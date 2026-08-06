package at.mafue.baumradar.dataprocessor.providers.germany;

import at.mafue.baumradar.dataprocessor.providers.*;
import at.mafue.baumradar.dataprocessor.models.*;
import at.mafue.baumradar.dataprocessor.utils.*;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * City provider for <strong>Wesel, Germany</strong>.
 *
 * <p>The Hanseatic city publishes its tree cadastre as one plain GeoJSON file on
 * its geoportal — no service, no pagination, coordinates already in WGS-84.
 *
 * <p>{@code GATTUNG} holds the botanical genus ("Quercus"), while {@code GA_LANG}
 * packs botanical species and German name into one comma-separated string
 * ("Quercus robur, Stieleiche") — the comma is the separator between the two
 * languages here, not a name inversion as in the KRZN sources.
 */
public class WeselProvider extends AbstractGeoJsonProvider {

    @Override
    public String getCityId() {
        return "wesel";
    }

    @Override
    public String getName() {
        return "Wesel";
    }

    @Override
    public String getCountry() {
        return "Deutschland";
    }

    @Override
    public double[] getBoundingBox() {
        return new double[]{51.6185, 6.4851, 51.7208, 6.7115};
    }

    @Override
    protected String getGeoJsonUrl(int offset) {
        return "https://geoportal.wesel.de/opendata/Baumkataster/Baumkataster.geojson";
    }

    /** One file, one response — a second request would fetch the same data again. */
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

        // "Quercus robur, Stieleiche" → botanical species | German name
        String gaLang = clean(props.path("GA_LANG").asText(""));
        int comma = gaLang.indexOf(',');
        String botanical = comma < 0 ? gaLang : gaLang.substring(0, comma).trim();
        String speciesDe = comma < 0 ? "" : gaLang.substring(comma + 1).trim();

        // The dedicated genus column is authoritative; GA_LANG covers the ~100
        // records that leave it empty.
        String latinGenus = clean(props.path("GATTUNG").asText(""));
        String genusDe = Translator.germanGenusFromLatin(
            latinGenus.isEmpty() ? botanical : latinGenus);
        if (genusDe.isEmpty()) return null;
        String genusEn = Translator.translateGenus(genusDe);

        String id = getCityId() + "_" + clean(props.path("ID").asText(""));

        return new TreeRecord(id, getCityId(), lat, lon, genusDe, genusEn, speciesDe, botanical);
    }

    /** Trims a JSON string value and maps the literal {@code "null"} to empty. */
    private static String clean(String s) {
        if (s == null) return "";
        String t = s.trim();
        return t.equalsIgnoreCase("null") ? "" : t;
    }
}
