package at.mafue.baumradar.dataprocessor;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.SQLException;
import java.util.List;

public class DatabaseExporter {
    private final String dbPath;
    private Connection connection;

    public DatabaseExporter(String dbPath) {
        this.dbPath = dbPath;
    }

    public void open() throws SQLException {
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
        String sql = "CREATE TABLE IF NOT EXISTS trees (" +
                     "id TEXT PRIMARY KEY NOT NULL, " +
                     "city_id TEXT NOT NULL, " +
                     "lat REAL NOT NULL, " +
                     "lon REAL NOT NULL, " +
                     "genus_de TEXT, " +
                     "genus_en TEXT, " +
                     "species_de TEXT, " +
                     "species_en TEXT);";
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS trees;");
            stmt.execute(sql);
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
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public void close() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }
}
