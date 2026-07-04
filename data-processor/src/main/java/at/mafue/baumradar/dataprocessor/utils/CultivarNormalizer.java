package at.mafue.baumradar.dataprocessor.utils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Schicht 1 der Artnamen-Harmonisierung: deterministische, listenlose Normalisierung.
 *
 * <p>Die Baumkataster der 19 Städte schreiben Sorten (Kultivare) wild unterschiedlich —
 * {@code 'Columnare'}, {@code -Columnare-}, {@code "Columnare"}, {@code 'columnare'},
 * nackt, teils mit Mojibake ({@code SÃ¤ulenahorn}) oder abgeschnitten. Diese Klasse
 * vereinheitlicht rein per Regeln, <b>ohne</b> Substitutionsliste.
 *
 * <p><b>Zwei bewusst getrennte Ausgaben:</b>
 * <ul>
 *   <li>{@link #canonicalScientific} — eine <em>ordnungserhaltende</em> Anzeige­form
 *       (repariert Mojibake, vereinheitlicht Zitierstil/Casing, zieht das Kultivar aus
 *       dem deutschen Feld nach, wenn das lateinische keins hat). Das ist der große,
 *       sichere Hebel (Quotes/Casing/Umlaute).</li>
 *   <li>{@link #identityKey} — ein <em>ordnungs­unabhängiger</em> Schlüssel (Token-Menge,
 *       römisch→arabisch). Nur zum Clustern/Deduplizieren im Report; er verschmilzt
 *       Wortstellungs- und II/2-Varianten, hält aber verschiedene Token-Mengen
 *       (z. B. {@code Ley I} vs. {@code Ley II}) strikt getrennt.</li>
 * </ul>
 *
 * <p><b>Konservativ by design:</b> Es werden nur beweisbar gleiche Formen verschmolzen.
 * Unterschiedliche Sorten bleiben unterschiedlich — echte Information wird nie zerstört.
 */
public final class CultivarNormalizer {

    private CultivarNormalizer() {}

    /** Platzhalter statt echter Artangabe (werden verworfen, nicht als Art gezählt). */
    private static final Set<String> PLACEHOLDERS = Set.of(
            "species", "sp", "sp.", "spec", "spec.", "spp", "spp.", "cf", "cf.", "x", "×");

    /** Junk-Marker: der ganze Eintrag ist keine Artangabe. */
    private static final Pattern JUNK = Pattern.compile(
            "(?i)\\b(unbekannt|nicht\\s+erfasst|unknown|keine\\s+angabe|sonstige|diverse)\\b");

    /** Zitier-Zeichen aller Stilrichtungen, die ein Kultivar umschließen. */
    private static final String QUOTE_CHARS = "'‘’‚‛`´\"“”„«»";
    private static final Pattern QUOTED = Pattern.compile("[" + QUOTE_CHARS + "]([^" + QUOTE_CHARS + "]+)[" + QUOTE_CHARS + "]");
    /** Mit Spaces umgebene Bindestrich-Paare: "Acer platanoides -Columnare-". */
    private static final Pattern DASH_WRAPPED = Pattern.compile("(?:^|\\s)-\\s*([^-]+?)\\s*-(?=\\s|$)");
    /** Marken-/Klammer-Rauschen: "(R)", "(TM)", "®", Klammerzusätze. */
    private static final Pattern TRADEMARK = Pattern.compile("\\s*\\([^)]*\\)|[®™©]");

    private static final Pattern ROMAN = Pattern.compile("(?i)^[ivxlcdm]+$");

    // ---------------------------------------------------------------------
    // Öffentliche API
    // ---------------------------------------------------------------------

    /**
     * Repariert doppelt kodiertes UTF-8 ({@code SÃ¤ulenahorn} → {@code Säulenahorn}).
     * Konservativ: greift nur bei Mojibake-Signatur und nur, wenn das Re-Dekodieren
     * gültiges UTF-8 ergibt — sonst bleibt der Originalstring unangetastet.
     */
    public static String repairMojibake(String s) {
        if (s == null || s.isEmpty()) return s;
        if (s.indexOf('Ã') < 0 && s.indexOf('Â') < 0) return s; // kein Ã/Â → nichts zu tun
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 0xFF) return s; // enthält echtes Nicht-Latin1 → nicht anfassen
        }
        try {
            String decoded = new String(s.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
            return decoded.indexOf('�') >= 0 ? s : decoded;
        } catch (Exception e) {
            return s;
        }
    }

    /** Deutscher Anzeigename: Mojibake repariert, Whitespace normalisiert. Keine Vereinheitlichung. */
    public static String cleanGerman(String speciesDe) {
        if (speciesDe == null) return null;
        String s = repairMojibake(speciesDe).replaceAll("\\s+", " ").trim();
        return s;
    }

    /**
     * Ordnungserhaltende, kanonische wissenschaftliche Anzeige­form (der neue {@code species_en}).
     * Beispiele:
     * <pre>
     *   ("Acer platanoides -Columnare-", …)          → "Acer platanoides 'Columnare'"
     *   ("Acer platanoides 'columnare'", …)          → "Acer platanoides 'Columnare'"
     *   ("Acer platanoides", "Spitzahorn 'Columnare'")→ "Acer platanoides 'Columnare'"  (Cross-Field)
     *   ("Ulmus 'New Horizon'", …)                    → "Ulmus 'New Horizon'"
     *   ("unbekannt, nicht erfasst", …)               → ""
     * </pre>
     */
    public static String canonicalScientific(String speciesEn, String speciesDe) {
        String en = repairMojibake(nullToEmpty(speciesEn)).trim();
        if (en.isEmpty()) return "";
        en = TRADEMARK.matcher(en).replaceAll(" ").replaceAll("\\s+", " ").trim();
        if (en.isEmpty()) return "";
        // Ganzer Eintrag ist Junk (kein Latein-Gattungsanfang) → verwerfen. Ein "unbekannt"
        // INNERHALB eines echten Namens (z. B. als Sortenname) bleibt dagegen unangetastet.
        if (JUNK.matcher(en).find() && !en.matches("^[A-ZÄÖÜ][a-z].*")) return "";

        String cleanedOriginal = en; // Rückfall für Nicht-Wissenschaftliches (z. B. Trivialnamen)

        // 1) Kultivar aus dem lateinischen Feld ziehen (Quote oder -...-).
        String cultivarRaw = null;
        boolean quotedFound = false;
        Matcher q = QUOTED.matcher(en);
        if (q.find()) {
            cultivarRaw = q.group(1);
            en = (en.substring(0, q.start()) + " " + en.substring(q.end())).replaceAll("\\s+", " ").trim();
            quotedFound = true;
        } else {
            Matcher d = DASH_WRAPPED.matcher(en);
            if (d.find()) {
                cultivarRaw = d.group(1);
                en = (en.substring(0, d.start()) + " " + en.substring(d.end())).replaceAll("\\s+", " ").trim();
                quotedFound = true;
            }
        }

        // 2) Binomen zerlegen: Gattung [× ] [Art].
        List<String> tokens = en.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(en.split("\\s+")));
        if (tokens.isEmpty()) {
            // Nur ein Kultivar ohne Gattung — nicht rekonstruierbar.
            return cultivarRaw == null ? "" : cleanedOriginal;
        }
        String genus = titleWord(tokens.remove(0));
        if (genus.isEmpty()) return cleanedOriginal;
        boolean hybrid = false, epithetConsumed = false, placeholderConsumed = false;
        if (!tokens.isEmpty() && isHybridMarker(tokens.get(0))) { hybrid = true; tokens.remove(0); }
        String species = "";
        if (!tokens.isEmpty()) {
            String t = tokens.get(0);
            if (isPlaceholder(t)) { tokens.remove(0); placeholderConsumed = true; }
            else if (isEpithet(t)) { species = epithetCore(t); tokens.remove(0); epithetConsumed = true; }
            // Groß geschriebenes Epitheton ("Acer Pseudoplatanus 'Leopoldii'") NUR im klar
            // wissenschaftlichen Kontext (ein quotiertes Kultivar beweist ihn) als Art werten —
            // sonst würde ein Trivialname wie "Norway Maple" fälschlich zerlegt. Die Art zu
            // verlieren wäre schlimmer: es drohte Fusion mit anderen Arten derselben Sorte.
            else if (quotedFound && isCapitalizedEpithet(t)) { species = epithetCore(t); tokens.remove(0); epithetConsumed = true; }
        }

        // Wissenschaftlichkeits-Wächter: nur dann als Binomen behandeln, wenn es sich
        // strukturell beweisen lässt. Sonst ist es vermutlich ein Trivialname
        // ("Norway Maple") und darf NICHT zu "Norway 'Maple'" verstümmelt werden.
        boolean scientific = quotedFound || hybrid || epithetConsumed || placeholderConsumed;

        // 3) Nachgestellte Tokens = unquotiertes Kultivar (z. B. "Columnare Ley Typ 2").
        if (cultivarRaw == null && !tokens.isEmpty()) {
            if (scientific) cultivarRaw = String.join(" ", tokens);
            else return cleanedOriginal;
        }

        // 4) Cross-Field: kein Kultivar im Latein → aus dem deutschen Feld nachziehen.
        if (cultivarRaw == null) {
            String de = repairMojibake(nullToEmpty(speciesDe));
            Matcher dq = QUOTED.matcher(de);
            if (dq.find()) cultivarRaw = dq.group(1);
        }

        StringBuilder out = new StringBuilder(genus);
        if (hybrid) out.append(" ×");
        if (!species.isEmpty()) out.append(' ').append(species);
        String cultivar = titleCultivar(cultivarRaw);
        if (!cultivar.isEmpty()) out.append(" '").append(cultivar).append('\'');
        return out.toString();
    }

    /**
     * Ordnungs­unabhängiger Identitäts-Schlüssel (nur fürs Clustern/Report, NICHT für die Anzeige).
     * Token-Menge des Kultivars, römisch→arabisch, alphabetisch sortiert — verschmilzt
     * Wortstellung und II/2, hält aber verschiedene Token-Mengen getrennt.
     */
    public static String identityKey(String genusDe, String speciesEn, String speciesDe) {
        String canon = canonicalScientific(speciesEn, speciesDe);
        if (canon.isEmpty()) {
            String g = genusDe == null ? "" : genusDe.trim().toLowerCase();
            return g + "||";
        }
        // canon = "Genus [×] species 'Cultivar'"
        String cultivar = "";
        Matcher m = QUOTED.matcher(canon);
        String base = canon;
        if (m.find()) {
            cultivar = m.group(1);
            base = canon.substring(0, m.start()).trim();
        }
        String[] bp = base.toLowerCase().replace("×", " ").replaceAll("\\s+", " ").trim().split(" ");
        String genus = bp.length > 0 ? bp[0] : "";
        String species = bp.length > 1 ? bp[bp.length - 1] : "";
        List<String> ct = new ArrayList<>();
        for (String w : cultivar.toLowerCase().split("\\s+")) {
            if (w.isEmpty()) continue;
            ct.add(romanToArabic(w));
        }
        ct.sort(String::compareTo);
        return genus + "|" + species + "|" + String.join(" ", ct);
    }

    /**
     * Art-Schlüssel ({@code gattung|art}) aus einem {@link #identityKey} — ohne die
     * Kultivar-Komponente. Grundlage des Schicht-2-Lookups: EIN Alias-Eintrag je Art
     * deckt alle ihre Sorten ab. Beispiele: {@code "acer|platanoides|columnare" →
     * "acer|platanoides"}, {@code "tilia||" → "tilia"}.
     */
    public static String speciesKeyOf(String identityKey) {
        if (identityKey == null) return "";
        int last = identityKey.lastIndexOf('|');
        if (last < 0) return identityKey;
        String prefix = identityKey.substring(0, last);
        return prefix.endsWith("|") ? prefix.substring(0, prefix.length() - 1) : prefix;
    }

    /**
     * Hängt das Kultivar aus einem kanonischen Latein-Namen an einen Basisnamen an
     * ({@code "Ahorn" + "Acer palmatum 'Bicolor'" → "Ahorn 'Bicolor'"}). Ohne Kultivar
     * bleibt der Basisname unverändert. Gemeinsam genutzt von Schicht 2 und dem
     * Gattungs-Fallback (damit ein deutscher Name nie leer bleibt).
     */
    public static String appendCultivar(String base, String canonicalScientificEn) {
        if (canonicalScientificEn != null) {
            Matcher m = QUOTED.matcher(canonicalScientificEn);
            if (m.find()) return base + " '" + m.group(1) + "'";
        }
        return base;
    }

    // ---------------------------------------------------------------------
    // Helfer
    // ---------------------------------------------------------------------

    private static String titleCultivar(String raw) {
        if (raw == null) return "";
        String s = raw.replaceAll("[" + QUOTE_CHARS + "-]", " ").replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) return "";
        String[] words = s.split(" ");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            if (ROMAN.matcher(w).matches()) sb.append(w.toUpperCase());
            else if (w.chars().allMatch(Character::isDigit)) sb.append(w);
            else sb.append(Character.toUpperCase(w.charAt(0))).append(w.length() > 1 ? w.substring(1).toLowerCase() : "");
        }
        return sb.toString();
    }

    private static String titleWord(String w) {
        w = w.replaceAll("^[^\\p{L}×]+|[^\\p{L}.]+$", "");
        if (w.isEmpty()) return "";
        return Character.toUpperCase(w.charAt(0)) + (w.length() > 1 ? w.substring(1).toLowerCase() : "");
    }

    private static boolean isHybridMarker(String t) {
        return t.equals("×") || t.equalsIgnoreCase("x");
    }

    private static boolean isPlaceholder(String t) {
        return PLACEHOLDERS.contains(t.toLowerCase());
    }

    /**
     * Ein Art-Epitheton ist ein kleingeschriebenes Latein-Wort, optional abgekürzt
     * mit einem Schluss-Punkt (z. B. {@code "sacchar."} für {@code saccharinum}).
     * Die Abkürzung wird als Art beibehalten (nicht verworfen) — sonst fiele die Art
     * aus dem Identitäts-Schlüssel. Das Auflösen der Abkürzung ist Sache von Schicht 2.
     */
    private static boolean isEpithet(String t) {
        if (t.isEmpty() || isPlaceholder(t)) return false;
        // WICHTIG: Groß-/Kleinschreibung am ORIGINAL prüfen (epithetCore lowercased),
        // sonst gilt jedes Wort als kleingeschrieben und würde fälschlich als Art gelten.
        if (!Character.isLowerCase(t.charAt(0))) return false;
        String core = t.endsWith(".") ? t.substring(0, t.length() - 1) : t;
        return core.length() >= 2 && core.chars().allMatch(Character::isLetter);
    }

    /** Kern eines (evtl. abgekürzten) Epithetons: Schluss-Punkt weg, kleingeschrieben. */
    private static String epithetCore(String t) {
        String core = t.endsWith(".") ? t.substring(0, t.length() - 1) : t;
        return core.toLowerCase();
    }

    /** Groß geschriebenes, sonst latein-artiges Wort — nur im quotierten Kontext als Art akzeptiert. */
    private static boolean isCapitalizedEpithet(String t) {
        if (t.isEmpty() || isPlaceholder(t) || !Character.isUpperCase(t.charAt(0))) return false;
        String core = t.endsWith(".") ? t.substring(0, t.length() - 1) : t;
        return core.length() >= 3 && core.chars().allMatch(Character::isLetter);
    }

    private static String romanToArabic(String w) {
        if (!ROMAN.matcher(w).matches()) return w;
        int result = 0, prev = 0;
        for (int i = w.length() - 1; i >= 0; i--) {
            int v = switch (Character.toLowerCase(w.charAt(i))) {
                case 'i' -> 1; case 'v' -> 5; case 'x' -> 10;
                case 'l' -> 50; case 'c' -> 100; case 'd' -> 500; case 'm' -> 1000;
                default -> 0;
            };
            if (v < prev) result -= v; else { result += v; prev = v; }
        }
        return result > 0 ? Integer.toString(result) : w;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
