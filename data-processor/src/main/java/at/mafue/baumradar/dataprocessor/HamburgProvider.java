package at.mafue.baumradar.dataprocessor;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

public class HamburgProvider extends AbstractGeoJsonProvider {

    @Override
    public String getCityId() {
        return "hamburg";
    }

    @Override
    public String getName() {
        return "Hamburg";
    }

    @Override
    public String getCountry() {
        return "Deutschland";
    }

    @Override
    public double[] getBoundingBox() {
        return new double[]{53.39, 9.73, 53.73, 10.32};
    }

    @Override
    protected boolean isZipped() {
        return true;
    }

    @Override
    protected boolean supportsPagination() {
        return false;
    }

    @Override
    protected String getGeoJsonUrl(int offset) {
        return "https://geodienste.hamburg.de/download?url=https://qs-geodienste.hamburg.de/HH_WFS_Strassenbaumkataster&f=json";
    }

    @Override
    protected TreeRecord mapFeatureToTree(JsonNode feature) {
        JsonNode props = feature.path("properties");
        JsonNode geom = feature.path("geometry");
        
        if (props.isMissingNode() || geom.isMissingNode()) return null;
        if (!"MultiPoint".equals(geom.path("type").asText()) && !"Point".equals(geom.path("type").asText())) return null;
        
        JsonNode coords = geom.path("coordinates");
        if (coords.size() < 1) return null;
        
        double easting, northing;
        if ("MultiPoint".equals(geom.path("type").asText())) {
            JsonNode firstPoint = coords.get(0);
            if (firstPoint.size() < 2) return null;
            easting = firstPoint.get(0).asDouble();
            northing = firstPoint.get(1).asDouble();
        } else {
            if (coords.size() < 2) return null;
            easting = coords.get(0).asDouble();
            northing = coords.get(1).asDouble();
        }
        
        double[] latlon = UtmConverter.utm32NToWgs84(easting, northing);
        double lat = latlon[0];
        double lon = latlon[1];
        
        String idStr = feature.path("id").asText("");
        if (idStr.isEmpty()) idStr = UUID.randomUUID().toString();
        String id = getCityId() + "_" + idStr;
        
        String genusDe = props.path("gattung_deutsch").asText("");
        if (genusDe.isEmpty() || genusDe.equalsIgnoreCase("null")) {
            genusDe = props.path("gattung_latein").asText("");
        }
        
        if (genusDe.isEmpty() || genusDe.equalsIgnoreCase("null")) {
            return null;
        }
        
        String artDe = props.path("art_deutsch").asText("");
        if (artDe.equalsIgnoreCase("null")) artDe = "";
        
        String artEn = "";
        String genusEn = Translator.translateGenus(genusDe);

        return new TreeRecord(id, getCityId(), lat, lon, genusDe, genusEn, artDe, artEn);
    }
}
