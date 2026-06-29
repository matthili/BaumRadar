package at.mafue.baumradar.dataprocessor.providers.austria;

import at.mafue.baumradar.dataprocessor.providers.*;
import at.mafue.baumradar.dataprocessor.providers.austria.*;
import at.mafue.baumradar.dataprocessor.providers.germany.*;
import at.mafue.baumradar.dataprocessor.providers.switzerland.*;
import at.mafue.baumradar.dataprocessor.models.*;
import at.mafue.baumradar.dataprocessor.utils.*;

/**
 * City provider for <strong>Innsbruck, Austria</strong>.
 *
 * <p>Innsbruck publishes its tree cadastre on data.gv.at as an XLSX workbook
 * (read via the dependency-free {@link AbstractXlsxProvider}/{@link XlsxReader}).
 * The sheet already carries WGS-84 {@code Lon}/{@code Lat} columns, so no
 * reprojection is needed. The German genus is derived from the botanical name
 * in {@code Gattung_Lat} (e.g. {@code "Tilia cordata"} → {@code "Linde"}), while
 * {@code Gattung_Dt} holds the German species name (e.g. {@code "Winterlinde"}).
 */
public class InnsbruckProvider extends AbstractXlsxProvider {

    private int gattungDtIdx = -1;
    private int gattungLatIdx = -1;
    private int lonIdx = -1;
    private int latIdx = -1;
    private int objIdx = -1;

    @Override
    public String getCityId() {
        return "innsbruck";
    }

    @Override
    public String getName() {
        return "Innsbruck";
    }

    @Override
    public String getCountry() {
        return "Österreich";
    }

    @Override
    public double[] getBoundingBox() {
        return new double[]{47.20, 11.30, 47.32, 11.46};
    }

    @Override
    protected String getXlsxUrl() {
        return "https://www.data.gv.at/api/hub/store/data/6967bdc631f15d18b4400db7";
    }

    @Override
    protected void processHeaders(String[] headers) {
        for (int i = 0; i < headers.length; i++) {
            String h = headers[i].trim();
            if (h.equalsIgnoreCase("Gattung_Dt")) gattungDtIdx = i;
            else if (h.equalsIgnoreCase("Gattung_Lat")) gattungLatIdx = i;
            else if (h.equalsIgnoreCase("Lon")) lonIdx = i;
            else if (h.equalsIgnoreCase("Lat")) latIdx = i;
            else if (h.equalsIgnoreCase("OBJECTID")) objIdx = i;
        }
    }

    @Override
    protected TreeRecord mapRowToTree(String[] cols, long lineNumber) {
        Double lon = coord(get(cols, lonIdx));
        Double lat = coord(get(cols, latIdx));
        if (lat == null || lon == null) return null;

        String latinName = get(cols, gattungLatIdx);
        String genusDe = Translator.germanGenusFromLatin(latinName);
        if (genusDe.isEmpty()) return null;
        String genusEn = Translator.translateGenus(genusDe);

        String speciesDe = get(cols, gattungDtIdx);
        String speciesEn = latinName;

        String oid = get(cols, objIdx);
        String id = getCityId() + "_" + (oid.isEmpty() ? String.valueOf(lineNumber) : oid);

        return new TreeRecord(id, getCityId(), lat, lon, genusDe, genusEn, speciesDe, speciesEn);
    }

    /** Safe column access: returns "" for out-of-range or null cells. */
    private static String get(String[] cols, int idx) {
        if (idx < 0 || idx >= cols.length || cols[idx] == null) return "";
        return cols[idx].trim();
    }

    /** Parses a coordinate, tolerating a German decimal comma; null if blank/invalid. */
    private static Double coord(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        try {
            return Double.parseDouble(s.trim().replace(",", "."));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
