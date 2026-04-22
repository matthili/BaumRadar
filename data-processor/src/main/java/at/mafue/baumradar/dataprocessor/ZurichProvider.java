package at.mafue.baumradar.dataprocessor;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

public class ZurichProvider extends AbstractGeoJsonProvider {

    @Override
    public String getCityId() {
        return "zurich";
    }

    @Override
    public String getName() {
        return "Zürich";
    }

    @Override
    public String getCountry() {
        return "Schweiz";
    }

    @Override
    public double[] getBoundingBox() {
        return new double[]{47.32, 8.44, 47.43, 8.62};
    }

    @Override
    protected String getGeoJsonUrl(int offset) {
        return "https://www.ogd.stadt-zuerich.ch/wfs/geoportal/Baumkataster?service=WFS&version=1.1.0&request=GetFeature&outputFormat=GeoJSON&typename=baumkataster_baumstandorte&startIndex=" + offset + "&maxFeatures=" + BATCH_SIZE;
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
        
        String idStr = props.path("baumnummer").asText("");
        if (idStr.isEmpty()) idStr = UUID.randomUUID().toString();
        String id = getCityId() + "_" + idStr;
        
        String genusDe = props.path("baumgattunglat").asText("");
        String artDe = props.path("baumnamedeu").asText("");
        
        if (genusDe.isEmpty() || genusDe.equalsIgnoreCase("null")) {
            return null;
        }
        
        String genusEn = Translator.translateGenus(genusDe);
        String artEn = "";
        
        return new TreeRecord(id, getCityId(), lat, lon, genusDe, genusEn, artDe, artEn);
    }
}
