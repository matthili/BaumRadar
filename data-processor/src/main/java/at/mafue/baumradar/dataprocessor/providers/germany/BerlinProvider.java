package at.mafue.baumradar.dataprocessor.providers.germany;

import at.mafue.baumradar.dataprocessor.providers.*;
import at.mafue.baumradar.dataprocessor.providers.austria.*;
import at.mafue.baumradar.dataprocessor.providers.germany.*;
import at.mafue.baumradar.dataprocessor.providers.switzerland.*;
import at.mafue.baumradar.dataprocessor.models.*;
import at.mafue.baumradar.dataprocessor.utils.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/**
 * City provider for <strong>Berlin, Germany</strong>.
 *
 * <p>Berlin's tree inventory is published as a WFS (Web Feature Service) with
 * two separate layers: {@code strassenbaeume} (street trees) and
 * {@code anlagenbaeume} (park/facility trees).  This provider iterates
 * over both layers sequentially, delegating each to an anonymous
 * {@link AbstractGeoJsonProvider} instance that handles WFS pagination
 * via {@code startIndex}/{@code count} parameters.
 *
 * <p>Because the two layers use slightly different property names for
 * genus and species, field extraction includes fallback lookups
 * (e.g. trying {@code gattung} first, then {@code baumart}).
 */
public class BerlinProvider implements CityProvider {

    // srsName=EPSG:4326 is REQUIRED: without it the WFS returns native EPSG:25833
    // (UTM 33N) easting/northing, which were silently stored as lat/lon — placing
    // every Berlin tree at an invalid position. The WFS reprojects to WGS-84 for us.
    private static final String WFS_BASE = "https://gdi.berlin.de/services/wfs/baumbestand?service=WFS&version=2.0.0&request=GetFeature&outputFormat=application/json&srsName=EPSG:4326";

    @Override
    public String getCityId() {
        return "berlin";
    }

    @Override
    public String getName() {
        return "Berlin";
    }

    @Override
    public String getCountry() {
        return "Deutschland";
    }

    @Override
    public double[] getBoundingBox() {
        // Approximate box for Berlin
        return new double[]{52.34, 13.08, 52.68, 13.76};
    }
    
    /**
     * Processes both WFS layers by creating a temporary {@link AbstractGeoJsonProvider}
     * per layer that reuses this provider's metadata and feature-mapping logic.
     */
    @Override
    public void processData(DatabaseExporter exporter) throws Exception {
        // Berlin uses WFS which has two layers.  Each layer is processed by
        // an anonymous AbstractGeoJsonProvider that handles pagination.
        String[] layers = {"baumbestand:strassenbaeume", "baumbestand:anlagenbaeume"};
        
        for (String layer : layers) {
            AbstractGeoJsonProvider subProvider = new AbstractGeoJsonProvider() {
                @Override
                public String getCityId() { return BerlinProvider.this.getCityId(); }

                @Override
                public String getName() { return BerlinProvider.this.getName() + " (" + layer + ")"; }

                @Override
                public String getCountry() { return BerlinProvider.this.getCountry(); }

                @Override
                public double[] getBoundingBox() { return BerlinProvider.this.getBoundingBox(); }

                @Override
                protected String getGeoJsonUrl(int offset) {
                    // WFS pagination uses startIndex and count
                    return WFS_BASE + "&typeNames=" + layer + "&startIndex=" + offset + "&count=" + BATCH_SIZE;
                }

                @Override
                protected TreeRecord mapFeatureToTree(JsonNode feature) {
                    return BerlinProvider.this.mapFeatureToTree(feature, layer);
                }
            };
            
            subProvider.processData(exporter);
        }
    }

    /**
     * Maps a single GeoJSON Feature to a {@link TreeRecord}, applying fallback
     * field lookups for genus and species names that may differ between
     * the street-tree and park-tree WFS layers.
     */
    protected TreeRecord mapFeatureToTree(JsonNode feature, String layer) {
        JsonNode props = feature.path("properties");
        JsonNode geom = feature.path("geometry");

        if (props.isMissingNode() || geom.isMissingNode()) return null;
        if (!"Point".equals(geom.path("type").asText())) return null;

        JsonNode coords = geom.path("coordinates");
        if (coords.size() < 2) return null;

        // WGS-84 [lon, lat] — requires srsName=EPSG:4326 on the WFS request
        // (the native CRS is EPSG:25833 / UTM 33N).
        double lon = coords.get(0).asDouble();
        double lat = coords.get(1).asDouble();
        if (lat == 0 || lon == 0) return null;

        String idStr = clean(props.path("pitid").asText(""));
        if (idStr.isEmpty()) idStr = clean(props.path("gisid").asText(""));
        if (idStr.isEmpty()) idStr = UUID.randomUUID().toString();
        String id = getCityId() + "_" + idStr;

        // German genus directly (gattung_deutsch); fall back to the Latin genus.
        String genusDe = clean(props.path("gattung_deutsch").asText(""));
        if (genusDe.isEmpty()) genusDe = Translator.germanGenusFromLatin(clean(props.path("gattung").asText("")));
        if (genusDe.isEmpty() || genusDe.equalsIgnoreCase("unbekannt")) return null;
        String genusEn = Translator.translateGenus(genusDe);

        String speciesDe = clean(props.path("art_dtsch").asText(""));
        String speciesEn = clean(props.path("art_bot").asText(""));

        return new TreeRecord(id, getCityId(), lat, lon, genusDe, genusEn, speciesDe, speciesEn);
    }

    /** Trims a JSON string value and maps the literal {@code "null"} to empty. */
    private static String clean(String s) {
        if (s == null) return "";
        String t = s.trim();
        return t.equalsIgnoreCase("null") ? "" : t;
    }
}

