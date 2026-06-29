package at.mafue.baumradar.dataprocessor.providers.switzerland;

import at.mafue.baumradar.dataprocessor.models.TreeRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Mapping tests for the Swiss GeoJSON providers Basel and Zürich, using real
 * sample features. Both normalize the Latin genus to a clean German genus while
 * preserving the full species name.
 */
public class SwissGeoJsonProviderTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void baselNormalizesLatinGenus() throws Exception {
        String json = "{ \"type\": \"Feature\","
            + " \"geometry\": { \"type\": \"Point\", \"coordinates\": [ 7.653156010442424, 47.582552015558115 ] },"
            + " \"properties\": { \"gml_id\": \"BA_Baeume.309398\","
            + " \"baumart_lateinisch\": \"Tilia cordata\", \"baumart_deutsch\": \"Winter-Linde\" } }";

        TreeRecord t = new BaselProvider().mapFeatureToTree(mapper.readTree(json));
        assertNotNull(t);
        assertEquals("Linde", t.genusDe);                 // was "Tilia" before harmonization
        assertEquals("Linden", t.genusEn);
        assertEquals("Winter-Linde", t.speciesDe);        // detail preserved
        assertEquals("Tilia cordata", t.speciesEn);
        assertEquals("basel_BA_Baeume.309398", t.id);
        assertEquals(47.582552015558115, t.latitude, 1e-7);
        assertEquals(7.653156010442424, t.longitude, 1e-7);
    }

    @Test
    public void zurichNormalizesLatinGenus() throws Exception {
        String json = "{ \"type\": \"Feature\","
            + " \"geometry\": { \"type\": \"Point\", \"coordinates\": [ 8.509643, 47.321987 ] },"
            + " \"properties\": { \"baumnummer\": \"nn-1851\", \"baumgattunglat\": \"Juglans\","
            + " \"baumnamedeu\": \"Walnuss-Obstgehölz, Sorte unbekannt\", \"baumnamelat\": \"Juglas regia cv.\" } }";

        TreeRecord t = new ZurichProvider().mapFeatureToTree(mapper.readTree(json));
        assertNotNull(t);
        assertEquals("Walnuss", t.genusDe);               // was "Juglans" before harmonization
        assertEquals("Walnut", t.genusEn);
        assertEquals("Walnuss-Obstgehölz, Sorte unbekannt", t.speciesDe);  // detail preserved
        assertEquals("Juglas regia cv.", t.speciesEn);
        assertEquals("zurich_nn-1851", t.id);
        assertEquals(47.321987, t.latitude, 1e-6);
        assertEquals(8.509643, t.longitude, 1e-6);
    }
}
