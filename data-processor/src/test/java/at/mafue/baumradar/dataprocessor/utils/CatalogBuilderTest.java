package at.mafue.baumradar.dataprocessor.utils;

import at.mafue.baumradar.dataprocessor.providers.CityProvider;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Verifies the {@code catalog.json} writer: the {@code dataVersion} field that
 * drives the app's "tree data updated" detection, the selective-run behaviour
 * (only cities with published files are listed) and the {@code dataVersion}
 * round-trip used to preserve un-reprocessed cities.
 */
public class CatalogBuilderTest {

    /** Minimal CityProvider stub — CatalogBuilder only reads its metadata, never processData(). */
    private CityProvider fakeCity(String id, String name) {
        return new CityProvider() {
            public String getCityId() { return id; }
            public String getName() { return name; }
            public String getCountry() { return "Testland"; }
            public double[] getBoundingBox() { return new double[]{1.0, 2.0, 3.0, 4.0}; }
            public void processData(DatabaseExporter exporter) { /* unused */ }
        };
    }

    /** Fresh temp directory so we can place dummy {@code .db.gz} files next to the catalog. */
    private File tempDir() throws Exception {
        File dir = Files.createTempDirectory("catalogtest").toFile();
        dir.deleteOnExit();
        return dir;
    }

    /** Creates an (empty) published archive so {@code build()} treats the city as available. */
    private void publish(File dir, String cityId) throws Exception {
        File f = new File(dir, cityId + ".db.gz");
        Files.write(f.toPath(), new byte[]{0});
        f.deleteOnExit();
    }

    @Test
    public void catalogContainsDataVersionPerCity() throws Exception {
        File dir = tempDir();
        publish(dir, "wien");
        publish(dir, "graz");
        File out = new File(dir, "catalog.json");
        List<CityProvider> providers = Arrays.asList(fakeCity("wien", "Wien"), fakeCity("graz", "Graz"));
        Map<String, String> versions = new HashMap<>();
        versions.put("wien", "abc1230000000000");
        versions.put("graz", "def4560000000000");

        CatalogBuilder.build(out, providers, "https://example.com/", versions);
        String json = Files.readString(out.toPath());

        assertTrue(json.contains("\"id\": \"wien\""));
        assertTrue(json.contains("\"dataVersion\": \"abc1230000000000\""));
        assertTrue(json.contains("\"dataVersion\": \"def4560000000000\""));
        assertTrue(json.contains("\"dbUrl\": \"https://example.com/wien.db.gz\""));
        assertTrue(json.contains("\"sigUrl\": \"https://example.com/graz.db.gz.sig\""));
    }

    @Test
    public void missingDataVersionEmitsEmptyString() throws Exception {
        File dir = tempDir();
        publish(dir, "linz");
        File out = new File(dir, "catalog.json");
        List<CityProvider> providers = Collections.singletonList(fakeCity("linz", "Linz"));

        CatalogBuilder.build(out, providers, "https://example.com/", new HashMap<>());
        String json = Files.readString(out.toPath());

        assertTrue(json.contains("\"id\": \"linz\""));
        assertTrue("absent version → empty string, never the literal 'null'",
            json.contains("\"dataVersion\": \"\""));
    }

    @Test
    public void skipsCitiesWithoutPublishedData() throws Exception {
        File dir = tempDir();
        publish(dir, "wien");            // graz has NO archive on disk
        File out = new File(dir, "catalog.json");
        List<CityProvider> providers = Arrays.asList(fakeCity("wien", "Wien"), fakeCity("graz", "Graz"));

        CatalogBuilder.build(out, providers, "https://example.com/", new HashMap<>());
        String json = Files.readString(out.toPath());

        assertTrue("city with data is listed", json.contains("\"id\": \"wien\""));
        assertFalse("phantom city without data is omitted", json.contains("\"id\": \"graz\""));
    }

    @Test
    public void chunkedCityWithoutPlainArchiveIsStillListed() throws Exception {
        File dir = tempDir();
        File chunk = new File(dir, "berlin.db.gz.001");   // large city → only chunks on disk
        Files.write(chunk.toPath(), new byte[]{0});
        chunk.deleteOnExit();
        File out = new File(dir, "catalog.json");
        List<CityProvider> providers = Collections.singletonList(fakeCity("berlin", "Berlin"));

        CatalogBuilder.build(out, providers, "https://example.com/", new HashMap<>());
        String json = Files.readString(out.toPath());

        assertTrue(json.contains("\"id\": \"berlin\""));
        assertTrue("chunk url is emitted", json.contains("\"dbUrlChunks\": [\"https://example.com/berlin.db.gz.001\"]"));
    }

    @Test
    public void readDataVersionsRoundTrip() throws Exception {
        File dir = tempDir();
        publish(dir, "wien");
        publish(dir, "graz");
        File out = new File(dir, "catalog.json");
        Map<String, String> versions = new HashMap<>();
        versions.put("wien", "abc1230000000000");
        versions.put("graz", "def4560000000000");
        CatalogBuilder.build(out, Arrays.asList(fakeCity("wien", "Wien"), fakeCity("graz", "Graz")),
                "https://example.com/", versions);

        Map<String, String> read = CatalogBuilder.readDataVersions(out);
        assertEquals("abc1230000000000", read.get("wien"));
        assertEquals("def4560000000000", read.get("graz"));
        assertEquals(2, read.size());
    }

    @Test
    public void readDataVersionsReturnsEmptyForMissingFile() {
        Map<String, String> read = CatalogBuilder.readDataVersions(new File("does-not-exist-catalog.json"));
        assertTrue(read.isEmpty());
    }
}
