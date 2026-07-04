package at.mafue.baumradar.dataprocessor.utils;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/**
 * Korpus-Regressionstest über eine eingecheckte Stichprobe der ECHTEN
 * (genusDe, speciesDe, speciesEn)-Tupel aller 19 Städte
 * ({@code src/test/resources/species_corpus.tsv}, ~7.900 Zeilen).
 *
 * <p>Das ist die Generalisierungs-Absicherung, die den beispielbasierten Tests
 * fehlte: Statt einzelner Ahorn-Fälle wird der Normalizer gegen die gesamte reale
 * Schreibweisen-Vielfalt geprüft — auf Robustheit, Idempotenz, korrekte
 * Art-Zuordnung und einen plausiblen Kollaps-Korridor (Kanarienvogel gegen
 * Regressionen, die entweder nicht mehr mergen oder über-mergen).
 */
public class CultivarNormalizerCorpusTest {

    private static final Set<String> PLACEHOLDERS = Set.of("species", "spec", "sp", "spp", "cf", "x");

    private List<String[]> loadCorpus() throws IOException {
        List<String[]> rows = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream("/species_corpus.tsv")) {
            assertNotNull("Korpus-Fixture species_corpus.tsv fehlt", in);
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isEmpty()) continue;
                    String[] p = line.split("\t", -1);
                    rows.add(new String[]{p[0], p.length > 1 ? p[1] : "", p.length > 2 ? p[2] : ""});
                }
            }
        }
        return rows;
    }

    @Test
    public void neverThrowsAndIsIdempotentOverRealCorpus() throws Exception {
        List<String[]> rows = loadCorpus();
        assertTrue("Fixture unerwartet klein: " + rows.size(), rows.size() > 5000);
        for (String[] row : rows) {
            String de = row[1], en = row[2];
            String once = CultivarNormalizer.canonicalScientific(en, de);
            String twice = CultivarNormalizer.canonicalScientific(once, de);
            assertEquals("canon nicht idempotent: [" + en + " | " + de + "]", once, twice);
            CultivarNormalizer.identityKey(row[0], en, de); // darf über keine reale Zeile werfen
        }
    }

    @Test
    public void keySpeciesAlwaysMatchesTheObviousLatinSpecies() throws Exception {
        // Unabhängige Extraktion des Art-Epithetons (Gattung + kleingeschriebenes Wort)
        // und Abgleich mit der Art-Komponente des Schlüssels. Divergenz = Parser-Bug,
        // der Arten falsch zuordnen (und damit fälschlich verschmelzen) könnte.
        int checked = 0;
        for (String[] row : loadCorpus()) {
            String[] toks = row[2].trim().split("\\s+");
            if (toks.length < 2 || !toks[0].matches("[A-ZÄÖÜ][a-z]+")) continue; // saubere Gattung
            // Nur EINDEUTIGE Binome prüfen: zweites Token muss ein kleingeschriebenes
            // Latein-Epitheton sein. Großgeschriebene Zweitwörter sind mehrdeutig
            // (Groß-Epitheton wie "Pseudoplatanus" ODER Sortenname wie "Jakob Lebel")
            // und werden hier bewusst ausgelassen — dafür gibt es die Invarianten-Tests.
            String t = toks[1];
            if (!Character.isLowerCase(t.charAt(0))) continue;
            String core = (t.endsWith(".") ? t.substring(0, t.length() - 1) : t).toLowerCase();
            if (core.length() < 2 || !core.chars().allMatch(Character::isLetter)) continue;
            if (PLACEHOLDERS.contains(core)) continue;
            String key = CultivarNormalizer.identityKey(row[0], row[2], row[1]);
            String keySpecies = key.split("\\|", -1)[1];
            assertEquals("Schlüssel-Art weicht ab bei speciesEn=[" + row[2] + "]", core, keySpecies);
            checked++;
        }
        assertTrue("zu wenige eindeutige Binome geprüft: " + checked, checked > 3000);
    }

    @Test
    public void collapseRatioStaysInSaneBand() throws Exception {
        List<String[]> rows = loadCorpus();
        Set<String> keys = new HashSet<>();
        for (String[] row : rows) keys.add(CultivarNormalizer.identityKey(row[0], row[2], row[1]));
        double ratio = 1.0 - (double) keys.size() / rows.size();
        // Gemessen ~0,55. Unter 0,45 → mergt nicht mehr; über 0,70 → über-mergt.
        assertTrue("Kollaps zu niedrig (" + ratio + ") — Merging kaputt?", ratio > 0.45);
        assertTrue("Kollaps zu hoch (" + ratio + ") — über-mergt?", ratio < 0.70);
    }
}
