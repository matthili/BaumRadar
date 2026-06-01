package at.mafue.baumradar.dataprocessor.utils;

import at.mafue.baumradar.dataprocessor.providers.*;
import at.mafue.baumradar.dataprocessor.providers.austria.*;
import at.mafue.baumradar.dataprocessor.providers.germany.*;
import at.mafue.baumradar.dataprocessor.providers.switzerland.*;
import at.mafue.baumradar.dataprocessor.models.*;
import at.mafue.baumradar.dataprocessor.utils.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Static lookup dictionary that translates German tree genus and species names
 * into their English equivalents.
 *
 * <p>Translations are stored in two in-memory {@link java.util.HashMap}s that
 * are populated once in a static initializer block.  If a German term has no
 * known translation, the original term is returned unchanged — this is
 * intentional because many botanical names (e.g. "Ginkgo") are identical in
 * both languages or are Latin terms used internationally.
 */
public class Translator {
    private static final Map<String, String> genusDict = new HashMap<>();
    private static final Map<String, String> speciesDict = new HashMap<>();

    static {
        // Genera Translations
        genusDict.put("Ahorn", "Maple");
        genusDict.put("Birke", "Birch");
        genusDict.put("Eiche", "Oak");
        genusDict.put("Esche", "Ash");
        genusDict.put("Kiefer", "Pine");
        genusDict.put("Linde", "Linden");
        genusDict.put("Kastanie", "Chestnut");
        genusDict.put("Rosskastanie", "Horse Chestnut");
        genusDict.put("Pappel", "Poplar");
        genusDict.put("Platane", "Plane Tree");
        genusDict.put("Fichte", "Spruce");
        genusDict.put("Tanne", "Fir");
        genusDict.put("Buche", "Beech");
        genusDict.put("Hainbuche", "Hornbeam");
        genusDict.put("Weide", "Willow");
        genusDict.put("Ulme", "Elm");
        genusDict.put("Hasel", "Hazel");
        genusDict.put("LÃ¤rche", "Larch");
        genusDict.put("Apfel", "Apple");
        genusDict.put("Kirsche", "Cherry");
        genusDict.put("Pflaume", "Plum");
        genusDict.put("Birne", "Pear");
        genusDict.put("Erle", "Alder");
        genusDict.put("Ginkgo", "Ginkgo");
        genusDict.put("Robinie", "Black Locust");
        genusDict.put("GÃ¶tterbaum", "Tree of Heaven");
        genusDict.put("Trompetenbaum", "Trumpet Tree");
        genusDict.put("Walnuss", "Walnut");

        // We can leave specific species generally as they are or translate common ones.
        // If not found, we fallback to the German name.
        speciesDict.put("Spitz-Ahorn", "Norway Maple");
        speciesDict.put("Berg-Ahorn", "Sycamore Maple");
        speciesDict.put("Feld-Ahorn", "Field Maple");
        speciesDict.put("HÃ¤nge-Birke", "Silver Birch");
        speciesDict.put("Stiel-Eiche", "English Oak");
        speciesDict.put("Winter-Linde", "Small-leaved Linden");
        speciesDict.put("Sommer-Linde", "Large-leaved Linden");
    }

    /**
     * Translates a German genus name to English.
     *
     * @param germanTerm the German genus name (e.g. "Eiche")
     * @return the English equivalent (e.g. "Oak"), or the original term if no
     *         translation exists, or {@code "Unknown"} if the input is {@code null}
     */
    public static String translateGenus(String germanTerm) {
        if (germanTerm == null) return "Unknown";
        String clean = germanTerm.trim();
        return genusDict.getOrDefault(clean, clean);
    }

    /**
     * Translates a German species name to English.
     *
     * @param germanTerm the German species name (e.g. "Stiel-Eiche")
     * @return the English equivalent (e.g. "English Oak"), or the original term if
     *         no translation exists, or {@code "Unknown"} if the input is {@code null}
     */
    public static String translateSpecies(String germanTerm) {
        if (germanTerm == null) return "Unknown";
        String clean = germanTerm.trim();
        return speciesDict.getOrDefault(clean, clean);
    }
}

