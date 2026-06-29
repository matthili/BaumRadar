package at.mafue.baumradar.dataprocessor.providers.austria;

import at.mafue.baumradar.dataprocessor.providers.*;
import at.mafue.baumradar.dataprocessor.providers.austria.*;
import at.mafue.baumradar.dataprocessor.providers.germany.*;
import at.mafue.baumradar.dataprocessor.providers.switzerland.*;
import at.mafue.baumradar.dataprocessor.models.*;
import at.mafue.baumradar.dataprocessor.utils.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/**
 * City provider for <strong>Graz, Austria</strong>.
 *
 * <p>Graz publishes its full per-tree cadastre ("Baumkataster Holding") through
 * an ArcGIS REST MapServer. Features are fetched as WGS-84 GeoJSON
 * ({@code outSR=4326}) in pages of {@value #PAGE} (the server's
 * {@code maxRecordCount}), ordered by {@code OBJECTID} for stable pagination.
 * The botanical name ({@code BAUM_ART}, e.g. {@code "Quercus robur"}) yields the
 * German genus; {@code DEUTSCHER_NAME} holds the German species name.
 *
 * <p>Note: the city's OGD_WFS service only exposes 89 aggregate stands — this
 * Holding service (the one the city's own web map uses) is the actual
 * individual-tree dataset.
 */
public class GrazProvider extends AbstractGeoJsonProvider {

    /** Page size; matches the service's advertised {@code maxRecordCount}. */
    private static final int PAGE = 2000;

    @Override
    public String getCityId() {
        return "graz";
    }

    @Override
    public String getName() {
        return "Graz";
    }

    @Override
    public String getCountry() {
        return "Österreich";
    }

    @Override
    public double[] getBoundingBox() {
        return new double[]{47.00, 15.35, 47.13, 15.54};
    }

    @Override
    protected String getGeoJsonUrl(int offset) {
        // ArcGIS REST query: WGS-84 GeoJSON, ordered by OBJECTID for deterministic
        // paging via resultOffset/resultRecordCount.
        return "https://geodaten.graz.at/mapping/rest/services/2_5_Umwelt/Baumkataster_Holding/MapServer/0/query"
            + "?where=1%3D1&outFields=OBJECTID,BAUM_ART,DEUTSCHER_NAME&returnGeometry=true"
            + "&outSR=4326&orderByFields=OBJECTID&f=geojson"
            + "&resultOffset=" + offset + "&resultRecordCount=" + PAGE;
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
        if (lat == 0 || lon == 0) return null;

        // BAUM_ART is the botanical name (e.g. "Quercus robur") → German genus.
        String botanical = clean(props.path("BAUM_ART").asText(""));
        String genusDe = Translator.germanGenusFromLatin(botanical);
        if (genusDe.isEmpty()) return null;
        String genusEn = Translator.translateGenus(genusDe);

        String speciesDe = clean(props.path("DEUTSCHER_NAME").asText(""));
        String speciesEn = botanical;

        String oid = clean(props.path("OBJECTID").asText(""));
        String id = getCityId() + "_" + (oid.isEmpty() ? UUID.randomUUID().toString() : oid);

        return new TreeRecord(id, getCityId(), lat, lon, genusDe, genusEn, speciesDe, speciesEn);
    }

    /** Trims a JSON string value and maps the literal {@code "null"} to empty. */
    private static String clean(String s) {
        if (s == null) return "";
        String t = s.trim();
        return t.equalsIgnoreCase("null") ? "" : t;
    }
}
