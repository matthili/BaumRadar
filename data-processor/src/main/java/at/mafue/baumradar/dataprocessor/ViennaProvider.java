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

public class ViennaProvider implements CityProvider {

    private static final String CSV_URL = "https://data.wien.gv.at/daten/geo?service=WFS&request=GetFeature&version=1.1.0&typeName=ogdwien:BAUMKATOGD&srsName=EPSG:4326&outputFormat=csv";
    private static final int BATCH_SIZE = 5000;

    @Override
    public String getCityId() {
        return "wien";
    }

    @Override
    public String getName() {
        return "Wien";
    }

    @Override
    public double[] getBoundingBox() {
        // Approximate box for Vienna
        return new double[]{48.11, 16.16, 48.33, 16.58};
    }

    @Override
    public void processData(DatabaseExporter exporter) throws Exception {
        System.out.println("-> Downloading & Parsing CSV Stream for " + getName());
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(CSV_URL)).build();
        
        HttpResponse<java.io.InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to download data for " + getName() + ". HTTP Status: " + response.statusCode());
        }

        long totalLines = 0;
        int inserted = 0;
        
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                throw new RuntimeException("Empty CSV file!");
            }
            
            String[] headers = headerLine.split(",");
            int idIdx = -1, shapeIdx = -1, latIdx = -1, lonIdx = -1, gattungIdx = -1, artIdx = -1;
            
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

            List<TreeRecord> batch = new ArrayList<>();
            String line;
            String splitRegex = ",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)";

            while ((line = reader.readLine()) != null) {
                totalLines++;
                String[] cols = line.split(splitRegex);
                
                if (cols.length <= Math.max(idIdx, gattungIdx)) continue;
                
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
                    
                    batch.add(new TreeRecord(id, getCityId(), lat, lon, gattungDe, gattungEn, artDe, artEn));
                    
                    if (batch.size() >= BATCH_SIZE) {
                        exporter.insertBatch(batch);
                        inserted += batch.size();
                        batch.clear();
                    }
                }
            }
            
            if (!batch.isEmpty()) {
                exporter.insertBatch(batch);
                inserted += batch.size();
            }
            
            System.out.println("   Processed " + totalLines + " lines, loaded " + inserted + " valid trees.");
        }
    }
}
