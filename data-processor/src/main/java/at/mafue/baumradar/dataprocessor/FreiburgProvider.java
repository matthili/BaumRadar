package at.mafue.baumradar.dataprocessor;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

public class FreiburgProvider extends AbstractGeoJsonProvider {

    @Override
    public String getCityId() {
        return "freiburg";
    }

    @Override
    public String getName() {
        return "Freiburg im Breisgau";
    }

    @Override
    public String getCountry() {
        return "Deutschland";
    }

    @Override
    public double[] getBoundingBox() {
        return new double[]{47.95, 7.78, 48.06, 7.93};
    }

    @Override
    protected String getGeoJsonUrl(int offset) {
        return "https://geoportal.freiburg.de/wfs/gut_pit/gut_pit?service=wfs&version=2.0.0&SRSNAME=EPSG:4326&request=getfeature&typename=baum&outputformat=GEOJSON&startIndex=" + offset + "&count=" + BATCH_SIZE;
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
        
        String idStr = props.path("baum_nr").asText("");
        if (idStr.isEmpty()) idStr = UUID.randomUUID().toString();
        String id = getCityId() + "_" + idStr;
        
        String art_botani = props.path("baumart_botanisch").asText("");
        String artDe = props.path("baumart_deutsch").asText("");
        
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
