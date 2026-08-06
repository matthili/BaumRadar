package at.mafue.baumradar.dataprocessor.providers.germany;

import at.mafue.baumradar.dataprocessor.providers.*;
import at.mafue.baumradar.dataprocessor.models.*;
import at.mafue.baumradar.dataprocessor.utils.*;

import java.util.Map;

/**
 * GML flavour of {@link KrznProvider} for KRZN layers whose <em>GeoJSON</em>
 * output is broken.
 *
 * <p>Viersen contains records with multi-geometries (a tree group mapped as
 * several stems). The service answers any GeoJSON page containing such a record
 * with <em>“Could not export multi geometry MULTI_GEOMETRY as GeoJSON”</em>
 * (HTTP 500) — and they are spread across the whole layer, so paging around them
 * is impossible. Its GML output delivers everything, so this provider takes that
 * route: same URL, same attributes, same mapping — only the transport differs.
 * The parser keeps the first position of a multi-geometry, which is precisely
 * what a tree needs.
 *
 * <p>GML coordinates arrive in the native EPSG:25832 (UTM zone 32N) and are
 * reprojected via {@link UtmConverter#utm32NToWgs84}.
 */
public class KrznGmlProvider extends AbstractGmlProvider {

    private static final String SERVICE =
        "https://geoservices.krzn.de/security-proxy/services/wfs_verb_baum";

    /** GML is ~1,4 kB per record, so pages stay smaller than in the GeoJSON flavour. */
    private static final int PAGE = 5000;

    private final String cityId;
    private final String name;
    private final String layer;
    private final double[] boundingBox;

    public KrznGmlProvider(String cityId, String name, String layer, double[] boundingBox) {
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
    protected String getGmlUrl(int offset) {
        // No OUTPUTFORMAT → the service's default GML 3.2.
        return SERVICE + "?SERVICE=WFS&VERSION=2.0.0&REQUEST=GetFeature"
            + "&TYPENAMES=gis:" + layer
            + "&COUNT=" + PAGE + "&STARTINDEX=" + offset;
    }

    @Override
    protected TreeRecord mapFeature(Map<String, String> fields, double easting, double northing) {
        if (easting == 0 || northing == 0) return null;
        double[] latlon = UtmConverter.utm32NToWgs84(easting, northing);
        if (!KrznProvider.plausible(latlon[0], latlon[1], boundingBox)) return null;
        return KrznProvider.mapFields(cityId, latlon[0], latlon[1],
            name -> fields.getOrDefault(name, ""));
    }
}
