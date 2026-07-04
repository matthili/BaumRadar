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
    public String canonicalGerman(String identityKey, String canonicalScientificEn) {
        String base = german.get(CultivarNormalizer.speciesKeyOf(identityKey));
        return base == null ? null : CultivarNormalizer.appendCultivar(base, canonicalScientificEn);
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
