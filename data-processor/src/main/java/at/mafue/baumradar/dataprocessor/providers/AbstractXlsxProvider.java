package at.mafue.baumradar.dataprocessor.providers;

import at.mafue.baumradar.dataprocessor.providers.*;
import at.mafue.baumradar.dataprocessor.providers.austria.*;
import at.mafue.baumradar.dataprocessor.providers.germany.*;
import at.mafue.baumradar.dataprocessor.providers.switzerland.*;
import at.mafue.baumradar.dataprocessor.models.*;
import at.mafue.baumradar.dataprocessor.utils.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Template Method base class for city providers whose open-data portal
 * delivers tree data as an XLSX spreadsheet over HTTP.
 *
 * <p>Subclasses implement:
 * <ul>
 *   <li>{@link #getXlsxUrl()} — the download URL</li>
 *   <li>{@link #processHeaders(String[])} — discover column indices from row 0</li>
 *   <li>{@link #mapRowToTree(String[], long)} — row-to-{@link TreeRecord} mapping</li>
 * </ul>
 * The base class downloads the workbook, decodes the first worksheet via the
 * dependency-free {@link XlsxReader}, batch-inserts valid records, and computes
 * geofence clusters — mirroring {@link AbstractCsvProvider}.
 */
public abstract class AbstractXlsxProvider implements CityProvider {

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    /** Number of tree records accumulated before flushing a batch to the database. */
    protected static final int BATCH_SIZE = 5000;

    /** Returns the HTTP URL from which the XLSX workbook is downloaded. */
    protected abstract String getXlsxUrl();

    /** Optional hook to discover column indices from the header row. */
    protected void processHeaders(String[] headers) {
        // Default: no-op
    }

    /** Maps a single spreadsheet row to a {@link TreeRecord}; return null to skip. */
    protected abstract TreeRecord mapRowToTree(String[] row, long lineNumber);

    @Override
    public void processData(DatabaseExporter exporter) throws Exception {
        logger.info("Downloading & Parsing XLSX for {} from {}", getName(), getXlsxUrl());
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(getXlsxUrl())).build();

        HttpResponse<java.io.InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to download data for " + getName() + ". HTTP Status: " + response.statusCode());
        }

        List<String[]> rows;
        try (java.io.InputStream is = response.body()) {
            rows = XlsxReader.read(is);
        }
        if (rows.isEmpty()) {
            throw new RuntimeException("Empty XLSX workbook for " + getName());
        }
        processHeaders(rows.get(0));

        int inserted = 0;
        List<TreeRecord> batch = new ArrayList<>();
        // Collects spatial data for all parsed trees to produce geofence clusters
        GeofenceClusterer clusterer = new GeofenceClusterer();

        for (int i = 1; i < rows.size(); i++) {
            TreeRecord record = mapRowToTree(rows.get(i), i);
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

        // Guard against a transient download/parse failure silently producing an
        // empty database (which would otherwise be compressed, signed, and published,
        // overwriting good data). Abort instead so Main keeps the previous file.
        if (inserted == 0) {
            throw new RuntimeException("No trees parsed for " + getName()
                + " — aborting so an empty database is not published.");
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

        logger.info("Processed {} XLSX rows, safely exported {} valid trees.", rows.size() - 1, inserted);
        logger.info("Computed and exported {} spatial geofence clusters.", geofenceInserted);
    }
}
