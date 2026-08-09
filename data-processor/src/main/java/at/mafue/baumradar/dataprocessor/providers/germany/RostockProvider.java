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
 * City provider for <strong>Rostock, Germany</strong>.
 *
 * <p>Rostock's open-data portal serves the tree cadastre as a single WGS-84
 * GeoJSON file. The dataset is unusually clean: it ships German <em>and</em>
 * botanical genus and species names directly ({@code gattung_deutsch},
 * {@code gattung_botanisch}, {@code art_deutsch}, {@code art_botanisch}), so no
 * genus derivation is normally required. The whole file is returned at once,
 * therefore pagination is disabled.
 */
public class RostockProvider extends AbstractGeoJsonProvider {

    @Override
    public String getCityId() {
        return "rostock";
    }

    @Override
    public String getName() {
        return "Rostock";
    }

    @Override
    public String getCountry() {
        return "Deutschland";
    }

    @Override
    public double[] getBoundingBox() {
        return new double[]{54.05, 11.95, 54.20, 12.25};
    }

    /** Rostock returns the entire dataset in a single GeoJSON file. */
    @Override
    protected boolean supportsPagination() {
        return false;
    }

    @Override
    protected String getGeoJsonUrl(int offset) {
        return "https://geo.sv.rostock.de/download/opendata/baeume/baeume.json";
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

        // German genus is provided directly; fall back to the botanical genus if absent.
        String genusDe = clean(props.path("gattung_deutsch").asText(""));
        if (genusDe.isEmpty()) {
            genusDe = Translator.germanGenusFromLatin(clean(props.path("gattung_botanisch").asText("")));
        }
        if (genusDe.isEmpty()) return null;
        String genusEn = Translator.translateGenus(genusDe);

        // Drop Rostock's genus-only placeholders: art_deutsch == genus, and the
        // botanical "<Genus> species" form both mean "species unknown".
        String speciesDe = clean(props.path("art_deutsch").asText(""));
        String speciesEn = clean(props.path("art_botanisch").asText(""));
        if (speciesDe.equalsIgnoreCase(genusDe)) speciesDe = "";
        if (speciesEn.toLowerCase().endsWith(" species")) speciesEn = "";

        String uuid = clean(props.path("uuid").asText(""));
        if (uuid.isEmpty()) uuid = UUID.randomUUID().toString();
        String id = getCityId() + "_" + uuid;

        return new TreeRecord(id, getCityId(), lat, lon, genusDe, genusEn, speciesDe, speciesEn);
    }
}
