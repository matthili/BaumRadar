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
 * City provider for <strong>Dortmund, Germany</strong>.
 *
 * <p>Downloads the tree cadastre from Dortmund's Opendatasoft-based open-data
 * portal as paginated GeoJSON exports.  Property field names are truncated
 * (e.g. {@code art_botani} instead of {@code art_botanisch}), which is a
 * common artifact of Shapefile-origin datasets with 10-character field limits.
 *
 * <p>The genus is derived from the first word of the botanical species name,
 * following the same convention as {@link FreiburgProvider}.
 */
public class DortmundProvider extends AbstractGeoJsonProvider {

    @Override
    public String getCityId() {
        return "dortmund";
    }

    @Override
    public String getName() {
        return "Dortmund";
    }

    @Override
    public String getCountry() {
        return "Deutschland";
    }

    @Override
    public double[] getBoundingBox() {
        return new double[]{51.42, 7.33, 51.58, 7.62};
    }

    @Override
    protected String getGeoJsonUrl(int offset) {
        return "https://open-data.dortmund.de/api/explore/v2.1/catalog/datasets/baumkataster/exports/geojson?lang=de&timezone=Europe%2FBerlin&limit=" + BATCH_SIZE + "&offset=" + offset;
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
        
        String idStr = props.path("id").asText("");
        if (idStr.isEmpty()) idStr = UUID.randomUUID().toString();
        String id = getCityId() + "_" + idStr;
        
        String art_botani = props.path("art_botani").asText("");
        String artDe = props.path("art_deutsc").asText("");
        
        String genusDe = "";
        
        // Extract genus from the first word of the Latin botanical species name
        if (!art_botani.isEmpty()) {
            String[] parts = art_botani.split(" ");
            if (parts.length > 0) genusDe = parts[0];
        } else {
            return null; // Cannot classify without a botanical name
        }
        
        String genusEn = Translator.translateGenus(genusDe);
        String artEn = "";
        
        return new TreeRecord(id, getCityId(), lat, lon, genusDe, genusEn, artDe, artEn);
    }
}

