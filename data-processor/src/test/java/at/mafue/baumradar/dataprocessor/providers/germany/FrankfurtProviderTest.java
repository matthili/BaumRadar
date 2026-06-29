package at.mafue.baumradar.dataprocessor.providers.germany;

import at.mafue.baumradar.dataprocessor.models.TreeRecord;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Mapping tests for {@link FrankfurtProvider}, using the real CSV header layout
 * (ETRS89/UTM32N coordinates and a combined "botanical, German" name column).
 */
public class FrankfurtProviderTest {

    private static final String[] HEADERS = {
        "Objelt ID", "Baumnummer", "ETRS 89 Hochwert", "ETRS 89 Rechtswert", "Lateinischer Name",
        "Kronen Durchmesser in Meter", "Stamm Umfang in cm", "Strasse", "Baumhöhe", "Stamm Durchmesser", "Pflanzjahr"
    };

    @Test
    public void reprojectsUtm32AndSplitsLatinGermanName() {
        FrankfurtProvider p = new FrankfurtProvider();
        p.processHeaders(HEADERS);
        String[] row = {"1", "1", "5549510,9", "473366,2", "Platanus acerifolia, Gewöhnliche Platane",
            "8", "141", "Ackermannstrasse", "7", "45", "1920"};

        TreeRecord t = p.mapRowToTree(row, 1);
        assertNotNull(t);
        assertEquals("Platane", t.genusDe);
        assertEquals("Plane Tree", t.genusEn);
        assertEquals("Gewöhnliche Platane", t.speciesDe);
        assertEquals("Platanus acerifolia", t.speciesEn);
        assertEquals("frankfurt_1", t.id);
        assertTrue("lat near Frankfurt", t.latitude > 50.0 && t.latitude < 50.25);
        assertTrue("lon near Frankfurt", t.longitude > 8.4 && t.longitude < 8.9);
    }

    @Test
    public void skipsRowsWithoutNameOrCoordinates() {
        FrankfurtProvider p = new FrankfurtProvider();
        p.processHeaders(HEADERS);
        // Missing botanical name
        assertNull(p.mapRowToTree(
            new String[]{"9", "9", "5549510,9", "473366,2", "", "", "", "", "", "", ""}, 1));
        // Missing coordinates
        assertNull(p.mapRowToTree(
            new String[]{"9", "9", "", "", "Acer platanoides, Spitzahorn", "", "", "", "", "", ""}, 2));
    }

    @Test
    public void skipsCorruptOutOfRangeCoordinates() {
        FrankfurtProvider p = new FrankfurtProvider();
        p.processHeaders(HEADERS);
        // Extra digit in the Hochwert (8 digits) reprojects far outside Frankfurt → drop.
        String[] row = {"99", "99", "55556033,5", "471641,3", "Acer platanoides, Spitz-Ahorn",
            "", "", "", "", "", ""};
        assertNull(p.mapRowToTree(row, 1));
    }
}
