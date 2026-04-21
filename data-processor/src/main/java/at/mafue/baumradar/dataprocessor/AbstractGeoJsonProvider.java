package at.mafue.baumradar.dataprocessor;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractGeoJsonProvider implements CityProvider {

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    protected static final int BATCH_SIZE = 5000;

    /**
     * Get the URL for the GeoJSON endpoint. 
     * @param offset for pagination loops (ArcGIS limits etc.)
     */
    protected abstract String getGeoJsonUrl(int offset);

    /**
     * Map a single GeoJSON Feature node to a TreeRecord.
     */
    protected abstract TreeRecord mapFeatureToTree(JsonNode feature);

    @Override
    public void processData(DatabaseExporter exporter) throws Exception {
        logger.info("Downloading & Parsing GeoJSON Stream for {}", getName());
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
        
        int offset = 0;
        int inserted = 0;
        boolean hasMoreData = true;

        ObjectMapper mapper = new ObjectMapper();
        JsonFactory factory = mapper.getFactory();
        
        Map<String, GeofenceCluster> clusters = new HashMap<>();

        while (hasMoreData) {
            String url = getGeoJsonUrl(offset);
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).build();
            
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                if (offset == 0) {
                    throw new RuntimeException("Failed to download data for " + getName() + ". HTTP Status: " + response.statusCode());
                } else {
                    logger.warn("Received HTTP {} at offset {}. Assuming pagination reached end of data.", response.statusCode(), offset);
                    break;
                }
            }
            
            int featuresParsedInThisBatch = 0;
            List<TreeRecord> batch = new ArrayList<>();
            
            try (JsonParser parser = factory.createParser(response.body())) {
                boolean featuresFound = false;
                
                // Advanced parsing: stream until we find "features" array
                while (!parser.isClosed()) {
                    JsonToken token = parser.nextToken();
                    if (token == null) break;
                    
                    if (JsonToken.FIELD_NAME.equals(token) && "features".equals(parser.getCurrentName())) {
                        token = parser.nextToken();
                        if (token == JsonToken.START_ARRAY) {
                            featuresFound = true;
                            // Parse each feature directly as an in-memory tree, saving us from loading the *entire* array.
                            while (parser.nextToken() == JsonToken.START_OBJECT) {
                                JsonNode featureNode = mapper.readTree(parser);
                                featuresParsedInThisBatch++;
                                
                                TreeRecord record = mapFeatureToTree(featureNode);
                                if (record != null) {
                                    batch.add(record);
                                    
                                    // Spatial Clustering
                                    String gridKey = String.format(Locale.US, "%.3f|%.3f", record.latitude, record.longitude);
                                    String clusterKey = record.genusDe + "|" + gridKey;
                                    
                                    GeofenceCluster c = clusters.computeIfAbsent(clusterKey, k -> new GeofenceCluster(record.genusDe));
                                    c.count++;
                                    c.sumLat += record.latitude;
                                    c.sumLon += record.longitude;

                                    if (batch.size() >= BATCH_SIZE) {
                                        exporter.insertBatch(batch);
                                        inserted += batch.size();
                                        batch.clear();
                                    }
                                }
                            }
                        }
                    }
                }
                
                if (!batch.isEmpty()) {
                    exporter.insertBatch(batch);
                    inserted += batch.size();
                }

                if (!featuresFound || featuresParsedInThisBatch == 0) {
                    // No more features returned
                    hasMoreData = false;
                } else {
                    offset += featuresParsedInThisBatch;
                    logger.info("Successfully fetched and inserted up to offset {}...", offset);
                }
            } catch (Exception e) {
                logger.error("Error parsing JSON at offset {}: {}", offset, e.getMessage(), e);
                break;
            }
        }
        
        // Export clustered Geofences
        List<GeofenceRecord> geofenceBatch = new ArrayList<>();
        int geofenceInserted = 0;
        for (GeofenceCluster c : clusters.values()) {
            if (c.count >= 2) {
                double centerLat = c.sumLat / c.count;
                double centerLon = c.sumLon / c.count;
                geofenceBatch.add(new GeofenceRecord(
                    UUID.randomUUID().toString(),
                    getCityId(),
                    centerLat,
                    centerLon,
                    100, // 100m radius
                    c.count,
                    c.genusDe
                ));
                
                if (geofenceBatch.size() >= BATCH_SIZE) {
                    exporter.insertGeofences(geofenceBatch);
                    geofenceInserted += geofenceBatch.size();
                    geofenceBatch.clear();
                }
            }
        }
        if (!geofenceBatch.isEmpty()) {
            exporter.insertGeofences(geofenceBatch);
            geofenceInserted += geofenceBatch.size();
        }
        
        logger.info("Processed GeoJSON, loaded {} valid trees.", inserted);
        logger.info("Computed and exported {} spatial geofence clusters.", geofenceInserted);
    }
    
    private static class GeofenceCluster {
        int count = 0;
        double sumLat = 0.0;
        double sumLon = 0.0;
        String genusDe;
        
        GeofenceCluster(String genusDe) {
            this.genusDe = genusDe;
        }
    }
}
