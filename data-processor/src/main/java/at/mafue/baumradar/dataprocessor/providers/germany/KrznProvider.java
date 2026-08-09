package at.mafue.baumradar.dataprocessor.providers.germany;

import at.mafue.baumradar.dataprocessor.providers.*;
import at.mafue.baumradar.dataprocessor.models.*;
import at.mafue.baumradar.dataprocessor.utils.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import static at.mafue.baumradar.dataprocessor.utils.Text.clean;

/**
 * City provider for the <strong>Lower Rhine municipalities served by the KRZN</strong>
 * (Kommunales Rechenzentrum Niederrhein), Germany.
 *
 * <p>A single open WFS 2.0 service publishes one tree layer per municipality —
 * all sharing the same schema. Hence one provider serves them all, parametrized
 * with city id, display name, layer name and bounding box; {@code Main} registers
 * one instance per municipality. Licence: Datenlizenz Deutschland – Zero 2.0,
 * explicitly without access restrictions.
 *
 * <p>Mapping specifics:
 * <ul>
 *   <li>The GeoJSON output arrives in WGS-84 lon/lat although the service advertises
 *       EPSG:25832; a guard reprojects UTM coordinates should that ever change.</li>
 *   <li>{@code BOTANISCHER_NAME} drives the genus (present on every sampled record).
 *       German names come in two styles: canonical ("Stiel-Eiche" — Krefeld, Kleve,
 *       Viersen) and inverted ("Eiche, Stiel-" — the smaller municipalities); the
 *       latter is flipped back into reading order.</li>
 *   <li>Felled ({@code GEFAELLT != 0}) and retired ({@code AKTIV = 0}) records are
 *       skipped — warning about a tree stump helps nobody.</li>
 * </ul>
 */
public class KrznProvider extends AbstractGeoJsonProvider {

    /** Shared endpoint of every municipality layer. */
    private static final String SERVICE =
        "https://geoservices.krzn.de/security-proxy/services/wfs_verb_baum";

    /** Page size; deep paging is served without a cap (verified past record 75.000). */
    private static final int PAGE = 20000;

    private final String cityId;
    private final String name;
    private final String layer;
    private final double[] boundingBox;

    /**
     * @param cityId URL-safe id used for the published slice (e.g. {@code "krefeld"})
     * @param name   display name (e.g. {@code "Krefeld"})
     * @param layer  WFS feature type without the {@code gis:} prefix (e.g. {@code "skre_baum"})
     * @param boundingBox {@code [minLat, minLon, maxLat, maxLon]}, taken from the service capabilities
     */
    public KrznProvider(String cityId, String name, String layer, double[] boundingBox) {
        this.cityId = cityId;
        this.name = name;
        this.layer = layer;
        this.boundingBox = boundingBox;
    }

    @Override
    public String getCityId() {
        return cityId;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getCountry() {
        return "Deutschland";
    }

    @Override
    public double[] getBoundingBox() {
        return boundingBox.clone();
    }

    @Override
    protected String getGeoJsonUrl(int offset) {
        return SERVICE + "?SERVICE=WFS&VERSION=2.0.0&REQUEST=GetFeature"
            + "&TYPENAMES=gis:" + layer
            + "&OUTPUTFORMAT=application/geo%2Bjson"
            + "&COUNT=" + PAGE + "&STARTINDEX=" + offset;
    }

    @Override
    protected TreeRecord mapFeatureToTree(JsonNode feature) {
        JsonNode props = feature.path("properties");
        JsonNode geom = feature.path("geometry");
        if (props.isMissingNode() || geom.isMissingNode()) return null;
        if (!"Point".equals(geom.path("type").asText())) return null;

        JsonNode coords = geom.path("coordinates");
        if (coords.size() < 2) return null;
        double x = coords.get(0).asDouble();
        double y = coords.get(1).asDouble();
        if (x == 0 || y == 0) return null;

        double lat;
        double lon;
        if (Math.abs(x) > 180 || Math.abs(y) > 90) {
            // Easting/northing far outside the degree range → EPSG:25832 (UTM 32N).
            double[] latlon = UtmConverter.utm32NToWgs84(x, y);
            lat = latlon[0];
            lon = latlon[1];
        } else {
            lat = y;
            lon = x;
        }

        if (!plausible(lat, lon, boundingBox)) return null;

        return mapFields(cityId, lat, lon, name -> clean(props.path(name).asText("")));
    }

    /**
     * Rejects coordinates outside the municipality (plus ~5 km margin).
     *
     * <p>Not paranoia but experience: a handful of Viersen records carry a
     * Gauß-Krüger easting complete with zone prefix (3317423 instead of 317423)
     * inside a layer declared as EPSG:25832. Read as UTM they land hundreds of
     * kilometres away — south of Geneva, to be precise.
     */
    static boolean plausible(double lat, double lon, double[] bbox) {
        double margin = 0.05;
        return lat >= bbox[0] - margin && lat <= bbox[2] + margin
            && lon >= bbox[1] - margin && lon <= bbox[3] + margin;
    }

    /**
     * Shared field mapping for both flavours of this service (GeoJSON here,
     * GML in {@link KrznGmlProvider}) — the attribute schema is identical, only
     * the transport differs.
     *
     * @param field accessor returning the trimmed raw value, or {@code ""} if absent
     */
    static TreeRecord mapFields(String cityId, double lat, double lon, java.util.function.UnaryOperator<String> field) {
        if (flag(field.apply("GEFAELLT"), 0) != 0) return null;   // gefällt
        if (flag(field.apply("AKTIV"), 1) == 0) return null;      // stillgelegter Datensatz

        String botanical = field.apply("BOTANISCHER_NAME");
        String speciesDe = normalizeGermanName(field.apply("BAUMART"));

        String genusDe = Translator.germanGenusFromLatin(botanical);
        if (genusDe.isEmpty()) genusDe = genusFromGermanName(speciesDe);
        if (genusDe.isEmpty()) return null;
        String genusEn = Translator.translateGenus(genusDe);

        // GDO_GID is the stable per-row key; layers without it (e.g. Moers) fall
        // back to a UUID, since Baumnummer is not unique across a municipality.
        String gid = field.apply("GDO_GID");
        String id = gid.isEmpty() ? cityId + "_" + UUID.randomUUID() : cityId + "_" + gid;

        return new TreeRecord(id, cityId, lat, lon, genusDe, genusEn, speciesDe, botanical);
    }

    /** Parses an integer flag, falling back to {@code fallback} for absent/odd values. */
    private static int flag(String raw, int fallback) {
        if (raw == null || raw.isEmpty()) return fallback;
        try {
            return (int) Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Flips the inverted German names of the smaller municipalities back into
     * reading order: {@code "Eiche, Stiel-"} → {@code "Stiel-Eiche"}. A qualifier
     * that is a placeholder rather than a name part ("Art") is dropped, so
     * {@code "Trompetenbaum, Art"} becomes {@code "Trompetenbaum"}. Canonical
     * names pass through untouched.
     */
    static String normalizeGermanName(String raw) {
        int comma = raw.indexOf(',');
        if (comma < 0) return raw;
        String base = raw.substring(0, comma).trim();
        String qualifier = raw.substring(comma + 1).trim();
        if (qualifier.isEmpty() || qualifier.equalsIgnoreCase("Art") || qualifier.equalsIgnoreCase("Arten")) {
            return base;
        }
        return qualifier.endsWith("-") ? qualifier + base : qualifier + " " + base;
    }

    /**
     * Fallback for records without a botanical name: the genus is the last
     * component of the German name ("Sand-Birke" → "Birke", "Gemeine Robinie" →
     * "Robinie"). Every sampled record carried Latin, so this is a safety net.
     */
    private static String genusFromGermanName(String germanName) {
        if (germanName.isEmpty()) return "";
        String[] parts = germanName.split("[\\s-]+");
        return parts.length == 0 ? "" : parts[parts.length - 1];
    }
}
