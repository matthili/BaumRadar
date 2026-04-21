package at.mafue.baumradar.dataprocessor;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class LinzProvider extends AbstractCsvProvider {

    private static final String CSV_URL = "https://data.linz.gv.at/katalog/umwelt/baumkataster/Baumkataster.csv";

    private int idIdx = -1;
    private int latIdx = -1;
    private int lonIdx = -1;
    private int gattungIdx = -1;
    private int artIdx = -1;
    private int nameDeIdx = -1;

    @Override
    public String getCityId() {
        return "linz";
    }

    @Override
    public String getName() {
        return "Linz";
    }

    @Override
    public double[] getBoundingBox() {
        return new double[]{48.24, 14.24, 48.36, 14.36};
    }

    @Override
    protected String getCsvUrl() {
        return CSV_URL;
    }

    @Override
    protected String getSplitRegex() {
        return ";";
    }

    @Override
    protected void processHeaders(String[] headers) {
        for (int i = 0; i < headers.length; i++) {
            String h = headers[i].trim().replaceAll("\"", "");
            if (h.equalsIgnoreCase("BaumNr") || h.equalsIgnoreCase("id")) idIdx = i;
            if (h.equalsIgnoreCase("lat")) latIdx = i;
            if (h.equalsIgnoreCase("lon")) lonIdx = i;
            if (h.equalsIgnoreCase("Gattung")) gattungIdx = i;
            if (h.equalsIgnoreCase("Art")) artIdx = i;
            if (h.equalsIgnoreCase("NameDeutsch")) nameDeIdx = i;
        }

        if (idIdx == -1 || nameDeIdx == -1) {
            System.out.println("Warning: Headers not found dynamically, using static index fallback for Linz");
            idIdx = 13;
            nameDeIdx = 4;
            latIdx = 12;
            lonIdx = 11;
            gattungIdx = 1;
            artIdx = 2;
        }
    }

    @Override
    protected TreeRecord mapRowToTree(String[] cols, long lineNumber) {
        if (cols.length <= Math.max(idIdx, nameDeIdx)) return null;
        
        String rawId = cols[idIdx].trim().replaceAll("\"", "");
        String id = getCityId() + "_" + lineNumber + "_" + rawId;
        
        String nameDe = cols[nameDeIdx].trim().replaceAll("\"", "");
        String gattungLat = (gattungIdx != -1 && cols.length > gattungIdx) ? cols[gattungIdx].trim().replaceAll("\"", "") : "";
        String artLat = (artIdx != -1 && cols.length > artIdx) ? cols[artIdx].trim().replaceAll("\"", "") : "";
        
        double lat = 0;
        double lon = 0;
        
        if (latIdx != -1 && lonIdx != -1 && cols.length > Math.max(latIdx, lonIdx)) {
            try {
                lat = Double.parseDouble(cols[latIdx].trim().replaceAll("\"", "").replace(",", "."));
                lon = Double.parseDouble(cols[lonIdx].trim().replaceAll("\"", "").replace(",", "."));
            } catch (NumberFormatException e) {
                // Ignore invalid coords
            }
        }
        
        if (lat != 0 && lon != 0 && !nameDe.isEmpty()) {
            String genusEn = gattungLat.isEmpty() ? Translator.translateGenus(nameDe) : gattungLat;
            String speciesEn = artLat.isEmpty() ? "" : artLat;

            return new TreeRecord(id, getCityId(), lat, lon, nameDe, genusEn, "", speciesEn);
        }
        
        return null;
    }
}
