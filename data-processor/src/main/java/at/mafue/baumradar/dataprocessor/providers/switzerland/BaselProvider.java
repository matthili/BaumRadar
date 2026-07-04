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
 * City provider for <strong>Basel, Switzerland</strong>.
 *
 * <p>Downloads the tree cadastre from the Canton of Basel-Stadt’s
 * Opendatasoft-based open-data portal as a single GeoJSON export.  The genus
 * is extracted from the first word of the Latin botanical species name
 * ({@code baumart_lateinisch}), similar to the approach used by
 * {@link at.mafue.baumradar.dataprocessor.providers.germany.FreiburgProvider}.
 *
 * <p>The export is fetched in one request (pagination disabled): Opendatasoft's
 * {@code /exports/geojson} endpoint rejects {@code offset + limit > 10000}, so the
 * previous paginated fetch silently capped Basel at 9997 of its ~32&nbsp;400 trees —
 * the same trap that hit
 * {@link at.mafue.baumradar.dataprocessor.providers.germany.DortmundProvider}.
 */
public class BaselProvider extends AbstractGeoJsonProvider {

    @Override
    public String getCityId() {
        return "basel";
    }

    @Override
    public String getName() {
        return "Basel";
    }

    @Override
    public String getCountry() {
        return "Schweiz";
    }

    @Override
    public double[] getBoundingBox() {
        return new double[]{47.53, 7.57, 47.60, 7.68};
    }

    /** The Opendatasoft export returns the whole dataset in one response;
     *  limit/offset paging is capped at 10000 and must not be used. */
    @Override
    protected boolean supportsPagination() {
        return false;
    }

    @Override
    protected String getGeoJsonUrl(int offset) {
        // Single-shot: offset wird ignoriert (siehe supportsPagination). Exakt der Link,
        // der im Browser den kompletten Datensatz (32.406 Bäume) auf einmal liefert.
        return "https://data.bs.ch/api/explore/v2.1/catalog/datasets/100052/exports/geojson/?lang=de&timezone=Europe%2FVienna";
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
        
        String idStr = props.path("gml_id").asText("");
        if (idStr.isEmpty()) idStr = UUID.randomUUID().toString();
        String id = getCityId() + "_" + idStr;
        
        String botanical = props.path("baumart_lateinisch").asText("");
        if (botanical.isEmpty() || botanical.equalsIgnoreCase("null")) return null;

        // Normalize to a clean German genus; keep the full German species name.
        String genusDe = Translator.germanGenusFromLatin(botanical);
        if (genusDe.isEmpty()) return null;
        String genusEn = Translator.translateGenus(genusDe);

        String speciesDe = props.path("baumart_deutsch").asText("");
        if (speciesDe.equalsIgnoreCase("null")) speciesDe = "";
        String speciesEn = botanical;

        return new TreeRecord(id, getCityId(), lat, lon, genusDe, genusEn, speciesDe, speciesEn);
    }
}

