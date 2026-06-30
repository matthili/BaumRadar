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
 * City provider for <strong>Gelsenkirchen, Germany</strong>.
 *
 * <p>Gelsenkirchen serves its tree cadastre through a (relay-protected) ArcGIS
 * REST <em>MapServer</em>. Unlike Graz's service, this one does not offer
 * {@code f=geojson} ("Format not supported by protected service of type
 * MapServer"), so it is queried as native Esri JSON ({@code f=json}): each
 * feature carries {@code attributes} (not {@code properties}) and an
 * {@code {x, y}} geometry (not a coordinate array). {@link AbstractGeoJsonProvider}
 * still drives the loop — it only looks for the {@code features} array and
 * delegates each element here — so this {@link #mapFeatureToTree} reads the
 * Esri-JSON shape directly.
 *
 * <p>Coordinates are requested in WGS-84 ({@code outSR=4326}); paging uses
 * {@code resultOffset}/{@code resultRecordCount} ordered by {@code OBJECTID}.
 * {@code Baumart} is the botanical name (→ German genus); {@code Baumart_dt}
 * the German species name.
 */
public class GelsenkirchenProvider extends AbstractGeoJsonProvider {

    /** Page size; the service advertises maxRecordCount 100000, 20k is safe. */
    private static final int PAGE = 20000;

    private static final String BASE =
        "https://gdi.gelsenkirchen.de/wss/service/ags-relay/GDI_GE/guest/arcgis/rest/services"
        + "/UN_Umwelt_Natur/Baumbestand/MapServer/0/query";

    @Override
    public String getCityId() {
        return "gelsenkirchen";
    }

    @Override
    public String getName() {
        return "Gelsenkirchen";
    }

    @Override
    public String getCountry() {
        return "Deutschland";
    }

    @Override
    public double[] getBoundingBox() {
        return new double[]{51.48, 6.97, 51.62, 7.15};
    }

    @Override
    protected String getGeoJsonUrl(int offset) {
        // Esri JSON (f=json), WGS-84, ordered by OBJECTID for deterministic paging.
        return BASE
            + "?where=1%3D1&outFields=OBJECTID,Baumart,Baumart_dt&returnGeometry=true"
            + "&outSR=4326&orderByFields=OBJECTID&f=json"
            + "&resultOffset=" + offset + "&resultRecordCount=" + PAGE;
    }

    @Override
    protected TreeRecord mapFeatureToTree(JsonNode feature) {
        // Esri JSON shape: { "attributes": {...}, "geometry": { "x": lon, "y": lat } }
        JsonNode attrs = feature.path("attributes");
        JsonNode geom = feature.path("geometry");
        if (attrs.isMissingNode() || geom.isMissingNode()) return null;

        JsonNode x = geom.path("x");
        JsonNode y = geom.path("y");
        if (x.isMissingNode() || y.isMissingNode()) return null;
        double lon = x.asDouble();
        double lat = y.asDouble();
        if (lat == 0 || lon == 0) return null;

        // Baumart is the botanical name (e.g. "Acer platanoides") → German genus.
        String botanical = clean(attrs.path("Baumart").asText(""));
        String genusDe = Translator.germanGenusFromLatin(botanical);
        if (genusDe.isEmpty()) return null;
        String genusEn = Translator.translateGenus(genusDe);

        String speciesDe = clean(attrs.path("Baumart_dt").asText(""));
        String speciesEn = botanical;

        String oid = clean(attrs.path("OBJECTID").asText(""));
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
