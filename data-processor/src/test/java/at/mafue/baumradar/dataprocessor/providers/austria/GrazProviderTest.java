package at.mafue.baumradar.dataprocessor.providers.austria;

import at.mafue.baumradar.dataprocessor.models.TreeRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Mapping test for {@link GrazProvider}, using a real ArcGIS-REST GeoJSON
 * feature from the Baumkataster Holding service (already WGS-84 via outSR=4326).
 */
public class GrazProviderTest {

    @Test
    public void mapsArcgisFeatureAndDerivesGenusFromBotanicalName() throws Exception {
        String json = "{ \"type\": \"Feature\", \"id\": 1,"
            + " \"geometry\": { \"type\": \"Point\", \"coordinates\": [ 15.430696032110006, 47.036341806349832 ] },"
            + " \"properties\": { \"OBJECTID\": 1, \"BAUM_ART\": \"Quercus robur\","
            + " \"DEUTSCHER_NAME\": \"Stiel-Eiche\", \"BAUM_TYP\": \"Laubbaum\" } }";

        TreeRecord t = new GrazProvider().mapFeatureToTree(new ObjectMapper().readTree(json));
        assertNotNull(t);
        assertEquals("Eiche", t.genusDe);
        assertEquals("Oak", t.genusEn);
        assertEquals("Stiel-Eiche", t.speciesDe);
        assertEquals("Quercus robur", t.speciesEn);
        assertEquals("graz_1", t.id);
        assertEquals(47.036341806349832, t.latitude, 1e-9);
        assertEquals(15.430696032110006, t.longitude, 1e-9);
    }
}
