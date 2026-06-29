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
 * City provider for <strong>Zürich, Switzerland</strong>.
 *
 * <p>Downloads the tree cadastre from the City of Zürich's OGD (Open
 * Government Data) WFS endpoint as paginated GeoJSON.  The dataset uses
 * Latin botanical names for the genus ({@code baumgattunglat}) and German
 * common names for the species ({@code baumnamedeu}).  WFS pagination is
 * controlled via {@code startIndex} and {@code maxFeatures} parameters.
 */
public class ZurichProvider extends AbstractGeoJsonProvider {

    @Override
    public String getCityId() {
        return "zurich";
    }

    @Override
    public String getName() {
        return "Zürich";
    }

    @Override
    public String getCountry() {
        return "Schweiz";
    }

    @Override
    public double[] getBoundingBox() {
        return new double[]{47.32, 8.44, 47.43, 8.62};
    }

    @Override
    protected String getGeoJsonUrl(int offset) {
        return "https://www.ogd.stadt-zuerich.ch/wfs/geoportal/Baumkataster?service=WFS&version=1.1.0&request=GetFeature&outputFormat=GeoJSON&typename=baumkataster_baumstandorte&startIndex=" + offset + "&maxFeatures=" + BATCH_SIZE;
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
        
        String idStr = props.path("baumnummer").asText("");
        if (idStr.isEmpty()) idStr = UUID.randomUUID().toString();
        String id = getCityId() + "_" + idStr;
        
        // The dataset stores the genus as a Latin name (e.g. "Acer") → normalize it.
        String latinGenus = props.path("baumgattunglat").asText("");
        if (latinGenus.isEmpty() || latinGenus.equalsIgnoreCase("null")) return null;
        String genusDe = Translator.germanGenusFromLatin(latinGenus);
        if (genusDe.isEmpty()) return null;
        String genusEn = Translator.translateGenus(genusDe);

        String speciesDe = props.path("baumnamedeu").asText("");
        if (speciesDe.equalsIgnoreCase("null")) speciesDe = "";
        String speciesEn = props.path("baumnamelat").asText("");
        if (speciesEn.equalsIgnoreCase("null")) speciesEn = "";

        return new TreeRecord(id, getCityId(), lat, lon, genusDe, genusEn, speciesDe, speciesEn);
    }
}

