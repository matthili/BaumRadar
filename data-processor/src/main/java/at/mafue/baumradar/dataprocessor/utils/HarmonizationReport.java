package at.mafue.baumradar.dataprocessor.utils;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Schicht 3 der Harmonisierung: der Import-Report — das „moderne Perl-Skript".
 *
 * <p>Sammelt während des Pipeline-Laufs (thread-safe, über alle Städte parallel),
 * welche Roh-Schreibweisen auf denselben {@link CultivarNormalizer#identityKey}
 * fallen, und schreibt am Ende eine menschenlesbare Arbeitsliste. Diese Liste ist
 * die Grundlage für die (noch kleine, kuratierte) Alias-Tabelle der Schicht 2.
 *
 * <p><b>Nichts wird blockiert.</b> Der Report meldet nur — Cluster mit mehreren
 * Schreibweisen, reparierte Mojibake, verworfenen Junk und Truncation-Verdacht.
 * Der Import läuft unabhängig davon durch.
 */
public final class HarmonizationReport {

    private static final HarmonizationReport SHARED = new HarmonizationReport();

    /** Gemeinsame Instanz für den Pipeline-Lauf (der Exporter füttert sie). */
    public static HarmonizationReport shared() {
        return SHARED;
    }

    /** Für Tests: frische, isolierte Instanz. */
    public HarmonizationReport() {}

    private static final Pattern CULTIVAR_IN_CANON = Pattern.compile("'([^']+)'");
    private static final Pattern ROMAN = Pattern.compile("(?i)^[ivxlcdm]+$");

    /** identityKey → (Roh-Tupel → Häufigkeit). */
    private final ConcurrentMap<String, ConcurrentMap<String, LongAdder>> clusters = new ConcurrentHashMap<>();
    /** identityKey → kanonische Anzeigeform (Repräsentant). */
    private final ConcurrentMap<String, String> canonByKey = new ConcurrentHashMap<>();
    private final Set<String> mojibake = ConcurrentHashMap.newKeySet();
    private final Set<String> junk = ConcurrentHashMap.newKeySet();
    private final LongAdder totalRecords = new LongAdder();

    /** Ein (bereits normalisierter) Datensatz wird erfasst. Roh-Werte für die Arbeitsliste. */
    public void record(String rawDe, String rawEn, String canonEn, String identityKey) {
        totalRecords.increment();
        String rd = rawDe == null ? "" : rawDe;
        String re = rawEn == null ? "" : rawEn;

        if (re.indexOf('Ã') >= 0 || re.indexOf('Â') >= 0 || rd.indexOf('Ã') >= 0 || rd.indexOf('Â') >= 0) {
            mojibake.add(trunc(re) + "  ⇢  " + trunc(rd));
        }
        if ((canonEn == null || canonEn.isEmpty()) && !re.trim().isEmpty()) {
            junk.add(trunc(re));
        }
        if (identityKey == null || identityKey.isEmpty()) return;

        String rawTuple = trunc(re) + "  ⇢  " + trunc(rd);
        clusters.computeIfAbsent(identityKey, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(rawTuple, t -> new LongAdder()).increment();
        if (canonEn != null && !canonEn.isEmpty()) {
            canonByKey.putIfAbsent(identityKey, canonEn);
        }
    }

    public void writeTo(File file) throws IOException {
        Files.createDirectories(file.getAbsoluteFile().getParentFile().toPath());
        try (Writer w = new BufferedWriter(new OutputStreamWriter(
                Files.newOutputStream(file.toPath()), StandardCharsets.UTF_8))) {
            write(w);
        }
    }

    /** Getrennt vom File-Handling, damit der Report-Inhalt rein in-memory testbar ist. */
    void write(Writer w) throws IOException {
        long mergeClusters = clusters.values().stream().filter(m -> m.size() > 1).count();

        w.write("BaumRadar — Harmonisierungs-Report (Schicht 3)\n");
        w.write("================================================\n\n");
        w.write("Datensätze gesamt:            " + totalRecords.sum() + "\n");
        w.write("Distinkte Identitäten:        " + clusters.size() + "\n");
        w.write("Cluster mit >1 Schreibweise:  " + mergeClusters + "  (Arbeitsliste unten)\n");
        w.write("Mojibake erkannt (distinkt):  " + mojibake.size() + "\n");
        w.write("Junk/leer verworfen (distinkt): " + junk.size() + "\n");
        long aliasCovered = clusters.keySet().stream().filter(k -> SpeciesAliasTable.get().covers(k)).count();
        w.write("Von Schicht-2-Alias abgedeckt: " + aliasCovered + " / " + clusters.size() + " Identitäten\n\n");

        // --- A) Cluster mit mehreren Roh-Schreibweisen (nach Baumzahl absteigend) ---
        w.write("── A) Zusammengeführte Schreibweisen (Top 200 nach Häufigkeit) ──\n");
        w.write("   Kandidaten für die Alias-Tabelle (Schicht 2), falls eine Variante daneben liegt.\n\n");
        List<Map.Entry<String, ConcurrentMap<String, LongAdder>>> merges = new ArrayList<>();
        for (var e : clusters.entrySet()) {
            if (e.getValue().size() > 1) merges.add(e);
        }
        merges.sort(Comparator.comparingLong((Map.Entry<String, ConcurrentMap<String, LongAdder>> e) ->
                e.getValue().values().stream().mapToLong(LongAdder::sum).sum()).reversed());
        int shown = 0;
        for (var e : merges) {
            if (shown++ >= 200) {
                w.write("   … (" + (merges.size() - 200) + " weitere Cluster)\n");
                break;
            }
            String canon = canonByKey.getOrDefault(e.getKey(), "(nur Gattung)");
            long total = e.getValue().values().stream().mapToLong(LongAdder::sum).sum();
            w.write("▶ " + canon + "   [" + total + " Bäume, " + e.getValue().size() + " Schreibweisen]\n");
            e.getValue().entrySet().stream()
                    .sorted(Comparator.comparingLong((Map.Entry<String, LongAdder> v) -> v.getValue().sum()).reversed())
                    .forEach(v -> {
                        try {
                            w.write("      " + v.getValue().sum() + "×  " + v.getKey() + "\n");
                        } catch (IOException ignored) {
                        }
                    });
            w.write("\n");
        }

        // --- B) Truncation-Verdacht: Kultivar endet auf einen 1–2-Buchstaben-Fetzen ---
        List<String> truncated = new ArrayList<>();
        for (String canon : canonByKey.values()) {
            Matcher m = CULTIVAR_IN_CANON.matcher(canon);
            if (m.find()) {
                String[] words = m.group(1).split("\\s+");
                String last = words[words.length - 1];
                if (last.length() <= 2 && last.chars().allMatch(Character::isLetter)
                        && !ROMAN.matcher(last).matches()) {
                    truncated.add(canon);
                }
            }
        }
        truncated.sort(Comparator.naturalOrder());
        w.write("── B) Truncation-Verdacht (Kultivar endet auf 1–2-Buchstaben-Fetzen) ──\n");
        writeList(w, truncated, 60);

        // --- C) Mojibake ---
        List<String> moji = new ArrayList<>(mojibake);
        moji.sort(Comparator.naturalOrder());
        w.write("\n── C) Mojibake erkannt (Encoding-Bug im Upstream) ──\n");
        writeList(w, moji, 80);

        // --- D) Junk ---
        List<String> junkList = new ArrayList<>(junk);
        junkList.sort(Comparator.naturalOrder());
        w.write("\n── D) Als Junk/leer verworfen ──\n");
        writeList(w, junkList, 60);
    }

    private static void writeList(Writer w, List<String> items, int cap) throws IOException {
        if (items.isEmpty()) {
            w.write("   (keine)\n");
            return;
        }
        int i = 0;
        for (String s : items) {
            if (i++ >= cap) {
                w.write("   … (" + (items.size() - cap) + " weitere)\n");
                break;
            }
            w.write("   " + s + "\n");
        }
    }

    private static String trunc(String s) {
        s = s == null ? "" : s.replace('\n', ' ').trim();
        return s.length() > 80 ? s.substring(0, 77) + "…" : s;
    }
}
