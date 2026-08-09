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
 * City provider for <strong>Hamburg, Germany</strong>.
 *
 * <p>Hamburg's street-tree cadastre is delivered as a ZIP archive containing
 * a single GeoJSON file.  Unlike most other portals, coordinates are
 * provided in EPSG:25832 (UTM zone 32N) rather than WGS-84, so every
 * point must be reprojected via {@link UtmConverter#utm32NToWgs84}.
 *
 * <p>Geometry types include both {@code Point} and {@code MultiPoint};
 * for MultiPoint features only the first coordinate pair is used.
 * Pagination is not supported — the entire dataset is fetched in one request.
 */
public class HamburgProvider extends AbstractGeoJsonProvider {

    @Override
    public String getCityId() {
        return "hamburg";
    }

    @Override
    public String getName() {
        return "Hamburg";
    }

    @Override
    public String getCountry() {
        return "Deutschland";
    }

    @Override
    public double[] getBoundingBox() {
        return new double[]{53.39, 9.73, 53.73, 10.32};
    }

    /** The Hamburg portal delivers data as a ZIP archive, not raw GeoJSON. */
    @Override
    protected boolean isZipped() {
        return true;
    }

    /** The Hamburg endpoint returns all data at once; pagination is not available. */
    @Override
    protected boolean supportsPagination() {
        return false;
    }

    @Override
    protected String getGeoJsonUrl(int offset) {
        return "https://geodienste.hamburg.de/download?url=https://qs-geodienste.hamburg.de/HH_WFS_Strassenbaumkataster&f=json";
    }

    @Override
    protected TreeRecord mapFeatureToTree(JsonNode feature) {
        JsonNode props = feature.path("properties");
        JsonNode geom = feature.path("geometry");

        if (props.isMissingNode() || geom.isMissingNode()) return null;
        // Hamburg uses both Point and MultiPoint geometries.
        String type = geom.path("type").asText();
        if (!"MultiPoint".equals(type) && !"Point".equals(type)) return null;

        JsonNode coords = geom.path("coordinates");
        if (coords.size() < 1) return null;

        // The portal now serves WGS-84 [lon, lat] (it previously delivered
        // EPSG:25832 / UTM 32N — converting that today would corrupt every point).
        double lon, lat;
        if ("MultiPoint".equals(type)) {
            JsonNode firstPoint = coords.get(0);
            if (firstPoint.size() < 2) return null;
            lon = firstPoint.get(0).asDouble();
            lat = firstPoint.get(1).asDouble();
        } else {
            if (coords.size() < 2) return null;
            lon = coords.get(0).asDouble();
            lat = coords.get(1).asDouble();
        }
        if (lat == 0 || lon == 0) return null;

        String idStr = feature.path("id").asText("");
        if (idStr.isEmpty()) idStr = clean(props.path("baumid").asText(""));
        if (idStr.isEmpty()) idStr = UUID.randomUUID().toString();
        String id = getCityId() + "_" + idStr;

        // German genus directly; fall back to deriving it from the Latin genus.
        String genusDe = clean(props.path("gattung_deutsch").asText(""));
        if (genusDe.isEmpty()) {
            genusDe = Translator.germanGenusFromLatin(clean(props.path("gattung_latein").asText("")));
        }
        if (genusDe.isEmpty()) return null;
        String genusEn = Translator.translateGenus(genusDe);

        String speciesDe = clean(props.path("art_deutsch").asText(""));
        String speciesEn = clean(props.path("art_latein").asText(""));

        return new TreeRecord(id, getCityId(), lat, lon, genusDe, genusEn, speciesDe, speciesEn);
    }
}

