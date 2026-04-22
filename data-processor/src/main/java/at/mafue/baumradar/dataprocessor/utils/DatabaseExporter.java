package at.mafue.baumradar.dataprocessor.utils;

import at.mafue.baumradar.dataprocessor.providers.*;
import at.mafue.baumradar.dataprocessor.providers.austria.*;
import at.mafue.baumradar.dataprocessor.providers.germany.*;
import at.mafue.baumradar.dataprocessor.providers.switzerland.*;
import at.mafue.baumradar.dataprocessor.models.*;
import at.mafue.baumradar.dataprocessor.utils.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.SQLException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DatabaseExporter {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseExporter.class);
    private final String dbPath;
    private Connection connection;

    public DatabaseExporter(String dbPath) {
        this.dbPath = dbPath;
    }

    public void open() throws SQLException {
        logger.info("Opening SQLite database at {}", dbPath);
        // SQLite connection string
        String url = "jdbc:sqlite:" + dbPath;
        connection = DriverManager.getConnection(url);
        // Optimize SQLite pragmas for bulk insert
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA journal_mode = OFF;");
            stmt.execute("PRAGMA synchronous = 0;");
            stmt.execute("PRAGMA cache_size = 100000;");
            stmt.execute("PRAGMA locking_mode = EXCLUSIVE;");
            stmt.execute("PRAGMA temp_store = MEMORY;");
        }
    }

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

    public void insertBatch(List<TreeRecord> records) throws SQLException {
        if (records.isEmpty()) return;
        
        String sql = "INSERT INTO trees(id, city_id, lat, lon, genus_de, genus_en, species_de, species_en) VALUES(?,?,?,?,?,?,?,?)";
        
        connection.setAutoCommit(false);
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            for (TreeRecord record : records) {
                pstmt.setString(1, record.id);
                pstmt.setString(2, record.cityId);
                pstmt.setDouble(3, record.latitude);
                pstmt.setDouble(4, record.longitude);
                pstmt.setString(5, record.genusDe);
                pstmt.setString(6, record.genusEn);
                pstmt.setString(7, record.speciesDe);
                pstmt.setString(8, record.speciesEn);
                pstmt.addBatch();
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

    public void close() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
            logger.info("Closed SQLite database at {}", dbPath);
        }
    }
}

