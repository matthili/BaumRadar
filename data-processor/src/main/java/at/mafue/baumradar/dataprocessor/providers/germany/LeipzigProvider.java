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
 * City provider for <strong>Leipzig, Germany</strong>.
 *
 * <p>Leipzig's geo portal serves the tree cadastre via a WFS {@code GetFeature}
 * request that returns GeoJSON. Unlike the WGS-84 portals, coordinates are
 * provided in EPSG:25833 (UTM zone 33N), so each point is reprojected via
 * {@link UtmConverter#utm33NToWgs84}. The genus arrives as a bare Latin name
 * ({@code gattung}, e.g. {@code "Tilia"}) and is mapped to the German genus;
 * {@code ga_lang_deutsch} carries the German species name (which may be null).
 * The WFS returns the full dataset in one response, so pagination is disabled.
 */
public class LeipzigProvider extends AbstractGeoJsonProvider {

    @Override
    public String getCityId() {
        return "leipzig";
    }

    @Override
    public String getName() {
        return "Leipzig";
    }

    @Override
    public String getCountry() {
        return "Deutschland";
    }

    @Override
    public double[] getBoundingBox() {
        return new double[]{51.23, 12.20, 51.45, 12.55};
    }

    /** The Leipzig WFS returns the entire dataset in a single response. */
    @Override
    protected boolean supportsPagination() {
        return false;
    }

    @Override
    protected String getGeoJsonUrl(int offset) {
        return "https://geodienste.leipzig.de/l3/OpenData//wfs?VERSION=1.3.0&REQUEST=getFeature&typeName=OpenData%3ABaeume&outputFormat=application/json";
    }

    @Override
    protected TreeRecord mapFeatureToTree(JsonNode feature) {
        JsonNode props = feature.path("properties");
        JsonNode geom = feature.path("geometry");
        if (props.isMissingNode() || geom.isMissingNode()) return null;
        if (!"Point".equals(geom.path("type").asText())) return null;

        JsonNode coords = geom.path("coordinates");
        if (coords.size() < 2) return null;

        // Coordinates are EPSG:25833 (UTM 33N) easting/northing → reproject to WGS-84.
        double easting = coords.get(0).asDouble();
        double northing = coords.get(1).asDouble();
        if (easting == 0 || northing == 0) return null;
        double[] latlon = UtmConverter.utm33NToWgs84(easting, northing);
        double lat = latlon[0];
        double lon = latlon[1];

        // "gattung" is the bare Latin genus (e.g. "Tilia"); fall back to the full
        // scientific name if it is missing.
        String latinGenus = clean(props.path("gattung").asText(""));
        String wiss = clean(props.path("ga_lang_wiss").asText(""));
        String genusDe = Translator.germanGenusFromLatin(latinGenus.isEmpty() ? wiss : latinGenus);
        if (genusDe.isEmpty()) return null;
        String genusEn = Translator.translateGenus(genusDe);

        String speciesDe = clean(props.path("ga_lang_deutsch").asText(""));
        String speciesEn = wiss;

        String oid = clean(props.path("objectid").asText(""));
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
