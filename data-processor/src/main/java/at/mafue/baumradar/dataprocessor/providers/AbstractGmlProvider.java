package at.mafue.baumradar.dataprocessor.providers;

import at.mafue.baumradar.dataprocessor.models.*;
import at.mafue.baumradar.dataprocessor.utils.*;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Template Method base class for city providers whose WFS only delivers usable
 * <strong>GML</strong> — the sibling of {@link AbstractGeoJsonProvider} for
 * sources where the GeoJSON writer fails (e.g. a server that cannot serialize
 * multi-geometries) or is not offered at all.
 *
 * <p>Parsing is streaming (StAX, JDK built-in — no extra dependency) and
 * namespace-agnostic: only local element names are considered, so
 * {@code gis:BAUMART}, {@code ms:baumart} and friends all work. Per feature the
 * child elements become a {@code name → text} map, and the <em>first</em>
 * {@code gml:pos} found supplies the coordinates — which is exactly the right
 * behaviour for multi-geometries: one tree, one position.
 *
 * <p>Like its GeoJSON sibling each page is parsed fully into memory before any
 * row is written, so a flaky page can be retried cleanly instead of silently
 * truncating the import.
 */
public abstract class AbstractGmlProvider implements CityProvider {

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    /** Number of tree records written to the database per insert. */
    protected static final int BATCH_SIZE = 5000;

    /** Attempts per page before giving up (absorbs transient/flaky failures). */
    protected static final int MAX_PAGE_ATTEMPTS = 6;

    private String sourceUrlOverride;

    @Override
    public void setSourceUrlOverride(String url) {
        this.sourceUrlOverride = url;
    }

    /** The paginated GML download URL; {@code offset} is the record to start at. */
    protected abstract String getGmlUrl(int offset);

    /**
     * Maps one parsed feature to a {@link TreeRecord}, or {@code null} to skip it.
     *
     * @param fields element name (without namespace prefix) → trimmed text
     * @param x      first coordinate value as written in the GML
     * @param y      second coordinate value as written in the GML
     */
    protected abstract TreeRecord mapFeature(Map<String, String> fields, double x, double y);

    /** Whether the service supports offset pagination. If false, we fetch once. */
    protected boolean supportsPagination() {
        return true;
    }

    private String resolveUrl(int offset) {
        if (sourceUrlOverride != null && !sourceUrlOverride.isBlank()) {
            return sourceUrlOverride.replace("{offset}", String.valueOf(offset));
        }
        return getGmlUrl(offset);
    }

    @Override
    public void processData(DatabaseExporter exporter) throws Exception {
        logger.info("Downloading & Parsing GML Stream for {}", getName());
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
        GeofenceClusterer clusterer = new GeofenceClusterer();

        int offset = 0;
        int inserted = 0;
        boolean hasMoreData = true;

        while (hasMoreData) {
            Page page = null;
            Exception lastError = null;

            for (int attempt = 1; attempt <= MAX_PAGE_ATTEMPTS && page == null; attempt++) {
                try {
                    page = fetchPage(client, resolveUrl(offset));
                } catch (Exception e) {
                    lastError = e;
                    logger.warn("[{}] page at offset {} failed (attempt {}/{}): {}",
                        getName(), offset, attempt, MAX_PAGE_ATTEMPTS, e.getMessage());
                    if (attempt < MAX_PAGE_ATTEMPTS) {
                        try {
                            Thread.sleep(2000L * attempt);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            }

            if (page == null) {
                throw new RuntimeException("[" + getName() + "] aborting at offset " + offset
                    + " after " + MAX_PAGE_ATTEMPTS + " failed attempts (avoiding partial data).", lastError);
            }

            for (int i = 0; i < page.records.size(); i += BATCH_SIZE) {
                List<TreeRecord> chunk = page.records.subList(i, Math.min(i + BATCH_SIZE, page.records.size()));
                exporter.insertBatch(chunk);
                for (TreeRecord r : chunk) clusterer.addTree(r.latitude, r.longitude, r.genusDe);
                inserted += chunk.size();
            }

            if (page.featuresParsed == 0) {
                hasMoreData = false;
            } else {
                offset += page.featuresParsed;
                logger.info("[{}] fetched up to offset {} ({} valid trees so far)...", getName(), offset, inserted);
                if (!supportsPagination()) {
                    hasMoreData = false;
                }
            }
        }

        if (inserted == 0) {
            throw new RuntimeException("No trees parsed for " + getName()
                + " — aborting so an empty database is not published.");
        }

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

        logger.info("Processed GML, loaded {} valid trees.", inserted);
        logger.info("Computed and exported {} spatial geofence clusters.", geofenceInserted);
    }

    private Page fetchPage(HttpClient client, String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new RuntimeException("HTTP " + response.statusCode());
        }
        try (InputStream is = response.body()) {
            return parse(is);
        }
    }

    /** Streams one GML response into fully-parsed records (visible for testing). */
    Page parse(InputStream is) throws Exception {
        XMLStreamReader reader = createReader(is);
        Page page = new Page();
        Map<String, String> fields = new HashMap<>();
        StringBuilder text = new StringBuilder();
        boolean inFeature = false;
        boolean inBoundedBy = false;
        boolean posCaptured = false;
        double x = 0;
        double y = 0;

        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String name = reader.getLocalName();
                    if (isMember(name)) {
                        inFeature = true;
                        fields.clear();
                        posCaptured = false;
                    } else if ("boundedBy".equals(name)) {
                        // A feature's own bounding box repeats its coordinates —
                        // reading them here would shadow the real geometry.
                        inBoundedBy = true;
                    }
                    text.setLength(0);
                } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                    text.append(reader.getText());
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String name = reader.getLocalName();
                    if (isMember(name)) {
                        if (inFeature) {
                            page.featuresParsed++;
                            if (posCaptured) {
                                TreeRecord record = mapFeature(fields, x, y);
                                if (record != null) page.records.add(record);
                            }
                        }
                        inFeature = false;
                    } else if ("boundedBy".equals(name)) {
                        inBoundedBy = false;
                    } else if (inFeature && !inBoundedBy && isPosition(name)) {
                        // First position wins — a multi-geometry describes one tree.
                        if (!posCaptured) {
                            // GML 3: <gml:pos>x y</gml:pos>
                            // GML 2: <gml:coordinates>x,y</gml:coordinates>
                            String[] parts = text.toString().trim().split("[,\\s]+");
                            if (parts.length >= 2) {
                                try {
                                    x = Double.parseDouble(parts[0]);
                                    y = Double.parseDouble(parts[1]);
                                    posCaptured = true;
                                } catch (NumberFormatException ignored) {
                                    // Malformed position — the feature is skipped below.
                                }
                            }
                        }
                    } else if (inFeature) {
                        String value = text.toString().trim();
                        if (!value.isEmpty()) fields.put(name, value);
                    }
                    text.setLength(0);
                }
            }
        } finally {
            reader.close();
        }
        return page;
    }

    /** GML 3.2 wraps features in {@code wfs:member}; older profiles use {@code gml:featureMember}. */
    private static boolean isMember(String localName) {
        return "member".equals(localName) || "featureMember".equals(localName);
    }

    /** GML 3 writes {@code gml:pos}, the older GML 2 profile {@code gml:coordinates}. */
    private static boolean isPosition(String localName) {
        return "pos".equals(localName) || "coordinates".equals(localName);
    }

    private static XMLStreamReader createReader(InputStream is) throws Exception {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        // No DTDs, no external entities — the parser only ever sees remote data.
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        return factory.createXMLStreamReader(is);
    }

    /** A fully-parsed page held in memory before being committed to the database. */
    static final class Page {
        final List<TreeRecord> records = new ArrayList<>();
        int featuresParsed = 0;
    }
}
