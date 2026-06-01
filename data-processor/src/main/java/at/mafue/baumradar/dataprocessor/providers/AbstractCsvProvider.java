package at.mafue.baumradar.dataprocessor.providers;

import at.mafue.baumradar.dataprocessor.providers.*;
import at.mafue.baumradar.dataprocessor.providers.austria.*;
import at.mafue.baumradar.dataprocessor.providers.germany.*;
import at.mafue.baumradar.dataprocessor.providers.switzerland.*;
import at.mafue.baumradar.dataprocessor.models.*;
import at.mafue.baumradar.dataprocessor.utils.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Template Method base class for city providers whose open-data portal
 * delivers tree data as a CSV file over HTTP.
 *
 * <p>Subclasses only need to implement:
 * <ul>
 *   <li>{@link #getCsvUrl()} — the download URL</li>
 *   <li>{@link #getSplitRegex()} — the column delimiter regex</li>
 *   <li>{@link #mapRowToTree(String[], long)} — row-to-{@link TreeRecord} mapping</li>
 * </ul>
 * Optionally, {@link #processHeaders(String[])} can be overridden to
 * dynamically discover column indices from the header row.
 *
 * <p>The base class handles HTTP download, line-by-line streaming, batch
 * insertion into the database, and geofence cluster computation.
 */
public abstract class AbstractCsvProvider implements CityProvider {

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    /** Number of tree records accumulated before flushing a batch to the database. */
    protected static final int BATCH_SIZE = 5000;

    /** Returns the HTTP URL from which the CSV data is downloaded. */
    protected abstract String getCsvUrl();

    /** Returns the regex used to split each CSV line into columns (e.g. {@code ","} or {@code ";"}). */
    protected abstract String getSplitRegex();
    
    /**
     * Optional hook to parse header indices.
     */
    protected void processHeaders(String[] headers) {
        // Default: no-op
    }

    /**
     * Map a single CSV row to a TreeRecord. Return null to skip the row.
     */
    protected abstract TreeRecord mapRowToTree(String[] row, long lineNumber);

    /**
     * Downloads the CSV file as a stream, parses it line by line, maps each row
     * to a {@link TreeRecord}, and batch-inserts valid records into the database.
     * After all rows are processed, geofence clusters are computed and exported.
     */
    @Override
    public void processData(DatabaseExporter exporter) throws Exception {
        logger.info("Downloading & Parsing CSV Stream for {} from {}", getName(), getCsvUrl());
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(getCsvUrl())).build();
        
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
            
            
            // Parse the first line as headers to allow dynamic column discovery
            processHeaders(headerLine.split(getSplitRegex()));
            
            List<TreeRecord> batch = new ArrayList<>();
            // Collects spatial data for all parsed trees to produce geofence clusters
            GeofenceClusterer clusterer = new GeofenceClusterer();

            String line;
            while ((line = reader.readLine()) != null) {
                totalLines++;
                String[] cols = line.split(getSplitRegex());
                
                TreeRecord record = mapRowToTree(cols, totalLines);
                if (record != null) {
                    batch.add(record);
                    clusterer.addTree(record.latitude, record.longitude, record.genusDe);

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
            
            // Build merged geofence clusters and export
            List<GeofenceRecord> geofences = clusterer.buildGeofences(getCityId());
            int geofenceInserted = 0;
            List<GeofenceRecord> geofenceBatch = new ArrayList<>();
            for (GeofenceRecord gf : geofences) {
                geofenceBatch.add(gf);
                if (geofenceBatch.size() >= BATCH_SIZE) {
                    exporter.insertGeofences(geofenceBatch);
                    geofenceInserted += geofenceBatch.size();
                    geofenceBatch.clear();
                }
            }
            if (!geofenceBatch.isEmpty()) {
                exporter.insertGeofences(geofenceBatch);
                geofenceInserted += geofenceBatch.size();
            }
            
            logger.info("Processed {} lines, safely exported {} valid trees.", totalLines, inserted);
            logger.info("Computed and exported {} spatial geofence clusters.", geofenceInserted);
        }
    }
}

