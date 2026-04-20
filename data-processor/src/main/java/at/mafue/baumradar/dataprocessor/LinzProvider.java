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

public class LinzProvider implements CityProvider {

    private static final String CSV_URL = "https://data.linz.gv.at/katalog/umwelt/baumkataster/Baumkataster.csv";
    private static final int BATCH_SIZE = 5000;

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
        // Approximate box for Linz
        return new double[]{48.24, 14.24, 48.36, 14.36};
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
        
        // We might need to handle ISO-8859-1 or standard Windows encoding if UTF-8 fails for Austrian endpoints
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                throw new RuntimeException("Empty CSV file!");
            }
            
            // Handle trailing delimiters and basic splits
            String[] headers = headerLine.split(";");
            int idIdx = -1, latIdx = -1, lonIdx = -1, gattungIdx = -1, artIdx = -1, nameDeIdx = -1;
            
            for (int i = 0; i < headers.length; i++) {
                String h = headers[i].trim().replaceAll("\"", "");
                if (h.equalsIgnoreCase("BaumNr") || h.equalsIgnoreCase("id")) idIdx = i;
                if (h.equalsIgnoreCase("lat")) latIdx = i;
                if (h.equalsIgnoreCase("lon")) lonIdx = i;
                if (h.equalsIgnoreCase("Gattung")) gattungIdx = i;
                if (h.equalsIgnoreCase("Art")) artIdx = i;
                if (h.equalsIgnoreCase("NameDeutsch")) nameDeIdx = i;
            }

            // Fallback schema if header parsing fails
            if (idIdx == -1 || nameDeIdx == -1) {
                System.out.println("Warning: Headers not found dynamically, using static index fallback for Linz");
                idIdx = 13;
                nameDeIdx = 4;
                latIdx = 12;
                lonIdx = 11;
                gattungIdx = 1;
                artIdx = 2;
            }

            List<TreeRecord> batch = new ArrayList<>();
            String line;

            while ((line = reader.readLine()) != null) {
                totalLines++;
                String[] cols = line.split(";");
                
                if (cols.length <= Math.max(idIdx, nameDeIdx)) continue;
                
                String rawId = cols[idIdx].trim().replaceAll("\"", "");
                String id = getCityId() + "_" + totalLines + "_" + rawId;
                
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
                    
                    // Wir versuchen die echten lateinischen Namen zu nutzen, falls vorhanden.
                    String genusEn = gattungLat.isEmpty() ? Translator.translateGenus(nameDe) : gattungLat;
                    String speciesEn = artLat.isEmpty() ? "" : artLat;

                    batch.add(new TreeRecord(id, getCityId(), lat, lon, nameDe, genusEn, "", speciesEn));
                    
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
