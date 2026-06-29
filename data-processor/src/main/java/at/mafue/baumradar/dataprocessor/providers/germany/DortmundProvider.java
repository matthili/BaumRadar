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
        
        String botanical = props.path("art_botani").asText("");
        if (botanical.isEmpty() || botanical.equalsIgnoreCase("null")) return null;

        // Normalize the ALL-CAPS Latin name to a clean German genus; keep the
        // full species names, nicely cased for display.
        String genusDe = Translator.germanGenusFromLatin(botanical);
        if (genusDe.isEmpty()) return null;
        String genusEn = Translator.translateGenus(genusDe);

        String speciesDe = titleCase(props.path("art_deutsc").asText(""));
        String speciesEn = latinCase(botanical);

        return new TreeRecord(id, getCityId(), lat, lon, genusDe, genusEn, speciesDe, speciesEn);
    }

    /** Capitalizes each word (also after '-' and '/'), lowercasing the rest. */
    private static String titleCase(String s) {
        if (s == null) return "";
        s = s.trim();
        if (s.isEmpty() || s.equalsIgnoreCase("null")) return "";
        StringBuilder sb = new StringBuilder(s.length());
        boolean cap = true;
        for (char c : s.toLowerCase().toCharArray()) {
            if (cap && Character.isLetter(c)) { sb.append(Character.toUpperCase(c)); cap = false; }
            else { sb.append(c); if (c == ' ' || c == '-' || c == '/') cap = true; }
        }
        return sb.toString();
    }

    /** Botanical casing: first letter upper, rest lower ("ACER PLATANOIDES" → "Acer platanoides"). */
    private static String latinCase(String s) {
        if (s == null) return "";
        s = s.trim().toLowerCase();
        if (s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}

