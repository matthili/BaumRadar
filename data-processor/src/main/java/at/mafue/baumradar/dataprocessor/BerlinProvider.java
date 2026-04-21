package at.mafue.baumradar.dataprocessor;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

public class BerlinProvider extends AbstractGeoJsonProvider {

    // Open Data ArcGIS REST API endpoint for Berlin. 
    // Pagination is natively supported via resultOffset.
    private static final String BASE_URL = "https://services.arcgis.com/jUpNdisbWqRpMo35/arcgis/rest/services/Baumkataster_Berlin/FeatureServer/0/query?outFields=*&where=1%3D1&f=geojson";

    @Override
    public String getCityId() {
        return "berlin";
    }

    @Override
    public String getName() {
        return "Berlin";
    }

    @Override
    public double[] getBoundingBox() {
        // Approximate box for Berlin
        return new double[]{52.34, 13.08, 52.68, 13.76};
    }

    @Override
    protected String getGeoJsonUrl(int offset) {
        return BASE_URL + "&resultOffset=" + offset;
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
        
        String idStr = props.path("OBJECTID").asText("");
        if (idStr.isEmpty()) idStr = UUID.randomUUID().toString();
        String id = getCityId() + "_" + idStr;
        
        // Common fields for German ArcGIS tree cadasters
        String gattungDe = props.path("GATTUNG").asText("");
        if (gattungDe.isEmpty() || gattungDe.equals("null")) gattungDe = props.path("BAUMART").asText("");

        String artDe = props.path("ART_DEUTSCH").asText("");
        if (artDe.isEmpty() || artDe.equals("null")) artDe = props.path("ART").asText("");

        if (gattungDe.isEmpty() || gattungDe.equalsIgnoreCase("null")) return null;
        if (gattungDe.equalsIgnoreCase("unbekannt")) return null;
        
        String gattungEn = Translator.translateGenus(gattungDe);
        String artEn = artDe.isEmpty() ? "" : Translator.translateSpecies(artDe);
        
        return new TreeRecord(id, getCityId(), lat, lon, gattungDe, gattungEn, artDe, artEn);
    }
}
