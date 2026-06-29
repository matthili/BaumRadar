package at.mafue.baumradar.dataprocessor.providers.switzerland;

import at.mafue.baumradar.dataprocessor.providers.*;
import at.mafue.baumradar.dataprocessor.providers.austria.*;
import at.mafue.baumradar.dataprocessor.providers.germany.*;
import at.mafue.baumradar.dataprocessor.providers.switzerland.*;
import at.mafue.baumradar.dataprocessor.models.*;
import at.mafue.baumradar.dataprocessor.utils.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/**
 * City provider for <strong>Zug, Switzerland</strong>.
 *
 * <p>The canton of Zug publishes the municipal tree cadastre as a single
 * GeoJSON file. Despite being Swiss data, coordinates are already in WGS-84
 * (not the national LV95/EPSG:2056 system), so no reprojection is needed.
 * Each feature provides a botanical name ({@code pflanzennamebotanisch}) and a
 * German common name ({@code pflanzennamedeutsch}); the German genus is derived
 * from the botanical name. The file is delivered in one response, so
 * pagination is disabled.
 */
public class ZugProvider extends AbstractGeoJsonProvider {

    @Override
    public String getCityId() {
        return "zug";
    }

    @Override
    public String getName() {
        return "Zug";
    }

    @Override
    public String getCountry() {
        return "Schweiz";
    }

    @Override
    public double[] getBoundingBox() {
        return new double[]{47.13, 8.44, 47.20, 8.54};
    }

    /** Zug returns the entire dataset in a single GeoJSON file. */
    @Override
    protected boolean supportsPagination() {
        return false;
    }

    @Override
    protected String getGeoJsonUrl(int offset) {
        return "https://data.zg.ch/store/13/resource/55";
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

        String botanical = clean(props.path("pflanzennamebotanisch").asText(""));
        String germanName = clean(props.path("pflanzennamedeutsch").asText(""));

        // Genus is derived from the botanical name (e.g. "Betula pubescens" → "Birke").
        String genusDe = Translator.germanGenusFromLatin(botanical);
        if (genusDe.isEmpty()) return null;
        String genusEn = Translator.translateGenus(genusDe);

        String speciesDe = germanName;
        String speciesEn = botanical;

        String rid = clean(props.path("id").asText(""));
        String id = getCityId() + "_" + (rid.isEmpty() ? UUID.randomUUID().toString() : rid);

        return new TreeRecord(id, getCityId(), lat, lon, genusDe, genusEn, speciesDe, speciesEn);
    }

    /** Trims a JSON string value and maps the literal {@code "null"} to empty. */
    private static String clean(String s) {
        if (s == null) return "";
        String t = s.trim();
        return t.equalsIgnoreCase("null") ? "" : t;
    }
}
