package at.mafue.baumradar.dataprocessor.utils;

import at.mafue.baumradar.dataprocessor.providers.*;
import at.mafue.baumradar.dataprocessor.providers.austria.*;
import at.mafue.baumradar.dataprocessor.providers.germany.*;
import at.mafue.baumradar.dataprocessor.providers.switzerland.*;
import at.mafue.baumradar.dataprocessor.models.*;
import at.mafue.baumradar.dataprocessor.utils.*;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.SQLException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes tree and geofence data into a per-city SQLite database file.
 *
 * <p>Each city processed by the pipeline receives its own SQLite file.
 * This class manages the JDBC connection lifecycle, creates the schema
 * ({@code trees} and {@code geofences} tables), and provides high-throughput
 * batch-insert methods that wrap records in explicit transactions.
 *
 * <p>On {@link #open()}, several SQLite PRAGMAs are set to maximize bulk-insert
 * performance at the cost of crash-safety — acceptable here because the database
 * is a disposable build artifact that can always be regenerated.
 */
public class DatabaseExporter {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseExporter.class);
    private final String dbPath;
    private Connection connection;

    /**
     * Running, order-independent content fingerprint over all inserted trees:
     * the sum (mod 2^256) of the per-row SHA-256 hashes. The tree {@code id} is
     * deliberately <em>excluded</em> so that cities whose ids are random UUIDs
     * (e.g. Köln, Würzburg, Stuttgart, Bonn) still yield a <em>stable</em> version
     * across runs when their underlying data is unchanged — only an actual change
     * in position, genus, species, or tree count moves the fingerprint.
     */
    private static final BigInteger FP_MOD = BigInteger.ONE.shiftLeft(256);
    private final MessageDigest rowDigest;
    private BigInteger fpSum = BigInteger.ZERO;
    private long fpCount = 0;

    public DatabaseExporter(String dbPath) {
        this.dbPath = dbPath;
        try {
            this.rowDigest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Opens a JDBC connection to the SQLite file and configures PRAGMAs for
     * maximum write throughput.
     *
     * @throws SQLException if the connection cannot be established
     */
    public void open() throws SQLException {
        logger.info("Opening SQLite database at {}", dbPath);
        // SQLite connection string
        String url = "jdbc:sqlite:" + dbPath;
        connection = DriverManager.getConnection(url);
        // Disable the rollback journal and synchronous writes — the database is a
        // disposable build artifact, so durability guarantees are unnecessary.
        // EXCLUSIVE locking prevents other processes from interfering during the
        // bulk import, and an in-memory temp store avoids disk I/O for sorting.
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA journal_mode = OFF;");
            stmt.execute("PRAGMA synchronous = 0;");
            stmt.execute("PRAGMA cache_size = 100000;");
            stmt.execute("PRAGMA locking_mode = EXCLUSIVE;");
            stmt.execute("PRAGMA temp_store = MEMORY;");
        }
    }

    /**
     * Drops and recreates the {@code trees} and {@code geofences} tables.
     *
     * <p>Existing tables are dropped unconditionally so that each pipeline
     * run produces a clean database without leftover rows from previous imports.
     *
     * @throws SQLException if schema creation fails
     */
    public void createTable() throws SQLException {
        String sqlTrees = "CREATE TABLE IF NOT EXISTS trees (" +
                     "id TEXT PRIMARY KEY NOT NULL, " +
                     "city_id TEXT NOT NULL, " +
                     "lat REAL NOT NULL, " +
                     "lon REAL NOT NULL, " +
                     "genus_de TEXT, " +
                     "genus_en TEXT, " +
                     "species_de TEXT, " +
                     "species_en TEXT);";
                     
        String sqlGeofences = "CREATE TABLE IF NOT EXISTS geofences (" +
                     "id TEXT PRIMARY KEY NOT NULL, " +
                     "lat REAL NOT NULL, " +
                     "lon REAL NOT NULL, " +
                     "radius INTEGER NOT NULL, " +
                     "count INTEGER NOT NULL, " +
                     "genus_de TEXT);";

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS trees;");
            stmt.execute("DROP TABLE IF EXISTS geofences;");
            stmt.execute(sqlTrees);
            stmt.execute(sqlGeofences);
        }
    }

    /**
     * Inserts a batch of geofence cluster records into the {@code geofences} table.
     *
     * <p>The entire batch is wrapped in an explicit transaction. On failure the
     * transaction is rolled back and the exception is re-thrown so that the
     * calling provider can decide how to handle the error.
     *
     * @param records list of geofence records to insert
     * @throws SQLException if the batch insert or commit fails
     */
    public void insertGeofences(List<GeofenceRecord> records) throws SQLException {
        if (records.isEmpty()) return;
        
        String sql = "INSERT INTO geofences(id, lat, lon, radius, count, genus_de) VALUES(?,?,?,?,?,?)";
        
        connection.setAutoCommit(false);
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            for (GeofenceRecord record : records) {
                pstmt.setString(1, record.id);
                pstmt.setDouble(2, record.latitude);
                pstmt.setDouble(3, record.longitude);
                pstmt.setInt(4, record.radius);
                pstmt.setInt(5, record.count);
                pstmt.setString(6, record.genusDe);
                pstmt.addBatch();
            }
            pstmt.executeBatch();
            connection.commit();
            logger.debug("Successfully committed {} geofence clusters to {}", records.size(), dbPath);
        } catch (SQLException e) {
            connection.rollback();
            logger.error("Failed to insert geofences into {}. Rolling back...", dbPath, e);
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    /**
     * Inserts a batch of tree records into the {@code trees} table.
     *
     * <p>Uses JDBC batch execution inside an explicit transaction for
     * throughput. Auto-commit is temporarily disabled and restored in a
     * {@code finally} block regardless of success or failure.
     *
     * @param records list of tree records to insert
     * @throws SQLException if the batch insert or commit fails
     */
    public void insertBatch(List<TreeRecord> records) throws SQLException {
        if (records.isEmpty()) return;
        
        String sql = "INSERT INTO trees(id, city_id, lat, lon, genus_de, genus_en, species_de, species_en) VALUES(?,?,?,?,?,?,?,?)";
        
        connection.setAutoCommit(false);
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            for (TreeRecord record : records) {
                // Schicht 1: Artnamen deterministisch harmonisieren (Chokepoint für alle
                // Städte), Schicht 3: Roh→kanonisch für den Import-Report erfassen.
                String rawDe = record.speciesDe;
                String rawEn = record.speciesEn;
                String canonEn = CultivarNormalizer.canonicalScientific(rawEn, rawDe);
                String key = CultivarNormalizer.identityKey(record.genusDe, rawEn, rawDe);
                // Schicht 2: kanonischer deutscher Artname aus der Alias-Tabelle
                // (Sorte wird angehängt); ohne Treffer der mojibake-bereinigte Original-Name.
                String aliasDe = SpeciesAliasTable.get().canonicalGerman(key, canonEn);
                if (aliasDe != null) {
                    record.speciesDe = aliasDe;
                } else {
                    String cleaned = CultivarNormalizer.cleanGerman(rawDe);
                    // Keinen deutschen Namen in der Quelle → Gattungsname (+ Sorte), damit
                    // die Anzeige nie leer ist, statt eines leeren deutschen Feldes.
                    record.speciesDe = (cleaned != null && !cleaned.isBlank())
                            ? cleaned : CultivarNormalizer.appendCultivar(record.genusDe, canonEn);
                }
                record.speciesEn = canonEn;
                HarmonizationReport.shared().record(rawDe, rawEn, canonEn, key);

                pstmt.setString(1, record.id);
                pstmt.setString(2, record.cityId);
                pstmt.setDouble(3, record.latitude);
                pstmt.setDouble(4, record.longitude);
                pstmt.setString(5, record.genusDe);
                pstmt.setString(6, record.genusEn);
                pstmt.setString(7, record.speciesDe);
                pstmt.setString(8, record.speciesEn);
                pstmt.addBatch();
                accumulateFingerprint(record);
            }
            pstmt.executeBatch();
            connection.commit();
            logger.debug("Successfully committed patch of {} records to {}", records.size(), dbPath);
        } catch (SQLException e) {
            connection.rollback();
            logger.error("Failed to insert batch into {}. Rolling back...", dbPath, e);
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    /** Folds one tree record into the order-independent content fingerprint
     *  (id-independent: only lat/lon/genus/species count). */
    private void accumulateFingerprint(TreeRecord r) {
        String canon = r.latitude + "|" + r.longitude + "|"
            + (r.genusDe == null ? "" : r.genusDe) + "|"
            + (r.speciesDe == null ? "" : r.speciesDe);
        byte[] h = rowDigest.digest(canon.getBytes(StandardCharsets.UTF_8));
        fpSum = fpSum.add(new BigInteger(1, h)).mod(FP_MOD);
        fpCount++;
    }

    /**
     * Returns a short (16 hex chars) content version over all inserted trees.
     *
     * <p>Order-independent and id-independent: stable across runs for unchanged
     * data even when row ids are random UUIDs, but changes whenever any tree's
     * position, genus, species, or the total tree count changes. Written into the
     * catalog as {@code dataVersion} so the app can detect when a downloaded
     * city's data has become stale and offer a refresh.
     */
    public String getContentVersion() {
        BigInteger mixed = fpSum.add(BigInteger.valueOf(fpCount)).mod(FP_MOD);
        String hex = mixed.toString(16);
        while (hex.length() < 16) hex = "0" + hex;
        return hex.substring(hex.length() - 16);
    }

    /** Closes the underlying JDBC connection if it is still open. */
    public void close() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
            logger.info("Closed SQLite database at {}", dbPath);
        }
    }
}

