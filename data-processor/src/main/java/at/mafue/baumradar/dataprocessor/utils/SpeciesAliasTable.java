package at.mafue.baumradar.dataprocessor.utils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Schicht 2 der Artnamen-Harmonisierung: die kuratierte Alias-Tabelle.
 *
 * <p>Löst, was deterministische Regeln (Schicht 1) nicht können — vor allem die
 * <b>Vereinheitlichung der deutschen Artnamen</b>. Ein Eintrag je Art
 * ({@code gattung|art → deutscher Artname}) deckt alle Sorten dieser Art ab; das
 * Kultivar wird mechanisch aus dem (bereits von Schicht 1 kanonisierten) Latein-Namen
 * übernommen und angehängt: {@code "Spitz-Ahorn 'Columnare'"}.
 *
 * <p>Die Tabelle liegt als {@code /species_aliases.tsv} im Klassenpfad und wächst aus
 * {@code harmonization_report.txt}. Fehlt sie, bleibt Schicht 2 schlicht wirkungslos —
 * Schicht 1 hat den Datenbestand dann trotzdem bereits deutlich bereinigt.
 */
public final class SpeciesAliasTable {

    private static final SpeciesAliasTable INSTANCE = load();

    /** Die aus {@code /species_aliases.tsv} geladene Tabelle für den Pipeline-Lauf. */
    public static SpeciesAliasTable get() {
        return INSTANCE;
    }

    /** {@code gattung|art} (bzw. {@code gattung}) → kanonischer deutscher Artname. */
    private final Map<String, String> german;

    /** Sichtbar für Tests: mit einer expliziten Tabelle konstruieren. */
    SpeciesAliasTable(Map<String, String> german) {
        this.german = german;
    }

    /**
     * Kanonischer deutscher Name für einen {@link CultivarNormalizer#identityKey}, oder
     * {@code null}, wenn die Art nicht in der Tabelle steht (dann greift der Aufrufer auf
     * den bereinigten Original-Namen zurück). Das Kultivar wird aus dem kanonischen
     * Latein-Namen übernommen.
     */
    /**
     * Kanonischer deutscher Name für einen {@link CultivarNormalizer#identityKey}, oder
     * {@code null}, wenn die Art nicht in der Tabelle steht.
     *
     * <p><b>Konservativ beim deutschen Namen:</b> überschrieben wird nur, wenn
     * <ol>
     *   <li>das Latein eine Sorte trägt (dann kanonischer Artname + Sorte), oder</li>
     *   <li>der deutsche Rohname ein zusammengemischter Doppelname ist
     *       ({@code "Sommer-Eiche, Stiel-Eiche"}), oder</li>
     *   <li>er nur eine Schreibvariante des Basisnamens ist ({@code "Spitzahorn"}).</li>
     * </ol>
     * Ein eigenständiger Trivialname, der eine Form meint, deren Sorte NICHT im
     * Latein-Feld steht ({@code "Kugel-Ahorn"} = 'Globosum', {@code "Blut-Ahorn"}),
     * bleibt <b>erhalten</b> — er trägt Information, die sonst verloren ginge.
     *
     * @param cleanedGermanRaw der bereits mojibake-bereinigte deutsche Rohname
     */
    public String canonicalGerman(String identityKey, String canonicalScientificEn, String cleanedGermanRaw) {
        String base = german.get(CultivarNormalizer.speciesKeyOf(identityKey));
        if (base == null) return null;
        String withCultivar = CultivarNormalizer.appendCultivar(base, canonicalScientificEn);
        if (!withCultivar.equals(base)) return withCultivar; // Latein-Sorte belegt → kanonisch
        if (cleanedGermanRaw == null || cleanedGermanRaw.isBlank()) return base;
        if (looksMerged(cleanedGermanRaw) || sameName(cleanedGermanRaw, base)) return base;
        return cleanedGermanRaw; // eigenständiger Trivialname → erhalten
    }

    /** Zusammengemischter Doppelname: Komma/Semikolon/Slash oder „ - " (mit Leerzeichen). */
    private static boolean looksMerged(String s) {
        return s.matches(".*[,;/].*") || s.matches(".*\\s[-–]\\s.*");
    }

    private static boolean sameName(String a, String b) {
        return normName(a).equals(normName(b));
    }

    /** Nur Buchstaben/Ziffern, kleingeschrieben — Bindestriche/Leerzeichen egal. */
    private static String normName(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-zäöüß0-9]", "");
    }

    public boolean covers(String identityKey) {
        return german.containsKey(CultivarNormalizer.speciesKeyOf(identityKey));
    }

    public int size() {
        return german.size();
    }

    private static SpeciesAliasTable load() {
        Map<String, String> m = new HashMap<>();
        try (InputStream in = SpeciesAliasTable.class.getResourceAsStream("/species_aliases.tsv")) {
            if (in != null) {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        String trimmed = line.strip();
                        if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                        int tab = line.indexOf('\t');
                        if (tab < 0) continue;
                        String key = line.substring(0, tab).strip().toLowerCase(Locale.ROOT);
                        String val = line.substring(tab + 1).strip();
                        if (!key.isEmpty() && !val.isEmpty()) m.put(key, val);
                    }
                }
            }
        } catch (IOException e) {
            // Tabelle ist optional; ohne sie ist Schicht 2 einfach wirkungslos.
        }
        return new SpeciesAliasTable(Map.copyOf(m));
    }
}
