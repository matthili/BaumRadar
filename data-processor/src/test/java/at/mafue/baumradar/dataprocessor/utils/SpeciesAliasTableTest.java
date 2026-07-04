package at.mafue.baumradar.dataprocessor.utils;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.*;

/**
 * Tests für {@link SpeciesAliasTable} (Schicht 2). Prüft die mechanische
 * Sorten-Anhängung, die Vereinheitlichung des deutschen Namens über verschiedene
 * Roh-Schreibweisen hinweg und den Durchreiche-Fall für nicht abgedeckte Arten.
 */
public class SpeciesAliasTableTest {

    /** Kombiniert Schicht 1 (Latein-Kanonisierung + Schlüssel) mit dem Alias-Lookup. */
    private static String germanFor(SpeciesAliasTable t, String rawEn, String rawDe) {
        String canonEn = CultivarNormalizer.canonicalScientific(rawEn, rawDe);
        String key = CultivarNormalizer.identityKey("egal", rawEn, rawDe);
        return t.canonicalGerman(key, canonEn, CultivarNormalizer.cleanGerman(rawDe));
    }

    @Test
    public void appendsCultivarMechanically() {
        SpeciesAliasTable t = new SpeciesAliasTable(Map.of("acer|platanoides", "Spitz-Ahorn"));
        assertEquals("Spitz-Ahorn 'Columnare'", germanFor(t, "Acer platanoides 'Columnare'", null));
        assertEquals("Spitz-Ahorn", germanFor(t, "Acer platanoides", null)); // ohne Sorte
    }

    @Test
    public void unifiesGermanNameAcrossRawSpellings() {
        SpeciesAliasTable t = new SpeciesAliasTable(Map.of("acer|platanoides", "Spitz-Ahorn"));
        String[] raws = {
                "Acer platanoides 'Columnare'",
                "Acer platanoides -Columnare-",
                "Acer platanoides \"Columnare\"",
                "Acer platanoides 'columnare'",
                "Acer platanoides Columnare",
        };
        for (String raw : raws) {
            assertEquals("Roh: " + raw, "Spitz-Ahorn 'Columnare'", germanFor(t, raw, null));
        }
        // Cross-Field: Sorte steckt im deutschen Feld, Latein ist nackt.
        assertEquals("Spitz-Ahorn 'Columnare'",
                germanFor(t, "Acer platanoides", "Säulenförmiger Spitz-Ahorn 'Columnare'"));
    }

    @Test
    public void preservesStandaloneGermanFormButUnifiesVariantsAndMergedNames() {
        SpeciesAliasTable t = new SpeciesAliasTable(Map.of(
                "acer|platanoides", "Spitz-Ahorn", "quercus|robur", "Stiel-Eiche"));
        // Reine Schreibvarianten des Basisnamens → vereinheitlicht:
        assertEquals("Spitz-Ahorn", germanFor(t, "Acer platanoides", "Spitzahorn"));
        assertEquals("Spitz-Ahorn", germanFor(t, "Acer platanoides", "Spitz-ahorn"));
        // Zusammengemischte Doppelnamen → auf den Basisnamen gezogen:
        assertEquals("Stiel-Eiche", germanFor(t, "Quercus robur", "Sommer-Eiche, Stiel-Eiche"));
        assertEquals("Stiel-Eiche", germanFor(t, "Quercus robur", "Sommer-Eiche - Stiel-Eiche"));
        // Eigenständige Form ohne Latein-Sorte → BLEIBT erhalten (nicht plattgemacht):
        assertEquals("Kugel-Ahorn", germanFor(t, "Acer platanoides", "Kugel-Ahorn"));
        assertEquals("Blut-Ahorn", germanFor(t, "Acer platanoides", "Blut-Ahorn"));
    }

    @Test
    public void returnsNullForUncoveredSpecies() {
        SpeciesAliasTable t = new SpeciesAliasTable(Map.of("acer|platanoides", "Spitz-Ahorn"));
        assertNull(germanFor(t, "Acer monspessulanum", null));
    }

    @Test
    public void genusOnlyEntryMapsToGenusName() {
        SpeciesAliasTable t = new SpeciesAliasTable(Map.of("tilia", "Linde"));
        assertEquals("Linde", germanFor(t, "Tilia", null));
    }

    @Test
    public void realTableLoadsAndCoversCommonSpecies() {
        SpeciesAliasTable real = SpeciesAliasTable.get();
        assertTrue("Alias-Tabelle geladen: " + real.size(), real.size() > 40);
        assertEquals("Spitz-Ahorn 'Columnare'",
                germanFor(real, "Acer platanoides -Columnare-", null));
        assertEquals("Winter-Linde", germanFor(real, "Tilia cordata", null));
        assertEquals("Stiel-Eiche 'Fastigiata'", germanFor(real, "Quercus robur 'Fastigiata'", null));
    }
}
