package at.mafue.baumradar.dataprocessor.providers;

import at.mafue.baumradar.dataprocessor.providers.*;
import at.mafue.baumradar.dataprocessor.providers.austria.*;
import at.mafue.baumradar.dataprocessor.providers.germany.*;
import at.mafue.baumradar.dataprocessor.providers.switzerland.*;
import at.mafue.baumradar.dataprocessor.models.*;
import at.mafue.baumradar.dataprocessor.utils.*;

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
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Template Method base class for city providers whose open-data portal
 * delivers tree data as GeoJSON over HTTP.
 *
 * <p>Subclasses implement:
 * <ul>
 *   <li>{@link #getGeoJsonUrl(int)} — the paginated download URL</li>
 *   <li>{@link #mapFeatureToTree(JsonNode)} — Feature-to-{@link TreeRecord} mapping</li>
 * </ul>
 * Optionally, {@link #isZipped()}, {@link #supportsPagination()} and
 * {@link #pageDelayMs()} can be overridden for portals that deliver ZIP-wrapped
 * responses, do not support offset-based pagination, or choke under rapid requests.
 *
 * <p>Each page is parsed in full <em>before</em> any row is written, so a page
 * that fails (e.g. a flaky server returning malformed JSON) can be retried
 * cleanly. A page that keeps failing aborts the whole import rather than
 * silently truncating to partial data.
 */
public abstract class AbstractGeoJsonProvider implements CityProvider {

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    /** Number of tree records written to the database per insert. */
    protected static final int BATCH_SIZE = 5000;

    /** Attempts per page before giving up (absorbs transient/flaky failures). */
    protected static final int MAX_PAGE_ATTEMPTS = 6;

    /**
     * Get the URL for the GeoJSON endpoint.
     * @param offset for pagination loops (ArcGIS limits etc.)
     */
    protected abstract String getGeoJsonUrl(int offset);

    /**
     * Map a single GeoJSON Feature node to a TreeRecord.
     */
    protected abstract TreeRecord mapFeatureToTree(JsonNode feature);

    /**
     * Whether the downloaded data is a ZIP file containing the GeoJSON.
     */
    protected boolean isZipped() {
        return false;
    }

    /**
     * Whether the API supports pagination. If false, we only fetch once.
     */
    protected boolean supportsPagination() {
        return true;
    }

    /**
     * Optional pause (ms) between successful pages, to avoid hammering a
     * rate-limited or flaky server. Default 0 (no delay).
     */
    protected long pageDelayMs() {
        return 0;
    }

    /**
     * Downloads and parses GeoJSON data in a pagination loop, mapping each
     * Feature to a {@link TreeRecord} and batch-inserting valid records.
     * After all pages are exhausted, geofence clusters are computed and exported.
     */
    @Override
    public void processData(DatabaseExporter exporter) throws Exception {
        logger.info("Downloading & Parsing GeoJSON Stream for {}", getName());
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();

        ObjectMapper mapper = new ObjectMapper();
        JsonFactory factory = mapper.getFactory();
        GeofenceClusterer clusterer = new GeofenceClusterer();

        int offset = 0;
        int inserted = 0;
        boolean hasMoreData = true;

        while (hasMoreData) {
            Page page = null;
            Exception lastError = null;

            for (int attempt = 1; attempt <= MAX_PAGE_ATTEMPTS && page == null; attempt++) {
                try {
                    page = fetchPage(client, factory, mapper, getGeoJsonUrl(offset));
                } catch (HttpStatusException he) {
                    lastError = he;
                    if (attempt < MAX_PAGE_ATTEMPTS) {
                        backoff(offset, attempt, he);
                    } else if (offset == 0) {
                        throw new RuntimeException("Failed to download data for " + getName()
                            + ". HTTP Status: " + he.status, he);
                    } else {
                        // A persistent non-200 only after the first page usually means we
                        // paged past the end (some servers signal this with an HTTP error).
                        logger.warn("[{}] persistent HTTP {} at offset {} — treating as end of data.",
                            getName(), he.status, offset);
                        page = Page.EMPTY;
                    }
                } catch (Exception e) {
                    lastError = e;
                    backoff(offset, attempt, e);
                }
            }

            if (page == null) {
                // A page failed to parse on every attempt. Never silently truncate —
                // abort so Main keeps the previous (complete) data.
                throw new RuntimeException("[" + getName() + "] aborting at offset " + offset
                    + " after " + MAX_PAGE_ATTEMPTS + " failed attempts (avoiding partial data).", lastError);
            }

            // The page parsed cleanly — only now do we touch the database/clusterer.
            for (int i = 0; i < page.records.size(); i += BATCH_SIZE) {
                List<TreeRecord> chunk = page.records.subList(i, Math.min(i + BATCH_SIZE, page.records.size()));
                exporter.insertBatch(chunk);
                for (TreeRecord r : chunk) clusterer.addTree(r.latitude, r.longitude, r.genusDe);
                inserted += chunk.size();
            }

            if (!page.featuresFound || page.featuresParsed == 0) {
                hasMoreData = false;   // empty page → end of data
            } else {
                offset += page.featuresParsed;
                logger.info("[{}] fetched up to offset {} ({} valid trees so far)...", getName(), offset, inserted);
                if (!supportsPagination()) {
                    hasMoreData = false;
                } else if (pageDelayMs() > 0) {
                    try { Thread.sleep(pageDelayMs()); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }
        }

        // Guard against a transient failure silently producing an empty database.
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

        logger.info("Processed GeoJSON, loaded {} valid trees.", inserted);
        logger.info("Computed and exported {} spatial geofence clusters.", geofenceInserted);
    }

    /**
     * Fetches and fully parses one page into memory (no DB writes yet), so the
     * caller can retry a failed page cleanly. Throws on any HTTP/parse error.
     */
    private Page fetchPage(HttpClient client, JsonFactory factory, ObjectMapper mapper, String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new HttpStatusException(response.statusCode());
        }

        Page page = new Page();
        java.io.File tempZip = null;
        java.util.zip.ZipFile zipFile = null;
        try {
            InputStream is = response.body();
            if (isZipped()) {
                tempZip = java.io.File.createTempFile(getName() + "_download", ".zip");
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tempZip)) {
                    is.transferTo(fos);
                }
                is.close();
                zipFile = new java.util.zip.ZipFile(tempZip);
                java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zipFile.entries();
                if (!entries.hasMoreElements()) {
                    throw new RuntimeException("ZIP file is empty for " + getName());
                }
                java.util.zip.ZipEntry entry = entries.nextElement();
                logger.info("Extracting {} from ZIP...", entry.getName());
                is = zipFile.getInputStream(entry);
            }

            try (JsonParser parser = factory.createParser(is)) {
                while (!parser.isClosed()) {
                    JsonToken token = parser.nextToken();
                    if (token == null) break;
                    if (JsonToken.FIELD_NAME.equals(token) && "features".equals(parser.getCurrentName())) {
                        token = parser.nextToken();
                        if (token == JsonToken.START_ARRAY) {
                            page.featuresFound = true;
                            while (parser.nextToken() == JsonToken.START_OBJECT) {
                                JsonNode featureNode = mapper.readTree(parser);
                                page.featuresParsed++;
                                TreeRecord record = mapFeatureToTree(featureNode);
                                if (record != null) {
                                    page.records.add(record);
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            if (zipFile != null) {
                try { zipFile.close(); } catch (Exception ignored) {}
            }
            if (tempZip != null && tempZip.exists()) {
                tempZip.delete();
            }
        }
        return page;
    }

    private void backoff(int offset, int attempt, Exception e) {
        logger.warn("[{}] page at offset {} failed (attempt {}/{}): {}",
            getName(), offset, attempt, MAX_PAGE_ATTEMPTS, e.getMessage());
        try {
            Thread.sleep(2000L * attempt);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /** A fully-parsed page held in memory before being committed to the database. */
    private static final class Page {
        static final Page EMPTY = new Page();
        final List<TreeRecord> records = new ArrayList<>();
        int featuresParsed = 0;
        boolean featuresFound = false;
    }

    /** Signals a non-200 HTTP response so the caller can distinguish it from a parse error. */
    private static final class HttpStatusException extends RuntimeException {
        final int status;
        HttpStatusException(int status) {
            super("HTTP " + status);
            this.status = status;
        }
    }
}
