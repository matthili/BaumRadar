package at.mafue.baumradar.webgis.loader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

/**
 * Alle PostGIS-Zugriffe des Loaders.
 *
 * Geometrien entstehen serverseitig in SQL: Bäume via {@code ST_MakePoint},
 * Allergiezonen via {@code ST_Buffer(geography, radius)} — der Radius liegt in
 * Metern vor, weshalb über den geography-Typ gebuffert wird (geodätisch korrekt)
 * statt in Grad.
 *
 * Jede Import-Operation öffnet ihre eigene Connection; die Klasse selbst hält
 * keinen Zustand und ist damit gefahrlos aus parallelen Virtual Threads nutzbar.
 */
final class PostGis {

    private static final int BATCH_SIZE = 5000;

    private final Config cfg;

    PostGis(Config cfg) {
        this.cfg = cfg;
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(cfg.pgUrl(), cfg.pgUser(), cfg.pgPassword());
    }

    /** Führt resources/schema.sql aus (idempotent, CREATE IF NOT EXISTS). */
    void initSchema() throws SQLException, IOException {
        String sql;
        try (InputStream in = PostGis.class.getResourceAsStream("/schema.sql")) {
            if (in == null) throw new IOException("schema.sql nicht im Klassenpfad");
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    /**
     * Befüllt die Gattungs-Statistik neu (Quelle des Filter-UI im Web-Client).
     * Ein einzelner GROUP-BY-Scan über alle Bäume — bewusst als Tabelle statt
     * View, damit WFS-Zugriffe nicht jedes Mal 2,6 Mio Zeilen aggregieren.
     *
     * @return Anzahl der Gattungen
     */
    int refreshGenusStats() throws SQLException {
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute("TRUNCATE genus_stats");
            return st.executeUpdate("""
                    INSERT INTO genus_stats (genus_de, genus_en, tree_count)
                    SELECT genus_de, min(genus_en), count(*)::int
                    FROM trees
                    WHERE genus_de IS NOT NULL AND genus_de <> ''
                    GROUP BY genus_de""");
        }
    }

    /**
     * Befüllt die Art-Tupel-Statistik neu (DISTINCT über Gattung + beide Artnamen,
     * analog zur Profil-Liste der App). Quelle der namens-übergreifenden Suche
     * im Web-Client; Zeilen ganz ohne Artangabe tragen zur Suche nichts bei.
     *
     * @return Anzahl der Art-Tupel
     */
    int refreshSpeciesStats() throws SQLException {
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute("TRUNCATE species_stats");
            return st.executeUpdate("""
                    INSERT INTO species_stats (genus_de, species_de, species_en, tree_count)
                    SELECT genus_de, coalesce(species_de, ''), coalesce(species_en, ''), count(*)::int
                    FROM trees
                    WHERE genus_de IS NOT NULL AND genus_de <> ''
                      AND (coalesce(species_de, '') <> '' OR coalesce(species_en, '') <> '')
                    GROUP BY 1, 2, 3""");
        }
    }

    /** Bereits importierte dataVersion je Stadt (für den Idempotenz-Vergleich). */
    Map<String, String> fetchImportState() throws SQLException {
        Map<String, String> state = new HashMap<>();
        try (Connection c = connect();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT city_id, data_version FROM import_state")) {
            while (rs.next()) {
                state.put(rs.getString(1), rs.getString(2));
            }
        }
        return state;
    }

    /**
     * Ersetzt den Datenbestand einer Stadt in einer Transaktion:
     * alte Zeilen löschen, Bäume + Zonen aus der SQLite-Datei batch-einfügen,
     * import_state fortschreiben. Bricht die Transaktion bei Fehlern komplett ab.
     *
     * @return [Baumanzahl, Zonenanzahl]
     */
    int[] importCity(Catalog.City city, Path sqliteDb) throws SQLException {
        try (Connection pg = connect();
             Connection sqlite = DriverManager.getConnection("jdbc:sqlite:" + sqliteDb)) {
            pg.setAutoCommit(false);
            try {
                try (PreparedStatement del = pg.prepareStatement("DELETE FROM trees WHERE city_id = ?")) {
                    del.setString(1, city.id());
                    del.executeUpdate();
                }
                try (PreparedStatement del = pg.prepareStatement("DELETE FROM allergy_zones WHERE city_id = ?")) {
                    del.setString(1, city.id());
                    del.executeUpdate();
                }

                int trees = copyTrees(city, sqlite, pg);
                int zones = copyZones(city, sqlite, pg);

                try (PreparedStatement up = pg.prepareStatement("""
                        INSERT INTO import_state (city_id, data_version, tree_count, zone_count, imported_at)
                        VALUES (?, ?, ?, ?, now())
                        ON CONFLICT (city_id) DO UPDATE SET
                          data_version = EXCLUDED.data_version,
                          tree_count   = EXCLUDED.tree_count,
                          zone_count   = EXCLUDED.zone_count,
                          imported_at  = now()""")) {
                    up.setString(1, city.id());
                    up.setString(2, city.dataVersion());
                    up.setInt(3, trees);
                    up.setInt(4, zones);
                    up.executeUpdate();
                }

                pg.commit();
                return new int[]{trees, zones};
            } catch (SQLException e) {
                pg.rollback();
                throw e;
            }
        }
    }

    private int copyTrees(Catalog.City city, Connection sqlite, Connection pg) throws SQLException {
        int count = 0;
        try (Statement src = sqlite.createStatement();
             ResultSet rs = src.executeQuery(
                     "SELECT id, lat, lon, genus_de, genus_en, species_de, species_en FROM trees");
             PreparedStatement ins = pg.prepareStatement("""
                     INSERT INTO trees (id, city_id, genus_de, genus_en, species_de, species_en, geom)
                     VALUES (?, ?, ?, ?, ?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326))""")) {
            while (rs.next()) {
                ins.setString(1, rs.getString("id"));
                ins.setString(2, city.id());
                ins.setString(3, rs.getString("genus_de"));
                ins.setString(4, rs.getString("genus_en"));
                ins.setString(5, rs.getString("species_de"));
                ins.setString(6, rs.getString("species_en"));
                ins.setDouble(7, rs.getDouble("lon"));   // ST_MakePoint(x=lon, y=lat)!
                ins.setDouble(8, rs.getDouble("lat"));
                ins.addBatch();
                if (++count % BATCH_SIZE == 0) ins.executeBatch();
            }
            ins.executeBatch();
        }
        return count;
    }

    private int copyZones(Catalog.City city, Connection sqlite, Connection pg) throws SQLException {
        int count = 0;
        try (Statement src = sqlite.createStatement();
             ResultSet rs = src.executeQuery(
                     "SELECT id, lat, lon, radius, count, genus_de FROM geofences");
             PreparedStatement ins = pg.prepareStatement("""
                     INSERT INTO allergy_zones (id, city_id, genus_de, radius_m, tree_count, geom)
                     VALUES (?, ?, ?, ?, ?,
                             ST_Buffer(ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?)::geometry)""")) {
            while (rs.next()) {
                ins.setString(1, rs.getString("id"));
                ins.setString(2, city.id());
                ins.setString(3, rs.getString("genus_de"));
                ins.setInt(4, rs.getInt("radius"));
                ins.setInt(5, rs.getInt("count"));
                ins.setDouble(6, rs.getDouble("lon"));
                ins.setDouble(7, rs.getDouble("lat"));
                ins.setInt(8, rs.getInt("radius"));
                ins.addBatch();
                if (++count % BATCH_SIZE == 0) ins.executeBatch();
            }
            ins.executeBatch();
        }
        return count;
    }
}
