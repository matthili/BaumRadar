package at.mafue.baumradar.dataprocessor.utils;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Eigenschafts-/Invarianten-Tests — sie prüfen NICHT einzelne Beispiel-Ausgaben,
 * sondern Regeln, die für ALLE Eingaben gelten müssen. Das schließt die Lücke der
 * beispielbasierten Tests: der Normalizer ist gattungsblind, deshalb bringen zehn
 * weitere Gattungen als Beispiel keine neue Abdeckung — diese Invarianten schon.
 *
 * <p>Bewusst gattungsübergreifend (Acer, Betula, Tilia, Malus, Prunus, Fagus,
 * Quercus, Carpinus, Pinus, Ulmus) mit teils GLEICHEN Sortennamen an verschiedenen
 * Arten ('Fastigiata' an Betula, Quercus, Carpinus) — die dürfen sich nie vermischen.
 */
public class CultivarNormalizerInvariantTest {

    /** (Gattung, Art, Kultivar) — bewusst paarweise verschiedene Token-Mengen. */
    private static final String[][] IDENTITIES = {
            {"Acer", "platanoides", ""},
            {"Acer", "platanoides", "Columnare"},
            {"Acer", "platanoides", "Globosum"},
            {"Acer", "platanoides", "Columnare Ley I"},
            {"Acer", "platanoides", "Columnare Ley II"},
            {"Acer", "campestre", ""},
            {"Acer", "campestre", "Elsrijk"},
            {"Betula", "pendula", ""},
            {"Betula", "pendula", "Fastigiata"},
            {"Betula", "pendula", "Youngii"},
            {"Tilia", "cordata", ""},
            {"Tilia", "cordata", "Greenspire"},
            {"Malus", "domestica", ""},
            {"Prunus", "avium", "Plena"},
            {"Prunus", "serrulata", "Kanzan"},
            {"Fagus", "sylvatica", ""},
            {"Fagus", "sylvatica", "Atropunicea"},
            {"Quercus", "robur", "Fastigiata"},
            {"Carpinus", "betulus", "Fastigiata"},
            {"Pinus", "nigra", ""},
            {"Ulmus", "", "New Horizon"}, // Kultivar direkt an der Gattung
    };

    /** Erzeugt (en, de)-Schreibvarianten, die ALLE dieselbe Identität meinen. */
    private static List<String[]> variants(String genus, String species, String cultivar) {
        List<String[]> out = new ArrayList<>();
        String bino = species.isEmpty() ? genus : genus + " " + species;
        if (cultivar.isEmpty()) {
            out.add(new String[]{bino, ""});
            out.add(new String[]{genus + "   " + species, ""});         // Whitespace
            out.add(new String[]{bino.toLowerCase(), ""});              // Gattung klein
        } else {
            out.add(new String[]{bino + " '" + cultivar + "'", ""});
            out.add(new String[]{bino + " \"" + cultivar + "\"", ""});
            out.add(new String[]{bino + " -" + cultivar + "-", ""});
            out.add(new String[]{bino + " '" + cultivar.toLowerCase() + "'", ""});
            out.add(new String[]{bino + "  '" + cultivar + "'", ""});   // Whitespace
            out.add(new String[]{bino, "Trivialname '" + cultivar + "'"}); // Cross-Field
            if (!species.isEmpty()) {
                out.add(new String[]{bino + " " + cultivar, ""});       // unquotiert (nur mit Art sicher)
            }
            String[] words = cultivar.split(" ");
            if (words.length > 1) {
                List<String> rev = new ArrayList<>(Arrays.asList(words));
                Collections.reverse(rev);
                out.add(new String[]{bino + " '" + String.join(" ", rev) + "'", ""}); // Wortstellung
                String arabic = cultivar.replaceAll("\\bII\\b", "2").replaceAll("\\bI\\b", "1");
                if (!arabic.equals(cultivar)) {
                    out.add(new String[]{bino + " '" + arabic + "'", ""}); // römisch→arabisch
                }
            }
        }
        return out;
    }

    @Test
    public void allSpellingVariantsOfOneIdentityShareOneKey() {
        for (String[] id : IDENTITIES) {
            Set<String> keys = new HashSet<>();
            List<String> witnesses = new ArrayList<>();
            for (String[] v : variants(id[0], id[1], id[2])) {
                String key = CultivarNormalizer.identityKey(id[0], v[0], v[1]);
                keys.add(key);
                witnesses.add("   [" + v[0] + " | " + v[1] + "] → " + key);
            }
            assertEquals("Alle Schreibweisen von " + Arrays.toString(id)
                            + " müssen denselben Schlüssel ergeben:\n" + String.join("\n", witnesses),
                    1, keys.size());
        }
    }

    @Test
    public void differentIdentitiesNeverCollideOnAKey() {
        Map<String, String[]> owner = new HashMap<>();
        for (String[] id : IDENTITIES) {
            String canonInput = (id[1].isEmpty() ? id[0] : id[0] + " " + id[1])
                    + (id[2].isEmpty() ? "" : " '" + id[2] + "'");
            String key = CultivarNormalizer.identityKey(id[0], canonInput, "");
            String[] prev = owner.put(key, id);
            assertNull("Zwei verschiedene Identitäten teilen sich einen Schlüssel:\n   "
                    + Arrays.toString(prev) + "\n   " + Arrays.toString(id) + "\n   → " + key, prev);
        }
    }

    @Test
    public void canonicalScientificIsIdempotent() {
        for (String[] id : IDENTITIES) {
            for (String[] v : variants(id[0], id[1], id[2])) {
                String once = CultivarNormalizer.canonicalScientific(v[0], v[1]);
                String twice = CultivarNormalizer.canonicalScientific(once, v[1]);
                assertEquals("canon nicht idempotent für [" + v[0] + " | " + v[1] + "]", once, twice);
            }
        }
    }
}
