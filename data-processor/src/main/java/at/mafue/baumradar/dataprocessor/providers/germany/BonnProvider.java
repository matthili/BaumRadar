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
 * City provider for <strong>Bonn, Germany</strong>.
 *
 * <p>Bonn's city map publishes the tree cadastre as a single GeoJSON document
 * ({@code stadtplan.bonn.de/geojson?Thema=21367}) already in WGS-84, so the
 * whole dataset is fetched in one request (pagination disabled). String fields
 * are heavily right-padded with spaces in the source and therefore trimmed:
 * {@code lateinischer_name} (botanical → German genus) and {@code deutscher_name}
 * (German species name).
 */
public class BonnProvider extends AbstractGeoJsonProvider {

    @Override
    public String getCityId() {
        return "bonn";
    }

    @Override
    public String getName() {
        return "Bonn";
    }

    @Override
    public String getCountry() {
        return "Deutschland";
    }

    @Override
    public double[] getBoundingBox() {
        return new double[]{50.62, 7.02, 50.77, 7.21};
    }

    /** The endpoint returns the whole dataset in one response. */
    @Override
    protected boolean supportsPagination() {
        return false;
    }

    @Override
    protected String getGeoJsonUrl(int offset) {
        return "https://stadtplan.bonn.de/geojson?Thema=21367";
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

        String botanical = clean(props.path("lateinischer_name").asText(""));
        String genusDe = Translator.germanGenusFromLatin(botanical);
        if (genusDe.isEmpty()) return null;
        String genusEn = Translator.translateGenus(genusDe);

        String speciesDe = clean(props.path("deutscher_name").asText(""));
        String speciesEn = botanical;

        String baumId = clean(props.path("baum_id").asText(""));
        String id = getCityId() + "_" + (baumId.isEmpty() ? UUID.randomUUID().toString() : baumId);

        return new TreeRecord(id, getCityId(), lat, lon, genusDe, genusEn, speciesDe, speciesEn);
    }

    /** Trims a JSON string value and maps the literal {@code "null"} to empty. */
    private static String clean(String s) {
        if (s == null) return "";
        String t = s.trim();
        return t.equalsIgnoreCase("null") ? "" : t;
    }
}
