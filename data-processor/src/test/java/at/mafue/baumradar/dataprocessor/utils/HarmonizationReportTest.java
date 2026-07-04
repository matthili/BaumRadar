package at.mafue.baumradar.dataprocessor.utils;

import org.junit.Test;

import java.io.StringWriter;

import static org.junit.Assert.*;

/**
 * Tests für {@link HarmonizationReport} (Schicht 3). Speisen echte Roh-Varianten
 * in eine frische Instanz und prüfen den erzeugten Report-Text.
 */
public class HarmonizationReportTest {

    @Test
    public void clustersQuoteAndCaseVariantsUnderOneCanonicalName() throws Exception {
        HarmonizationReport r = new HarmonizationReport();
        String canon = "Acer platanoides 'Columnare'";
        r.record("Säulenahorn", "Acer platanoides 'Columnare'", canon,
                CultivarNormalizer.identityKey("Ahorn", "Acer platanoides 'Columnare'", null));
        r.record("Säulenförmiger Spitz-Ahorn", "Acer platanoides -Columnare-", canon,
                CultivarNormalizer.identityKey("Ahorn", "Acer platanoides -Columnare-", null));
        r.record("x", "Acer platanoides 'columnare'", canon,
                CultivarNormalizer.identityKey("Ahorn", "Acer platanoides 'columnare'", null));

        StringWriter sw = new StringWriter();
        r.write(sw);
        String out = sw.toString();

        assertTrue("kanonischer Name im Report", out.contains(canon));
        assertTrue("drei Schreibweisen geclustert", out.contains("3 Schreibweisen"));
    }

    @Test
    public void flagsMojibakeAndJunk() throws Exception {
        HarmonizationReport r = new HarmonizationReport();
        r.record("SÃ¤ulenahorn", "Acer platanoides", "Acer platanoides",
                CultivarNormalizer.identityKey("Ahorn", "Acer platanoides", "SÃ¤ulenahorn"));
        r.record("", "unbekannt, nicht erfasst", "", ""); // Junk → leerer Schlüssel

        StringWriter sw = new StringWriter();
        r.write(sw);
        String out = sw.toString();

        assertTrue(out.contains("Mojibake"));
        assertTrue(out.contains("SÃ¤ulenahorn"));
        assertTrue(out.contains("unbekannt, nicht erfasst"));
    }
}
