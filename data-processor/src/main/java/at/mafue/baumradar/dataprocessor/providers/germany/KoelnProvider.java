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
 * City provider for <strong>Köln (Cologne), Germany</strong>.
 *
 * <p>Cologne publishes its tree cadastre via a MapServer WFS that supports
 * GeoJSON output and {@code count}/{@code startIndex} pagination. Coordinates
 * are delivered in EPSG:25832 (UTM zone 32N) — the service ignores a requested
 * {@code srsName} — so each point is reprojected via
 * {@link UtmConverter#utm32NToWgs84}. The botanical name ({@code Botanischer_Name})
 * yields the German genus; {@code Deutscher_Name} holds the German species name.
 */
public class KoelnProvider extends AbstractGeoJsonProvider {

    @Override
    public String getCityId() {
        return "koeln";
    }

    @Override
    public String getName() {
        return "Köln";
    }

    @Override
    public String getCountry() {
        return "Deutschland";
    }

    @Override
    public double[] getBoundingBox() {
        return new double[]{50.83, 6.77, 51.10, 7.16};
    }

    /** Large page size: Köln's WFS returns malformed JSON under many rapid requests,
     *  so fetch in a few big chunks (the server happily serves 60k features at once). */
    private static final int PAGE = 60000;

    @Override
    protected String getGeoJsonUrl(int offset) {
        // WFS 2.0 GetFeature as GeoJSON, paginated via count/startIndex.
        return "https://geoportal.stadt-koeln.de/wss/service/baumkataster_extern_wfs/guest"
            + "?SERVICE=WFS&VERSION=2.0.0&REQUEST=GetFeature&typeNames=ms:baumkataster"
            + "&outputFormat=geojson&count=" + PAGE + "&startIndex=" + offset;
    }

    /** Pause between pages so the (rapid-request-sensitive) server isn't hammered. */
    @Override
    protected long pageDelayMs() {
        return 1500;
    }

    @Override
    protected TreeRecord mapFeatureToTree(JsonNode feature) {
        JsonNode props = feature.path("properties");
        JsonNode geom = feature.path("geometry");
        if (props.isMissingNode() || geom.isMissingNode()) return null;
        if (!"Point".equals(geom.path("type").asText())) return null;

        JsonNode coords = geom.path("coordinates");
        if (coords.size() < 2) return null;

        // EPSG:25832 (UTM 32N) easting/northing → WGS-84
        double easting = coords.get(0).asDouble();
        double northing = coords.get(1).asDouble();
        if (easting == 0 || northing == 0) return null;
        double[] latlon = UtmConverter.utm32NToWgs84(easting, northing);
        double lat = latlon[0];
        double lon = latlon[1];

        String botanical = clean(props.path("Botanischer_Name").asText(""));
        String genusDe = Translator.germanGenusFromLatin(botanical);
        if (genusDe.isEmpty()) return null;
        String genusEn = Translator.translateGenus(genusDe);

        String speciesDe = clean(props.path("Deutscher_Name").asText(""));
        String speciesEn = botanical;

        // Baumnummer is NOT unique in the source (it triggers primary-key collisions),
        // so use a random UUID like FreiburgProvider/WuerzburgProvider.
        String id = getCityId() + "_" + UUID.randomUUID();

        return new TreeRecord(id, getCityId(), lat, lon, genusDe, genusEn, speciesDe, speciesEn);
    }

    /** Trims a JSON string value and maps the literal {@code "null"} to empty. */
    private static String clean(String s) {
        if (s == null) return "";
        String t = s.trim();
        return t.equalsIgnoreCase("null") ? "" : t;
    }
}
