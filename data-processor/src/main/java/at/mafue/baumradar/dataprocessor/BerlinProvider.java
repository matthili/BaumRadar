package at.mafue.baumradar.dataprocessor;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

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
    
    @Override
    public void processData(DatabaseExporter exporter) throws Exception {
        // Berlin uses WFS which has two layers. We delegate to two dummy providers.
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
        
        // Extract fields
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
