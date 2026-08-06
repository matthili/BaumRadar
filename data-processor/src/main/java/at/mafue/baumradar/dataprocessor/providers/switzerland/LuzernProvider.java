package at.mafue.baumradar.dataprocessor.providers.switzerland;

import at.mafue.baumradar.dataprocessor.providers.*;
import at.mafue.baumradar.dataprocessor.models.*;
import at.mafue.baumradar.dataprocessor.utils.*;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * City provider for <strong>Luzern (Lucerne), Switzerland</strong>.
 *
 * <p>The city runs an ArcGIS MapServer whose WFS endpoint can emit GeoJSON and
 * happily returns the whole layer in a single response — despite the Swiss
 * origin already reprojected to WGS-84.
 *
 * <p>The botanical name is split across two columns: {@code GATTUNG_TEXT} holds
 * the genus ("Acer"), {@code ART_SORTE_TEXT} the species epithet
 * ("pseudoplatanus"); they are recombined here. The source carries no German
 * species name, so only the derived genus is German.
 */
public class LuzernProvider extends AbstractGeoJsonProvider {

    @Override
    public String getCityId() {
        return "luzern";
    }

    @Override
    public String getName() {
        return "Luzern";
    }

    @Override
    public String getCountry() {
        return "Schweiz";
    }

    @Override
    public double[] getBoundingBox() {
        return new double[]{47.0284, 8.2520, 47.0695, 8.3569};
    }

    @Override
    protected String getGeoJsonUrl(int offset) {
        return "https://map.stadtluzern.ch/server/services/OGD/baum/MapServer/WFSServer"
            + "?service=WFS&version=2.0.0&request=GetFeature"
            + "&typeNames=esri:Baum&outputFormat=GEOJSON";
    }

    /** The service returns all ~12k features at once; a second page would repeat them. */
    @Override
    protected boolean supportsPagination() {
        return false;
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
        if (lon == 0 || lat == 0) return null;

        String latinGenus = clean(props.path("GATTUNG_TEXT").asText(""));
        String epithet = clean(props.path("ART_SORTE_TEXT").asText(""));
        String botanical = epithet.isEmpty() ? latinGenus : latinGenus + " " + epithet;

        String genusDe = Translator.germanGenusFromLatin(latinGenus);
        if (genusDe.isEmpty()) return null;
        String genusEn = Translator.translateGenus(genusDe);

        String id = getCityId() + "_" + clean(props.path("OBJECTID").asText(""));

        // No German species name in the source — the genus carries the meaning.
        return new TreeRecord(id, getCityId(), lat, lon, genusDe, genusEn, "", botanical);
    }

    /** Trims a JSON string value and maps the literal {@code "null"} to empty. */
    private static String clean(String s) {
        if (s == null) return "";
        String t = s.trim();
        return t.equalsIgnoreCase("null") ? "" : t;
    }
}
