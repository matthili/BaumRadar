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
    /** Maps botanical (Latin) genus names to their German genus equivalent. */
    private static final Map<String, String> latinGenusDict = new HashMap<>();

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
        genusDict.put("Lärche", "Larch");
        genusDict.put("Apfel", "Apple");
        genusDict.put("Kirsche", "Cherry");
        genusDict.put("Pflaume", "Plum");
        genusDict.put("Birne", "Pear");
        genusDict.put("Erle", "Alder");
        genusDict.put("Ginkgo", "Ginkgo");
        genusDict.put("Robinie", "Black Locust");
        genusDict.put("Götterbaum", "Tree of Heaven");
        genusDict.put("Trompetenbaum", "Trumpet Tree");
        genusDict.put("Walnuss", "Walnut");
        // Additional genera introduced by the newer city providers
        genusDict.put("Edelkastanie", "Sweet Chestnut");
        genusDict.put("Mehlbeere", "Whitebeam");
        genusDict.put("Eberesche", "Rowan");
        genusDict.put("Weißdorn", "Hawthorn");
        genusDict.put("Gleditschie", "Honey Locust");
        genusDict.put("Schnurbaum", "Pagoda Tree");
        genusDict.put("Amberbaum", "Sweetgum");
        genusDict.put("Tulpenbaum", "Tulip Tree");
        genusDict.put("Magnolie", "Magnolia");
        genusDict.put("Blasenbaum", "Golden Rain Tree");
        genusDict.put("Zürgelbaum", "Hackberry");
        genusDict.put("Judasbaum", "Judas Tree");
        genusDict.put("Douglasie", "Douglas Fir");
        genusDict.put("Eibe", "Yew");
        genusDict.put("Urweltmammutbaum", "Dawn Redwood");
        // Less common genera unified by the extended Latin→German table below
        genusDict.put("Hartriegel", "Dogwood");
        genusDict.put("Maulbeere", "Mulberry");
        genusDict.put("Felsenbirne", "Serviceberry");
        genusDict.put("Blauglockenbaum", "Foxglove Tree");
        genusDict.put("Holunder", "Elder");
        genusDict.put("Hemlocktanne", "Hemlock");
        genusDict.put("Eisenholzbaum", "Persian Ironwood");
        genusDict.put("Geweihbaum", "Kentucky Coffeetree");
        genusDict.put("Zeder", "Cedar");
        genusDict.put("Mammutbaum", "Giant Sequoia");
        genusDict.put("Hibiskus", "Hibiscus");
        genusDict.put("Kuchenbaum", "Katsura");
        genusDict.put("Flieder", "Lilac");
        genusDict.put("Quitte", "Quince");
        genusDict.put("Ölweide", "Oleaster");
        genusDict.put("Sumpfzypresse", "Bald Cypress");
        genusDict.put("Mispel", "Medlar");
        genusDict.put("Wacholder", "Juniper");
        genusDict.put("Goldregen", "Laburnum");
        genusDict.put("Lebensbaum", "Arborvitae");
        genusDict.put("Bienenbaum", "Bee Bee Tree");
        genusDict.put("Taschentuchbaum", "Dove Tree");
        genusDict.put("Zypresse", "Cypress");
        genusDict.put("Tamariske", "Tamarisk");
        genusDict.put("Seidenbaum", "Silk Tree");
        genusDict.put("Essigbaum", "Sumac");
        genusDict.put("Buchsbaum", "Box");
        genusDict.put("Papiermaulbeere", "Paper Mulberry");
        genusDict.put("Perückenstrauch", "Smoke Tree");
        genusDict.put("Sicheltanne", "Japanese Cedar");
        genusDict.put("Korkbaum", "Amur Cork Tree");
        genusDict.put("Feige", "Fig");
        genusDict.put("Schneeball", "Viburnum");
        genusDict.put("Guttaperchabaum", "Hardy Rubber Tree");
        genusDict.put("Persimone", "Persimmon");
        genusDict.put("Pfaffenhütchen", "Spindle");
        genusDict.put("Hanfpalme", "Windmill Palm");
        genusDict.put("Glanzmispel", "Photinia");
        genusDict.put("Araukarie", "Monkey Puzzle");
        genusDict.put("Zwergmispel", "Cotoneaster");
        genusDict.put("Kopfeibe", "Plum Yew");
        genusDict.put("Flusszeder", "Incense Cedar");
        genusDict.put("Kreuzdorn", "Buckthorn");
        genusDict.put("Zelkove", "Zelkova");
        genusDict.put("Flügelnuss", "Wingnut");

        // Botanical (Latin) genus → German genus. Used by providers whose source
        // data ships only scientific names (e.g. Würzburg, Zug, Leipzig, Innsbruck),
        // so that allergy matching on genusDe stays consistent across all cities.
        latinGenusDict.put("Abies", "Tanne");
        latinGenusDict.put("Acer", "Ahorn");
        latinGenusDict.put("Aesculus", "Rosskastanie");
        latinGenusDict.put("Ailanthus", "Götterbaum");
        latinGenusDict.put("Alnus", "Erle");
        latinGenusDict.put("Betula", "Birke");
        latinGenusDict.put("Carpinus", "Hainbuche");
        latinGenusDict.put("Castanea", "Edelkastanie");
        latinGenusDict.put("Catalpa", "Trompetenbaum");
        latinGenusDict.put("Celtis", "Zürgelbaum");
        latinGenusDict.put("Cercis", "Judasbaum");
        latinGenusDict.put("Corylus", "Hasel");
        latinGenusDict.put("Crataegus", "Weißdorn");
        latinGenusDict.put("Fagus", "Buche");
        latinGenusDict.put("Fraxinus", "Esche");
        latinGenusDict.put("Ginkgo", "Ginkgo");
        latinGenusDict.put("Gleditsia", "Gleditschie");
        latinGenusDict.put("Juglans", "Walnuss");
        latinGenusDict.put("Koelreuteria", "Blasenbaum");
        latinGenusDict.put("Larix", "Lärche");
        latinGenusDict.put("Liquidambar", "Amberbaum");
        latinGenusDict.put("Liriodendron", "Tulpenbaum");
        latinGenusDict.put("Magnolia", "Magnolie");
        latinGenusDict.put("Malus", "Apfel");
        latinGenusDict.put("Metasequoia", "Urweltmammutbaum");
        latinGenusDict.put("Picea", "Fichte");
        latinGenusDict.put("Pinus", "Kiefer");
        latinGenusDict.put("Platanus", "Platane");
        latinGenusDict.put("Populus", "Pappel");
        latinGenusDict.put("Prunus", "Kirsche");
        latinGenusDict.put("Pseudotsuga", "Douglasie");
        latinGenusDict.put("Pyrus", "Birne");
        latinGenusDict.put("Quercus", "Eiche");
        latinGenusDict.put("Robinia", "Robinie");
        latinGenusDict.put("Salix", "Weide");
        latinGenusDict.put("Sophora", "Schnurbaum");
        latinGenusDict.put("Styphnolobium", "Schnurbaum");
        latinGenusDict.put("Sorbus", "Mehlbeere");
        latinGenusDict.put("Taxus", "Eibe");
        latinGenusDict.put("Tilia", "Linde");
        latinGenusDict.put("Ulmus", "Ulme");
        // Extended tail: rarer genera that previously fell back to their Latin name.
        latinGenusDict.put("Albizia", "Seidenbaum");
        latinGenusDict.put("Amelanchier", "Felsenbirne");
        latinGenusDict.put("Araucaria", "Araukarie");
        latinGenusDict.put("Broussonetia", "Papiermaulbeere");
        latinGenusDict.put("Buxus", "Buchsbaum");
        latinGenusDict.put("Calocedrus", "Flusszeder");
        latinGenusDict.put("Cedrus", "Zeder");
        latinGenusDict.put("Cephalotaxus", "Kopfeibe");
        latinGenusDict.put("Cercidiphyllum", "Kuchenbaum");
        latinGenusDict.put("Cornus", "Hartriegel");
        latinGenusDict.put("Cotinus", "Perückenstrauch");
        latinGenusDict.put("Cotoneaster", "Zwergmispel");
        latinGenusDict.put("Cryptomeria", "Sicheltanne");
        latinGenusDict.put("Cupressus", "Zypresse");
        latinGenusDict.put("Cydonia", "Quitte");
        latinGenusDict.put("Davidia", "Taschentuchbaum");
        latinGenusDict.put("Diospyros", "Persimone");
        latinGenusDict.put("Elaeagnus", "Ölweide");
        latinGenusDict.put("Eucommia", "Guttaperchabaum");
        latinGenusDict.put("Euodia", "Bienenbaum");
        latinGenusDict.put("Euonymus", "Pfaffenhütchen");
        latinGenusDict.put("Ficus", "Feige");
        latinGenusDict.put("Gymnocladus", "Geweihbaum");
        latinGenusDict.put("Hibiscus", "Hibiskus");
        latinGenusDict.put("Juniperus", "Wacholder");
        latinGenusDict.put("Laburnum", "Goldregen");
        latinGenusDict.put("Mespilus", "Mispel");
        latinGenusDict.put("Morus", "Maulbeere");
        latinGenusDict.put("Parrotia", "Eisenholzbaum");
        latinGenusDict.put("Paulownia", "Blauglockenbaum");
        latinGenusDict.put("Phellodendron", "Korkbaum");
        latinGenusDict.put("Photinia", "Glanzmispel");
        latinGenusDict.put("Platycladus", "Lebensbaum");
        latinGenusDict.put("Pterocarya", "Flügelnuss");
        latinGenusDict.put("Rhamnus", "Kreuzdorn");
        latinGenusDict.put("Rhus", "Essigbaum");
        latinGenusDict.put("Sambucus", "Holunder");
        latinGenusDict.put("Sequoia", "Mammutbaum");
        latinGenusDict.put("Sequoiadendron", "Mammutbaum");
        latinGenusDict.put("Syringa", "Flieder");
        latinGenusDict.put("Tamarix", "Tamariske");
        latinGenusDict.put("Taxodium", "Sumpfzypresse");
        latinGenusDict.put("Tetradium", "Bienenbaum");
        latinGenusDict.put("Thuja", "Lebensbaum");
        latinGenusDict.put("Trachycarpus", "Hanfpalme");
        latinGenusDict.put("Tsuga", "Hemlocktanne");
        latinGenusDict.put("Viburnum", "Schneeball");
        latinGenusDict.put("Zelkova", "Zelkove");

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
     * Derives the German genus name from a botanical (Latin) name.
     *
     * <p>Takes the first whitespace-delimited token (the genus, e.g.
     * {@code "Betula"} from {@code "Betula pubescens"}) and looks it up in the
     * Latin→German dictionary. Falls back to the capitalized Latin genus if no
     * mapping exists, so unknown genera still produce a stable, consistent key.
     *
     * @param botanicalName the scientific name (e.g. "Tilia cordata 'Greenspire'")
     * @return the German genus (e.g. "Linde"), or {@code ""} if the input is blank
     */
    public static String germanGenusFromLatin(String botanicalName) {
        if (botanicalName == null) return "";
        String clean = botanicalName.trim();
        if (clean.isEmpty()) return "";
        String latinGenus = clean.split("\\s+")[0];
        // Strip leading/trailing non-letters so source artifacts like "Unbekannt,"
        // normalize to a clean genus key.
        latinGenus = latinGenus.replaceAll("^[^\\p{L}]+|[^\\p{L}]+$", "");
        if (latinGenus.isEmpty()) return "";
        latinGenus = latinGenus.substring(0, 1).toUpperCase()
                + (latinGenus.length() > 1 ? latinGenus.substring(1).toLowerCase() : "");
        return latinGenusDict.getOrDefault(latinGenus, latinGenus);
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

