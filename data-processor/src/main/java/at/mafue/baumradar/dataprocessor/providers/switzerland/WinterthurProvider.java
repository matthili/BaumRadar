package at.mafue.baumradar.dataprocessor.providers.switzerland;

import at.mafue.baumradar.dataprocessor.providers.*;
import at.mafue.baumradar.dataprocessor.models.*;
import at.mafue.baumradar.dataprocessor.utils.*;

import java.util.Map;
import java.util.UUID;

/**
 * City provider for <strong>Winterthur, Switzerland</strong>.
 *
 * <p>Winterthur's tree cadastre hides behind what looks like a pure map service:
 * the advertised endpoint is a WMS (images only), but the very same URL answers
 * WFS requests as well — an open one, "Keine Einschränkungen" per its own
 * capabilities. It speaks <em>GML only</em> (no GeoJSON), hence
 * {@link AbstractGmlProvider}; the older WFS 1.0 profile writes positions as
 * {@code gml:coordinates} ("x,y") rather than {@code gml:pos}.
 *
 * <p>Coordinates arrive in the Swiss national grid LV95 (EPSG:2056) and are
 * converted via {@link SwissConverter}. German names list synonyms after a comma
 * ("Hainbuche, Weissbuche"); only the first is kept.
 */
public class WinterthurProvider extends AbstractGmlProvider {

    private static final String SERVICE = "https://stadtplan.winterthur.ch/wms/Baumkataster";

    @Override
    public String getCityId() {
        return "winterthur";
    }

    @Override
    public String getName() {
        return "Winterthur";
    }

    @Override
    public String getCountry() {
        return "Schweiz";
    }

    @Override
    public double[] getBoundingBox() {
        return new double[]{47.4621, 8.6610, 47.5400, 8.7980};
    }

    @Override
    protected String getGmlUrl(int offset) {
        // WFS 1.0 knows no startIndex; the service delivers all ~16.6k trees at once.
        return SERVICE + "?service=WFS&version=1.0.0&request=GetFeature"
            + "&typeName=ms:BaumkatasterBaumstandort";
    }

    @Override
    protected boolean supportsPagination() {
        return false;
    }

    @Override
    protected TreeRecord mapFeature(Map<String, String> fields, double easting, double northing) {
        if (easting == 0 || northing == 0) return null;
        double[] latlon = SwissConverter.lv95ToWgs84(easting, northing);
        double lat = latlon[0];
        double lon = latlon[1];

        String botanical = fields.getOrDefault("Baumart_latein", "").trim();
        String speciesDe = firstName(fields.getOrDefault("Baumart_deutsch", ""));

        String genusDe = Translator.germanGenusFromLatin(botanical);
        if (genusDe.isEmpty()) return null;
        String genusEn = Translator.translateGenus(genusDe);

        // Baumnummer is *almost* unique (one collision in 16.612) — not worth the risk.
        String id = getCityId() + "_" + UUID.randomUUID();

        return new TreeRecord(id, getCityId(), lat, lon, genusDe, genusEn, speciesDe, botanical);
    }

    /**
     * Keeps the first usable of several comma-separated German synonyms:
     * {@code "Hainbuche, Weissbuche"} → {@code "Hainbuche"}. A fragment ending in
     * a hyphen is only half a word ({@code "Sand-, Weissbirke, Hängebirke"}), so
     * the first complete one wins: {@code "Weissbirke"}.
     */
    static String firstName(String raw) {
        String s = raw.trim();
        if (s.isEmpty()) return "";
        String[] parts = s.split(",");
        for (String part : parts) {
            String p = part.trim();
            if (!p.isEmpty() && !p.endsWith("-")) return p;
        }
        return parts[0].trim();
    }
}
