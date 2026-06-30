package at.mafue.baumradar.dataprocessor.utils;

import at.mafue.baumradar.dataprocessor.models.TreeRecord;
import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests the id-independent content fingerprint ({@link DatabaseExporter#getContentVersion()})
 * that drives the app's "tree data updated" detection.
 */
public class DatabaseExporterTest {

    private TreeRecord tree(String id, double lat, double lon, String genus, String species) {
        return new TreeRecord(id, "test", lat, lon, genus, "GenusEn", species, "SpeciesEn");
    }

    private String versionOf(List<TreeRecord> records) throws Exception {
        File db = File.createTempFile("fp_test", ".db");
        db.delete();
        try {
            DatabaseExporter ex = new DatabaseExporter(db.getAbsolutePath());
            ex.open();
            ex.createTable();
            ex.insertBatch(records);
            String v = ex.getContentVersion();
            ex.close();
            return v;
        } finally {
            db.delete();
        }
    }

    @Test
    public void versionIsOrderAndIdIndependent() throws Exception {
        String v1 = versionOf(Arrays.asList(
            tree("a1", 48.1, 16.2, "Ahorn", "Spitzahorn"),
            tree("a2", 48.3, 16.4, "Linde", "Stadt-Linde")
        ));
        // Same data, reversed order, completely different ids (UUID-style churn).
        String v2 = versionOf(Arrays.asList(
            tree("zzz-9f3", 48.3, 16.4, "Linde", "Stadt-Linde"),
            tree("yyy-1a2", 48.1, 16.2, "Ahorn", "Spitzahorn")
        ));
        assertEquals("fingerprint must be order- and id-independent", v1, v2);
        assertEquals("16 hex chars", 16, v1.length());
    }

    @Test
    public void versionChangesWhenCoordinateChanges() throws Exception {
        String v1 = versionOf(Arrays.asList(tree("a", 48.1, 16.2, "Ahorn", "Spitzahorn")));
        String v2 = versionOf(Arrays.asList(tree("a", 48.1, 16.20001, "Ahorn", "Spitzahorn")));
        assertNotEquals("a moved tree must change the fingerprint", v1, v2);
    }

    @Test
    public void versionChangesWhenGenusOrCountChanges() throws Exception {
        String base = versionOf(Arrays.asList(tree("a", 48.1, 16.2, "Ahorn", "Spitzahorn")));
        String genusChanged = versionOf(Arrays.asList(tree("a", 48.1, 16.2, "Linde", "Spitzahorn")));
        String countChanged = versionOf(Arrays.asList(
            tree("a", 48.1, 16.2, "Ahorn", "Spitzahorn"),
            tree("b", 48.5, 16.6, "Ahorn", "Spitzahorn")
        ));
        assertNotEquals("a corrected genus must change the fingerprint", base, genusChanged);
        assertNotEquals("an added tree must change the fingerprint", base, countChanged);
    }
}
