package at.mafue.baumradar.dataprocessor.providers.austria;

import at.mafue.baumradar.dataprocessor.models.TreeRecord;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Mapping tests for the Austrian CSV providers (Vienna, Linz) using real header
 * layouts and sample rows. Both now normalize to a clean German genus for
 * cross-city allergy matching while preserving the full species name.
 */
public class AustriaCsvProviderTest {

    @Test
    public void viennaSplitsCombinedGenusArtAndKeepsSpecies() {
        ViennaProvider p = new ViennaProvider();
        p.processHeaders(new String[]{"FID", "OBJECTID", "SHAPE", "BAUM_ID", "DATENFUEHRUNG", "BEZIRK",
            "OBJEKT_STRASSE", "GEBIETSGRUPPE", "GATTUNG_ART", "PFLANZJAHR"});
        String[] row = {"BAUMKATOGD.803469791", "803469791", "POINT (16.271166879449012 48.190282342054005)",
            "352858", "magistrat", "13", "13., Streckerpark, MA42", "MA 42 - Parkanlage",
            "Celtis australis (Südlicher Zürgelbaum)", "2021"};

        TreeRecord t = p.mapRowToTree(row, 1);
        assertNotNull(t);
        assertEquals("Zürgelbaum", t.genusDe);                 // was the full combined string before
        assertEquals("Hackberry", t.genusEn);
        assertEquals("Südlicher Zürgelbaum", t.speciesDe);     // detail preserved (parenthetical)
        assertEquals("Celtis australis", t.speciesEn);
        assertEquals("803469791", t.id);
        assertEquals(48.190282342054005, t.latitude, 1e-7);
        assertEquals(16.271166879449012, t.longitude, 1e-7);
    }

    @Test
    public void linzDerivesGermanGenusFromLatinColumn() {
        LinzProvider p = new LinzProvider();
        p.processHeaders(new String[]{"Flaeche", "Gattung", "Art", "Sorte", "NameDeutsch", "Hoehe",
            "Schirmdurchmesser", "Stammumfang", "Typ", "XPos", "YPos", "lon", "lat", "BaumNr", "DatumExport"});
        String[] row = {"1127", "Betula", "pendula", " -", "Weiß-Birke", "16", "8", "200", "L", "68728",
            "353883", "14.25924976761750", "48.31945538154956", "005", "20260101"};

        TreeRecord t = p.mapRowToTree(row, 1);
        assertNotNull(t);
        assertEquals("Birke", t.genusDe);                      // was "Weiß-Birke" (species) before
        assertEquals("Birch", t.genusEn);
        assertEquals("Weiß-Birke", t.speciesDe);               // detail preserved
        assertEquals("Betula pendula", t.speciesEn);
        assertEquals("linz_1_005", t.id);
        assertEquals(48.31945538154956, t.latitude, 1e-7);
        assertEquals(14.25924976761750, t.longitude, 1e-7);
    }
}
