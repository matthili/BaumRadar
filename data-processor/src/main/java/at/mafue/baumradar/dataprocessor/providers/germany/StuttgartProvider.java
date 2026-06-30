package at.mafue.baumradar.dataprocessor.providers.germany;

import at.mafue.baumradar.dataprocessor.providers.*;
import at.mafue.baumradar.dataprocessor.providers.austria.*;
import at.mafue.baumradar.dataprocessor.providers.germany.*;
import at.mafue.baumradar.dataprocessor.providers.switzerland.*;
import at.mafue.baumradar.dataprocessor.models.*;
import at.mafue.baumradar.dataprocessor.utils.*;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * City provider for <strong>Stuttgart, Germany</strong>.
 *
 * <p>Stuttgart's map portal renders trees only as per-genus WMS image layers,
 * but the underlying GeoServer also exposes the full inventory as a single
 * vector layer, {@code Base:A67_GFM_BAUM_PLUS_EPSG25832}, via WFS 2.0. Features
 * are fetched as GeoJSON with {@code srsName=EPSG:4326} (so the server delivers
 * WGS-84 directly, no reprojection needed) and paged through
 * {@code count}/{@code startIndex}. The botanical name ({@code BAUMART_BOT},
 * e.g. {@code "Quercus rubra"}) yields the German genus; {@code BAUMART} holds
 * the German species name (e.g. {@code "Amerikanische Roteiche"}).
 */
public class StuttgartProvider extends AbstractGeoJsonProvider {

    /** WFS page size (GeoServer serves large pages happily). */
    private static final int PAGE = 50000;

    @Override
    public String getCityId() {
        return "stuttgart";
    }

    @Override
    public String getName() {
        return "Stuttgart";
    }

    @Override
    public String getCountry() {
        return "Deutschland";
    }

    @Override
    public double[] getBoundingBox() {
        return new double[]{48.69, 9.04, 48.87, 9.32};
    }

    @Override
    protected String getGeoJsonUrl(int offset) {
        // WFS 2.0 GetFeature as GeoJSON, WGS-84, paginated via count/startIndex.
        return "https://geoserver.stuttgart.de/geoserver/ows"
            + "?service=WFS&version=2.0.0&request=GetFeature"
            + "&typeNames=Base:A67_GFM_BAUM_PLUS_EPSG25832"
            + "&outputFormat=application/json&srsName=EPSG:4326"
            + "&count=" + PAGE + "&startIndex=" + offset;
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

        String botanical = clean(props.path("BAUMART_BOT").asText(""));
        String genusDe = Translator.germanGenusFromLatin(botanical);
        if (genusDe.isEmpty()) return null;
        String genusEn = Translator.translateGenus(genusDe);

        String speciesDe = clean(props.path("BAUMART").asText(""));
        String speciesEn = botanical;

        String baid = clean(props.path("BAID").asText(""));
        String id = getCityId() + "_" + (baid.isEmpty() ? java.util.UUID.randomUUID().toString() : baid);

        return new TreeRecord(id, getCityId(), lat, lon, genusDe, genusEn, speciesDe, speciesEn);
    }

    /** Trims a JSON string value and maps the literal {@code "null"} to empty. */
    private static String clean(String s) {
        if (s == null) return "";
        String t = s.trim();
        return t.equalsIgnoreCase("null") ? "" : t;
    }
}
