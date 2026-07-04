package at.mafue.baumradar.dataprocessor.utils;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests für {@link CultivarNormalizer} (Schicht 1 der Artnamen-Harmonisierung),
 * durchgehend mit ECHTEN Schreibweisen aus den 19 Katastern (v. a. der
 * berüchtigten {@code Acer platanoides 'Columnare'}-Familie).
 */
public class CultivarNormalizerTest {

    // --- Zitierstil / Casing vereinheitlichen (der große, sichere Hebel) --------

    @Test
    public void unifiesAllQuoteStylesToIcncpForm() {
        String expected = "Acer platanoides 'Columnare'";
        assertEquals(expected, CultivarNormalizer.canonicalScientific("Acer platanoides 'Columnare'", null));
        assertEquals(expected, CultivarNormalizer.canonicalScientific("Acer platanoides -Columnare-", null));
        assertEquals(expected, CultivarNormalizer.canonicalScientific("Acer platanoides \"Columnare\"", null));
        assertEquals(expected, CultivarNormalizer.canonicalScientific("Acer platanoides 'columnare'", null));
        assertEquals(expected, CultivarNormalizer.canonicalScientific("Acer platanoides Columnare", null));
    }

    @Test
    public void pullsCultivarFromGermanFieldWhenLatinLacksIt() {
        // Genau das Gegenbeispiel des Nutzers: Latein hat die Sorte verloren,
        // der deutsche Name trägt sie.
        assertEquals("Acer platanoides 'Columnare'",
                CultivarNormalizer.canonicalScientific("Acer platanoides", "Spitzahorn 'Columnare'"));
    }

    @Test
    public void plainSpeciesStaysDistinctFromCultivar() {
        // Ohne Cross-Field würde ein schlichtes "Acer platanoides" fälschlich mit
        // einem Columnare verschmelzen — hier bleibt es die reine Art.
        assertEquals("Acer platanoides",
                CultivarNormalizer.canonicalScientific("Acer platanoides", "Spitz-Ahorn"));
    }

    // --- Mojibake-Reparatur -----------------------------------------------------

    @Test
    public void repairsDoubleEncodedUtf8() {
        assertEquals("Säulenahorn", CultivarNormalizer.repairMojibake("SÃ¤ulenahorn"));
        assertEquals("Säulenahorn", CultivarNormalizer.cleanGerman("SÃ¤ulenahorn"));
    }

    @Test
    public void leavesCleanTextUntouched() {
        assertEquals("Säulenahorn", CultivarNormalizer.repairMojibake("Säulenahorn"));
        assertEquals("Acer platanoides", CultivarNormalizer.repairMojibake("Acer platanoides"));
    }

    // --- Trademark-/Junk-/Platzhalter-Behandlung --------------------------------

    @Test
    public void stripsTrademarkSymbols() {
        // (R)/® werden entfernt. Ein daneben stehendes Marken-Wort ("Resista") lässt sich
        // auf Schicht 1 nicht sicher von einem Art-Epitheton unterscheiden und bleibt (klein) —
        // das ist Schicht-2-Material. Die Art zu verlieren wäre die schlechtere Wahl.
        assertEquals("Tilia cordata 'Greenspire'",
                CultivarNormalizer.canonicalScientific("Tilia cordata (R) 'Greenspire'", null));
        assertEquals("Ulmus resista 'New Horizon'",
                CultivarNormalizer.canonicalScientific("Ulmus Resista (R) 'New Horizon'", null));
    }

    @Test
    public void junkBecomesEmpty() {
        assertEquals("", CultivarNormalizer.canonicalScientific("unbekannt, nicht erfasst", null));
    }

    @Test
    public void placeholderSpeciesReducesToGenus() {
        assertEquals("Tilia", CultivarNormalizer.canonicalScientific("Tilia  species", null));
        assertEquals("Tilia", CultivarNormalizer.canonicalScientific("Tilia", null));
    }

    @Test
    public void handlesHybridMarker() {
        assertEquals("Platanus × hispanica",
                CultivarNormalizer.canonicalScientific("Platanus X hispanica", null));
    }

    @Test
    public void recognizesCapitalizedEpithetInScientificContext() {
        // Manche Kataster schreiben das Epitheton groß. Mit quotiertem Kultivar ist der
        // wissenschaftliche Kontext klar → die Art bleibt erhalten.
        assertEquals("Acer pseudoplatanus 'Leopoldii'",
                CultivarNormalizer.canonicalScientific("Acer Pseudoplatanus 'Leopoldii'", null));
        // Und verschiedene Arten mit gleichem Sortennamen dürfen NICHT fusionieren:
        assertNotEquals(
                CultivarNormalizer.identityKey("Ahorn", "Acer Pseudoplatanus 'Leopoldii'", null),
                CultivarNormalizer.identityKey("Ahorn", "Acer Platanoides 'Leopoldii'", null));
    }

    @Test
    public void appendCultivarBuildsGermanFallbackName() {
        // Gattungs-Fallback für Arten ohne deutschen Quellnamen: nie leer.
        assertEquals("Ahorn 'Bicolor'", CultivarNormalizer.appendCultivar("Ahorn", "Acer palmatum 'Bicolor'"));
        assertEquals("Ahorn", CultivarNormalizer.appendCultivar("Ahorn", "Acer palmatum"));
    }

    @Test
    public void keepsAbbreviatedEpithetInsteadOfDroppingSpecies() {
        // "sacchar." (= saccharinum) bleibt als Art erhalten statt zu verschwinden.
        String key = CultivarNormalizer.identityKey("Ahorn", "Acer sacchar. 'Laciniatum wieri'", null);
        assertEquals("acer|sacchar|laciniatum wieri", key);
    }

    @Test
    public void doesNotMangleEnglishCommonNames() {
        // species_en ist bei einigen Städten ein englischer Trivialname statt Latein.
        // "Norway Maple" darf NICHT zu "Norway 'Maple'" werden.
        assertEquals("Norway Maple", CultivarNormalizer.canonicalScientific("Norway Maple", "Spitz-Ahorn"));
        assertEquals("Sycamore Maple", CultivarNormalizer.canonicalScientific("Sycamore Maple", "Berg-Ahorn"));
    }

    // --- identityKey: verschmelzen was gleich ist, trennen was verschieden ist ---

    @Test
    public void keyMergesWordOrderAndRomanArabic() {
        String a = CultivarNormalizer.identityKey("Ahorn", "Acer platanoides 'Columnare Typ Ley II'", null);
        String b = CultivarNormalizer.identityKey("Ahorn", "Acer platanoides 'Columnare Ley Typ 2'", null);
        assertEquals("Wortstellung + II/2 müssen zum selben Schlüssel führen", a, b);
    }

    @Test
    public void keyKeepsGenuinelyDifferentCultivarsApart() {
        String ley1 = CultivarNormalizer.identityKey("Ahorn", "Acer platanoides 'Columnare Typ Ley I'", null);
        String ley2 = CultivarNormalizer.identityKey("Ahorn", "Acer platanoides 'Columnare Typ Ley II'", null);
        assertNotEquals("Ley I und Ley II sind verschiedene Selektionen", ley1, ley2);

        String columnare = CultivarNormalizer.identityKey("Ahorn", "Acer platanoides 'Columnare'", null);
        String globosum = CultivarNormalizer.identityKey("Ahorn", "Acer platanoides 'Globosum'", null);
        assertNotEquals(columnare, globosum);

        String platanoides = CultivarNormalizer.identityKey("Ahorn", "Acer platanoides", null);
        String campestre = CultivarNormalizer.identityKey("Ahorn", "Acer campestre", null);
        assertNotEquals("Verschiedene Arten bleiben getrennt", platanoides, campestre);
    }

    @Test
    public void keyIsStableAcrossQuoteAndCaseVariants() {
        String base = CultivarNormalizer.identityKey("Ahorn", "Acer platanoides 'Columnare'", null);
        assertEquals(base, CultivarNormalizer.identityKey("Ahorn", "Acer platanoides -Columnare-", null));
        assertEquals(base, CultivarNormalizer.identityKey("Ahorn", "Acer platanoides 'columnare'", null));
        assertEquals(base, CultivarNormalizer.identityKey("Ahorn", "Acer platanoides", "Säulenahorn 'Columnare'"));
    }
}
