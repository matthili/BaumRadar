package at.mafue.baumradar.dataprocessor.providers.germany;

import at.mafue.baumradar.dataprocessor.providers.*;
import at.mafue.baumradar.dataprocessor.providers.austria.*;
import at.mafue.baumradar.dataprocessor.providers.germany.*;
import at.mafue.baumradar.dataprocessor.providers.switzerland.*;
import at.mafue.baumradar.dataprocessor.models.*;
import at.mafue.baumradar.dataprocessor.utils.*;

/**
 * City provider for <strong>Frankfurt am Main, Germany</strong>.
 *
 * <p>Frankfurt's portal exposes the tree cadastre as a semicolon-delimited CSV
 * download. Coordinates are given in ETRS89 / UTM zone 32N
 * (columns {@code "ETRS 89 Rechtswert"} = easting, {@code "ETRS 89 Hochwert"} =
 * northing, German decimal comma), reprojected via
 * {@link UtmConverter#utm32NToWgs84}. The {@code "Lateinischer Name"} column
 * combines the botanical and German names in one field, e.g.
 * {@code "Platanus acerifolia, Gewöhnliche Platane"} — split on the first comma
 * to derive genus and species.
 */
public class FrankfurtProvider extends AbstractCsvProvider {

    private int objIdx = -1;
    private int hochwertIdx = -1;   // ETRS89 northing (Y)
    private int rechtswertIdx = -1; // ETRS89 easting (X)
    private int nameIdx = -1;       // "<botanical>, <German common name>"

    @Override
    public String getCityId() {
        return "frankfurt";
    }

    @Override
    public String getName() {
        return "Frankfurt am Main";
    }

    @Override
    public String getCountry() {
        return "Deutschland";
    }

    @Override
    public double[] getBoundingBox() {
        return new double[]{50.01, 8.45, 50.24, 8.83};
    }

    @Override
    protected String getCsvUrl() {
        return "https://offenedaten.frankfurt.de/dcat/dataset/de-he-frankfurtam-baumkataster_op/content.csv";
    }

    /** Frankfurt's CSV is semicolon-delimited. */
    @Override
    protected String getSplitRegex() {
        return ";";
    }

    @Override
    protected void processHeaders(String[] headers) {
        for (int i = 0; i < headers.length; i++) {
            // Strip a possible UTF-8 BOM from the very first header.
            String h = headers[i].replace("﻿", "").trim();
            if (h.equalsIgnoreCase("Objelt ID") || h.equalsIgnoreCase("Objekt ID")) objIdx = i;
            else if (h.equalsIgnoreCase("ETRS 89 Hochwert")) hochwertIdx = i;
            else if (h.equalsIgnoreCase("ETRS 89 Rechtswert")) rechtswertIdx = i;
            else if (h.equalsIgnoreCase("Lateinischer Name")) nameIdx = i;
        }
    }

    @Override
    protected TreeRecord mapRowToTree(String[] cols, long lineNumber) {
        Double northing = coord(get(cols, hochwertIdx));   // Hochwert = Y
        Double easting = coord(get(cols, rechtswertIdx));  // Rechtswert = X
        if (northing == null || easting == null) return null;

        // ETRS89 / UTM zone 32N (EPSG:25832) → WGS-84
        double[] latlon = UtmConverter.utm32NToWgs84(easting, northing);
        double lat = latlon[0];
        double lon = latlon[1];
        // A few source rows carry a corrupt coordinate (an extra digit in the
        // Hochwert) that reprojects far outside the city — drop those outliers.
        if (lat < 49.9 || lat > 50.4 || lon < 8.3 || lon > 9.0) return null;

        // "Lateinischer Name" = "<botanical>, <German common name>"
        String full = get(cols, nameIdx);
        if (full.isEmpty()) return null;
        String[] parts = full.split(",", 2);
        String botanical = parts[0].trim();
        String germanName = parts.length > 1 ? parts[1].trim() : "";

        String genusDe = Translator.germanGenusFromLatin(botanical);
        if (genusDe.isEmpty()) return null;
        String genusEn = Translator.translateGenus(genusDe);

        String speciesDe = germanName;
        String speciesEn = botanical;

        String oid = get(cols, objIdx);
        String id = getCityId() + "_" + (oid.isEmpty() ? String.valueOf(lineNumber) : oid);

        return new TreeRecord(id, getCityId(), lat, lon, genusDe, genusEn, speciesDe, speciesEn);
    }

    /** Safe column access: returns "" for out-of-range or null cells. */
    private static String get(String[] cols, int idx) {
        if (idx < 0 || idx >= cols.length || cols[idx] == null) return "";
        return cols[idx].trim();
    }

    /** Parses a coordinate with German decimal comma; null if blank/invalid. */
    private static Double coord(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        try {
            return Double.parseDouble(s.trim().replace(",", "."));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
