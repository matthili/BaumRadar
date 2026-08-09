package at.mafue.baumradar.dataprocessor.providers.germany;

import at.mafue.baumradar.dataprocessor.providers.*;
import at.mafue.baumradar.dataprocessor.providers.austria.*;
import at.mafue.baumradar.dataprocessor.providers.germany.*;
import at.mafue.baumradar.dataprocessor.providers.switzerland.*;
import at.mafue.baumradar.dataprocessor.models.*;
import at.mafue.baumradar.dataprocessor.utils.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import static at.mafue.baumradar.dataprocessor.utils.Text.clean;

/**
 * City provider for <strong>Würzburg, Germany</strong>.
 *
 * <p>Würzburg publishes its tree cadastre via an Opendatasoft GeoJSON export
 * in WGS-84. Each feature carries a German common name ({@code baumart}, in
 * ALL CAPS, e.g. {@code "ESCHE RAYWOOD"}) and a botanical name
 * ({@code baumart_la}, e.g. {@code "Fraxinus angustifolia Raywood"}). The
 * German genus is derived from the botanical name (most reliable); the German
 * common name is title-cased and kept as the species label. The export
 * delivers all records at once, so pagination is disabled.
 */
public class WuerzburgProvider extends AbstractGeoJsonProvider {

    @Override
    public String getCityId() {
        return "wuerzburg";
    }

    @Override
    public String getName() {
        return "Würzburg";
    }

    @Override
    public String getCountry() {
        return "Deutschland";
    }

    @Override
    public double[] getBoundingBox() {
        return new double[]{49.74, 9.88, 49.84, 10.00};
    }

    /** The Opendatasoft export returns the whole dataset in one response. */
    @Override
    protected boolean supportsPagination() {
        return false;
    }

    @Override
    protected String getGeoJsonUrl(int offset) {
        return "https://opendata.wuerzburg.de/api/explore/v2.1/catalog/datasets/baumkataster_stadt_wuerzburg/exports/geojson?lang=de&timezone=Europe%2FBerlin";
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

        String baumartLat = clean(props.path("baumart_la").asText(""));
        String baumartDe = clean(props.path("baumart").asText(""));

        // Derive the German genus from the botanical name; if absent, fall back
        // to the first word of the German common name.
        String genusDe = Translator.germanGenusFromLatin(baumartLat);
        if (genusDe.isEmpty() && !baumartDe.isEmpty()) {
            genusDe = titleCase(baumartDe.split("\\s+")[0]);
        }
        if (genusDe.isEmpty()) return null;

        String genusEn = Translator.translateGenus(genusDe);
        String speciesDe = titleCase(baumartDe); // source ships ALL CAPS
        String speciesEn = baumartLat;

        // source_id is NOT reliably unique in the Würzburg export and triggers
        // primary-key collisions, so use a random UUID like FreiburgProvider does.
        String id = getCityId() + "_" + UUID.randomUUID();

        return new TreeRecord(id, getCityId(), lat, lon, genusDe, genusEn, speciesDe, speciesEn);
    }

    /** Converts an ALL-CAPS or mixed string to Title Case ("ESCHE RAYWOOD" → "Esche Raywood"). */
    private static String titleCase(String s) {
        if (s == null || s.trim().isEmpty()) return "";
        String[] words = s.trim().toLowerCase().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }
}
