package at.mafue.baumradar.dataprocessor.utils;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for {@link Translator#germanGenusFromLatin}, the shared helper that
 * normalizes botanical names to a consistent German genus across all city
 * providers (essential for cross-city allergy matching on {@code genusDe}).
 */
public class TranslatorLatinGenusTest {

    @Test
    public void mapsKnownLatinGeneraToGerman() {
        assertEquals("Birke", Translator.germanGenusFromLatin("Betula pubescens"));
        assertEquals("Linde", Translator.germanGenusFromLatin("Tilia cordata 'Greenspire'"));
        assertEquals("Esche", Translator.germanGenusFromLatin("Fraxinus angustifolia Raywood"));
        assertEquals("Ahorn", Translator.germanGenusFromLatin("Acer pseudoplatanus 'Atropurpureum'"));
        assertEquals("Hasel", Translator.germanGenusFromLatin("Corylus colurna"));
        assertEquals("Platane", Translator.germanGenusFromLatin("Platanus acerifolia (= hispanica)"));
        assertEquals("Schnurbaum", Translator.germanGenusFromLatin("Styphnolobium japonicum"));
    }

    @Test
    public void normalizesCapitalizationOfTheGenusToken() {
        assertEquals("Birke", Translator.germanGenusFromLatin("betula PENDULA"));
    }

    @Test
    public void fallsBackToLatinGenusWhenUnknown() {
        // Rhododendron has no German mapping → keep the capitalized Latin genus
        // so the value stays stable and groupable.
        assertEquals("Rhododendron", Translator.germanGenusFromLatin("Rhododendron 'Pink Pearl'"));
    }

    @Test
    public void handlesBlankAndNullInput() {
        assertEquals("", Translator.germanGenusFromLatin(""));
        assertEquals("", Translator.germanGenusFromLatin("   "));
        assertEquals("", Translator.germanGenusFromLatin(null));
    }

    @Test
    public void endToEndLatinToGermanToEnglish() {
        assertEquals("Birch", Translator.translateGenus(Translator.germanGenusFromLatin("Betula pendula")));
        assertEquals("Linden", Translator.translateGenus(Translator.germanGenusFromLatin("Tilia cordata")));
    }
}
