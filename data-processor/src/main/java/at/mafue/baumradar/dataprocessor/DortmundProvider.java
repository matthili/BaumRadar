package at.mafue.baumradar.dataprocessor;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

public class DortmundProvider extends AbstractGeoJsonProvider {

    @Override
    public String getCityId() {
        return "dortmund";
    }

    @Override
    public String getName() {
        return "Dortmund";
    }

    @Override
    public String getCountry() {
        return "Deutschland";
    }

    @Override
    public double[] getBoundingBox() {
        return new double[]{51.42, 7.33, 51.58, 7.62};
    }

    @Override
    protected String getGeoJsonUrl(int offset) {
        return "https://open-data.dortmund.de/api/explore/v2.1/catalog/datasets/baumkataster/exports/geojson?lang=de&timezone=Europe%2FBerlin&limit=" + BATCH_SIZE + "&offset=" + offset;
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
        
        String idStr = props.path("id").asText("");
        if (idStr.isEmpty()) idStr = UUID.randomUUID().toString();
        String id = getCityId() + "_" + idStr;
        
        String art_botani = props.path("art_botani").asText("");
        String artDe = props.path("art_deutsc").asText("");
        
        String genusDe = "";
        
        if (!art_botani.isEmpty()) {
            String[] parts = art_botani.split(" ");
            if (parts.length > 0) genusDe = parts[0];
        } else {
            return null;
        }
        
        String genusEn = Translator.translateGenus(genusDe);
        String artEn = "";
        
        return new TreeRecord(id, getCityId(), lat, lon, genusDe, genusEn, artDe, artEn);
    }
}
