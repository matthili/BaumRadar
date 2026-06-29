package at.mafue.baumradar.dataprocessor.providers.austria;

import at.mafue.baumradar.dataprocessor.models.TreeRecord;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Mapping tests for {@link InnsbruckProvider}, using the real header layout of
 * the Innsbruck XLSX (which carries ready-made WGS-84 Lon/Lat columns).
 */
public class InnsbruckProviderTest {

    private static final String[] HEADERS =
        {"OBJECTID", "Anlage", "Baumnummer", "Gattung_Dt", "Gattung_Lat", "Pflanzdatum", "X", "Y", "Lon", "Lat"};

    @Test
    public void mapsRowUsingHeaderIndicesAndDerivesGenus() {
        InnsbruckProvider p = new InnsbruckProvider();
        p.processHeaders(HEADERS);
        String[] row = {"1001", "Hofgarten", "B-5", "Winterlinde", "Tilia cordata", "2008", "", "", "11.3897", "47.2685"};

        TreeRecord t = p.mapRowToTree(row, 1);
        assertNotNull(t);
        assertEquals("Linde", t.genusDe);
        assertEquals("Linden", t.genusEn);
        assertEquals("Winterlinde", t.speciesDe);
        assertEquals("Tilia cordata", t.speciesEn);
        assertEquals("innsbruck_1001", t.id);
        assertEquals(47.2685, t.latitude, 1e-6);
        assertEquals(11.3897, t.longitude, 1e-6);
    }

    @Test
    public void toleratesGermanDecimalComma() {
        InnsbruckProvider p = new InnsbruckProvider();
        p.processHeaders(HEADERS);
        String[] row = {"2", "", "", "Gemeine Esche", "Fraxinus excelsior", "", "", "", "11,40", "47,27"};

        TreeRecord t = p.mapRowToTree(row, 1);
        assertNotNull(t);
        assertEquals("Esche", t.genusDe);
        assertEquals(11.40, t.longitude, 1e-6);
        assertEquals(47.27, t.latitude, 1e-6);
    }

    @Test
    public void skipsRowWithoutCoordinates() {
        InnsbruckProvider p = new InnsbruckProvider();
        p.processHeaders(HEADERS);
        String[] row = {"7", "", "", "Esche", "Fraxinus excelsior", "", "", "", "", ""};
        assertNull(p.mapRowToTree(row, 2));
    }
}
