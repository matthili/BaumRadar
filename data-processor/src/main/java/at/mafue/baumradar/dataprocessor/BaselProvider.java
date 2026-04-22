package at.mafue.baumradar.dataprocessor;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

public class BaselProvider extends AbstractGeoJsonProvider {

    @Override
    public String getCityId() {
        return "basel";
    }

    @Override
    public String getName() {
        return "Basel";
    }

    @Override
    public String getCountry() {
        return "Schweiz";
    }

    @Override
    public double[] getBoundingBox() {
        return new double[]{47.53, 7.57, 47.60, 7.68};
    }

    @Override
    protected String getGeoJsonUrl(int offset) {
        return "https://data.bs.ch/api/v2/catalog/datasets/100052/exports/geojson?limit=" + BATCH_SIZE + "&offset=" + offset;
    }

    @Override
    protected TreeRecord mapFeatureToTree(JsonNode feature) {
        JsonNode props = feature.path("properties");
        JsonNode geom = feature.path("geometry");
        
        if (props.isMissingNode() || geom.isMissingNode()) return null;
        if (!"Point".equals(geom.path("type").asText())) return null;
        
        JsonNode coords = geom.path("coordinates");
        if (coords.size() < 2) return null;

        double lon = coords.get(0).asDouble();
        double lat = coords.get(1).asDouble();
        
        String idStr = props.path("gml_id").asText("");
        if (idStr.isEmpty()) idStr = UUID.randomUUID().toString();
        String id = getCityId() + "_" + idStr;
        
        String baumart_lateinisch = props.path("baumart_lateinisch").asText("");
        String artDe = props.path("baumart_deutsch").asText("");
        
        String genusDe = "";
        String artEn = "";
        String genusEn = "";
        
        if (!baumart_lateinisch.isEmpty()) {
            String[] parts = baumart_lateinisch.split(" ");
            if (parts.length > 0) genusDe = parts[0];
            genusEn = Translator.translateGenus(genusDe);
        } else {
            return null; // Ohne Gattung kein Geofence
        }

        return new TreeRecord(id, getCityId(), lat, lon, genusDe, genusEn, artDe, artEn);
    }
}
