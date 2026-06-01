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

    private static final String WFS_BASE = "https://gdi.berlin.de/services/wfs/baumbestand?service=WFS&version=2.0.0&request=GetFeature&outputFormat=application/json";

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

        double lon = coords.get(0).asDouble();
        double lat = coords.get(1).asDouble();
        
        // Use default ID or generate
        String idStr = props.path("gml_id").asText("");
        if (idStr.isEmpty()) idStr = UUID.randomUUID().toString();
        String id = getCityId() + "_" + idStr;
        
        // Try multiple property names: the two layers use different field names
        String gattungDe = props.path("gattung").asText("");
        if (gattungDe.isEmpty() || gattungDe.equals("null")) gattungDe = props.path("baumart").asText("");

        String artDe = props.path("art_deutsch").asText("");
        if (artDe.isEmpty() || artDe.equals("null")) artDe = props.path("art").asText("");

        if (gattungDe.isEmpty() || gattungDe.equalsIgnoreCase("null") || gattungDe.equalsIgnoreCase("unbekannt")) {
            return null;
        }
        
        String gattungEn = Translator.translateGenus(gattungDe);
        String artEn = artDe.isEmpty() ? "" : Translator.translateSpecies(artDe);
        
        return new TreeRecord(id, getCityId(), lat, lon, gattungDe, gattungEn, artDe, artEn);
    }
}

