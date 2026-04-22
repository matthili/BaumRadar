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

public class ViennaProvider extends AbstractCsvProvider {

    private static final String CSV_URL = "https://data.wien.gv.at/daten/geo?service=WFS&request=GetFeature&version=1.1.0&typeName=ogdwien:BAUMKATOGD&srsName=EPSG:4326&outputFormat=csv";

    private int idIdx = -1;
    private int shapeIdx = -1;
    private int latIdx = -1;
    private int lonIdx = -1;
    private int gattungIdx = -1;
    private int artIdx = -1;

    @Override
    public String getCityId() {
        return "wien";
    }

    @Override
    public String getName() {
        return "Wien";
    }

    @Override
    public String getCountry() {
        return "Österreich";
    }

    @Override
    public double[] getBoundingBox() {
        return new double[]{48.11, 16.16, 48.33, 16.58};
    }

    @Override
    protected String getCsvUrl() {
        return CSV_URL;
    }

    @Override
    protected String getSplitRegex() {
        return ",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)";
    }

    @Override
    protected void processHeaders(String[] headers) {
        for (int i = 0; i < headers.length; i++) {
            String h = headers[i].toUpperCase().replaceAll("\"", "");
            if (h.contains("BAUM_ID") || h.equals("ID") || h.equals("OBJECTID")) idIdx = (idIdx == -1) ? i : idIdx;
            if (h.equals("SHAPE")) shapeIdx = i;
            if (h.equals("LAT") || h.equals("Y")) latIdx = i;
            if (h.equals("LON") || h.equals("X")) lonIdx = i;
            if (h.contains("GATTUNG")) gattungIdx = i;
            if (h.contains("ART")) artIdx = i;
        }

        if (idIdx == -1 || gattungIdx == -1) {
            idIdx = 0;
            shapeIdx = 1;
            gattungIdx = 5;
            artIdx = 6;
        }
    }

    @Override
    protected TreeRecord mapRowToTree(String[] cols, long lineNumber) {
        if (cols.length <= Math.max(idIdx, gattungIdx)) return null;
        
        String id = cols[idIdx].replaceAll("\"", "");
        if (id.isEmpty()) id = UUID.randomUUID().toString();
        
        String gattungDe = cols[gattungIdx].replaceAll("\"", "");
        String artDe = (artIdx != -1 && cols.length > artIdx) ? cols[artIdx].replaceAll("\"", "") : "";
        
        double lat = 0;
        double lon = 0;
        
        if (shapeIdx != -1 && cols.length > shapeIdx) {
            String shape = cols[shapeIdx].replaceAll("\"", "");
            shape = shape.replace("POINT", "").replace("(", "").replace(")", "").trim();
            String[] coords = shape.split(" ");
            if (coords.length >= 2) {
                try {
                    lon = Double.parseDouble(coords[0].trim());
                    lat = Double.parseDouble(coords[1].trim());
                } catch (NumberFormatException e) {
                    // Ignore
                }
            }
        } else if (latIdx != -1 && lonIdx != -1 && cols.length > Math.max(latIdx, lonIdx)) {
            try {
                lat = Double.parseDouble(cols[latIdx].replaceAll("\"", ""));
                lon = Double.parseDouble(cols[lonIdx].replaceAll("\"", ""));
            } catch (NumberFormatException e) {
                // Ignore
            }
        }
        
        if (lat != 0 && lon != 0 && !gattungDe.isEmpty() && !gattungDe.equalsIgnoreCase("unbekannt")) {
            String gattungEn = Translator.translateGenus(gattungDe);
            String artEn = artDe.isEmpty() ? "" : Translator.translateSpecies(artDe);
            
            return new TreeRecord(id, getCityId(), lat, lon, gattungDe, gattungEn, artDe, artEn);
        }
        return null;
    }
}
