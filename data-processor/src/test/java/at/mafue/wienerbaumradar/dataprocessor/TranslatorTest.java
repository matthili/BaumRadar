package at.mafue.baumradar.dataprocessor;

import org.junit.Test;
import static org.junit.Assert.*;

public class TranslatorTest {

    @Test
    public void testKnownTranslations() {
        assertEquals("Maple", Translator.translateGenus("Ahorn"));
        assertEquals("Oak", Translator.translateGenus("Eiche"));
        assertEquals("Linden", Translator.translateGenus("Linde"));
    }

    @Test
    public void testUnknownTranslations() {
        assertEquals("Unbekanntes_Kraut", Translator.translateGenus("Unbekanntes_Kraut"));
        assertEquals("Unknown", Translator.translateGenus(null));
        assertEquals("", Translator.translateGenus(""));
    }
}
