package at.mafue.baumradar.webgis.loader;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integrationstest gegen ein echtes PostGIS (Testcontainers).
 * Läuft nur im Maven-Profil {@code -Pit}, da Docker benötigt wird.
 *
 * Prüft die ganze Import-Strecke ab SQLite: Schema, Punkte, geodätisch
 * gebufferte Zonen-Polygone und die dataVersion-Idempotenz.
 */
@Tag("it")
@Testcontainers
class PostGisIT {

    @Container
    static final PostgreSQLContainer<?> POSTGIS = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("baumradar")
            .withUsername("baumradar")
            .withPassword("baumradar");

    @TempDir
    Path tempDir;

    private Config config() {
        return new Config(
                Config.DEFAULT_CATALOG_URL, Config.DEFAULT_PUBLIC_KEY,
                POSTGIS.getJdbcUrl(), "baumradar", "baumradar", "baumradar",
                "http://unused", "admin", "geoserver", "postgis", 5432,
                Set.of(), true, 1);
    }

    private Path miniSqlite() throws Exception {
        Path db = tempDir.resolve("testort.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE trees (id TEXT PRIMARY KEY NOT NULL, city_id TEXT NOT NULL, "
                    + "lat REAL NOT NULL, lon REAL NOT NULL, "
                    + "genus_de TEXT, genus_en TEXT, species_de TEXT, species_en TEXT)");
            st.execute("CREATE TABLE geofences (id TEXT PRIMARY KEY NOT NULL, "
                    + "lat REAL NOT NULL, lon REAL NOT NULL, "
                    + "radius INTEGER NOT NULL, count INTEGER NOT NULL, genus_de TEXT)");
            st.execute("INSERT INTO trees VALUES "
                    + "('testort_1','testort',48.20,16.37,'Birke','Birch','Hänge-Birke','Silver Birch'),"
                    + "('testort_2','testort',48.21,16.38,'Ahorn','Maple',NULL,NULL),"
                    + "('testort_3','testort',48.22,16.39,'Linde','Lime','Winterlinde','Small-leaved Lime')");
            st.execute("INSERT INTO geofences VALUES "
                    + "('testort_zone_1',48.205,16.375,60,5,'Birke')");
        }
        return db;
    }

    @Test
    void importsTreesAndBufferedZonesIdempotently() throws Exception {
        Config cfg = config();
        PostGis pg = new PostGis(cfg);
        pg.initSchema();

        Catalog.City city = new Catalog.City("testort", "Testort", "Österreich",
                new double[]{48.1, 16.3, 48.3, 16.4},
                "unused", "unused", null, "v1");

        int[] counts = pg.importCity(city, miniSqlite());
        assertEquals(3, counts[0]);
        assertEquals(1, counts[1]);

        try (Connection c = DriverManager.getConnection(
                POSTGIS.getJdbcUrl(), "baumradar", "baumradar");
             Statement st = c.createStatement()) {

            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM trees WHERE city_id='testort'")) {
                rs.next();
                assertEquals(3, rs.getInt(1));
            }

            // Zone muss ein echtes Polygon sein — und ~60 m Radius haben:
            // Fläche eines 60-m-Kreises ist ~11.310 m²; geography-Fläche grob prüfen.
            try (ResultSet rs = st.executeQuery("""
                    SELECT ST_GeometryType(geom), ST_Area(geom::geography)
                    FROM allergy_zones WHERE city_id='testort'""")) {
                rs.next();
                assertEquals("ST_Polygon", rs.getString(1));
                double area = rs.getDouble(2);
                assertTrue(area > 10500 && area < 11600,
                        "Kreisfläche unplausibel: " + area + " m²");
            }
        }

        // Idempotenz: gleiche dataVersion => Import nicht nötig.
        assertEquals("v1", pg.fetchImportState().get("testort"));

        // Re-Import mit neuer Version ersetzt sauber (keine Duplikate durch PK).
        Catalog.City v2 = new Catalog.City("testort", "Testort", "Österreich",
                new double[]{48.1, 16.3, 48.3, 16.4}, "unused", "unused", null, "v2");
        int[] counts2 = pg.importCity(v2, miniSqlite());
        assertEquals(3, counts2[0]);
        assertFalse(pg.fetchImportState().get("testort").equals("v1"));
    }
}
