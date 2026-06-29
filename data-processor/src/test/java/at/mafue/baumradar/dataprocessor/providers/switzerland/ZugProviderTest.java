package at.mafue.baumradar.dataprocessor.providers.switzerland;

import at.mafue.baumradar.dataprocessor.models.TreeRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Mapping test for {@link ZugProvider}, using a real sample feature from the
 * canton of Zug's open-data endpoint. Confirms the (already WGS-84) coordinates
 * are taken as-is and the German genus is derived from the botanical name.
 */
public class ZugProviderTest {

    @Test
    public void mapsWgs84CoordinatesAndDerivesGenus() throws Exception {
        String json = "{ \"type\": \"Feature\","
            + " \"geometry\": { \"type\": \"Point\", \"coordinates\": [ 8.4867190108, 47.1820110167 ] },"
            + " \"properties\": { \"id\": \"cf6e5327-162e-4d0d-9a00-8b7979e64791\","
            + " \"pflanzennamebotanisch\": \"Betula pubescens\", \"pflanzennamedeutsch\": \"Moorbirke\" } }";

        TreeRecord t = new ZugProvider().mapFeatureToTree(new ObjectMapper().readTree(json));
        assertNotNull(t);
        assertEquals("Birke", t.genusDe);
        assertEquals("Birch", t.genusEn);
        assertEquals("Moorbirke", t.speciesDe);
        assertEquals("Betula pubescens", t.speciesEn);
        assertEquals(47.1820110167, t.latitude, 1e-7);
        assertEquals(8.4867190108, t.longitude, 1e-7);
        assertEquals("zug_cf6e5327-162e-4d0d-9a00-8b7979e64791", t.id);
    }
}
