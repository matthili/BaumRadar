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
 * Verifies the {@code catalog.json} writer, in particular the {@code dataVersion}
 * field that drives the app's "tree data updated" detection.
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

    @Test
    public void catalogContainsDataVersionPerCity() throws Exception {
        File out = File.createTempFile("catalog", ".json");
        out.deleteOnExit();
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
        File out = File.createTempFile("catalog_empty", ".json");
        out.deleteOnExit();
        List<CityProvider> providers = Collections.singletonList(fakeCity("linz", "Linz"));

        CatalogBuilder.build(out, providers, "https://example.com/", new HashMap<>());
        String json = Files.readString(out.toPath());

        assertTrue(json.contains("\"id\": \"linz\""));
        assertTrue("absent version → empty string, never the literal 'null'",
            json.contains("\"dataVersion\": \"\""));
    }
}
