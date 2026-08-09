package at.mafue.baumradar.dataprocessor.utils;

/**
 * Small text helpers shared by the city providers.
 *
 * <p>Open-data portals are inconsistent about "no value": some omit the field,
 * some send an empty string, some the four letters {@code null}, and many pad
 * their columns with spaces. Every provider used to carry its own copy of the
 * same three lines; this is that copy, once.
 */
public final class Text {

    private Text() {
    }

    /**
     * Trims a raw field value and maps the literal string {@code "null"} to empty
     * — so callers only ever have to test for {@code isEmpty()}.
     *
     * @param s raw value, may be {@code null}
     * @return the trimmed value, or {@code ""} for null/blank/"null"
     */
    public static String clean(String s) {
        if (s == null) return "";
        String t = s.trim();
        return t.equalsIgnoreCase("null") ? "" : t;
    }
}
