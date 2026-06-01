package at.mafue.baumradar.dataprocessor.utils;

import at.mafue.baumradar.dataprocessor.providers.*;
import at.mafue.baumradar.dataprocessor.providers.austria.*;
import at.mafue.baumradar.dataprocessor.providers.germany.*;
import at.mafue.baumradar.dataprocessor.providers.switzerland.*;
import at.mafue.baumradar.dataprocessor.models.*;
import at.mafue.baumradar.dataprocessor.utils.*;

/**
 * Converts UTM (Universal Transverse Mercator) projected coordinates back to
 * geographic WGS-84 latitude/longitude.
 *
 * <p>Several German open-data portals (notably Hamburg) deliver tree positions
 * in EPSG:25832 (UTM zone 32N on the ETRS89/WGS-84 ellipsoid).  This class
 * implements the inverse UTM projection formulas so that those coordinates can
 * be stored as standard latitude/longitude values in the SQLite database.
 *
 * <p>The implementation follows the series-expansion approach described in
 * <em>Snyder, J.P. — Map Projections: A Working Manual (USGS PP 1395)</em>.
 */
public class UtmConverter {

    // WGS-84 ellipsoid parameters
    /** Semi-major axis of the WGS-84 ellipsoid in meters. */
    private static final double a = 6378137.0;
    /** Flattening of the WGS-84 ellipsoid. */
    private static final double f = 1.0 / 298.257223563;
    /** UTM scale factor at the central meridian. */
    private static final double k0 = 0.9996;

    /**
     * Converts EPSG:25832 (UTM Zone 32N) to EPSG:4326 (WGS84 Lat/Lon).
     * @param easting  X coordinate (e.g. 399219.0)
     * @param northing Y coordinate (e.g. 5711961.6)
     * @return [latitude, longitude]
     */
    public static double[] utm32NToWgs84(double easting, double northing) {
        int zone = 32;
        boolean northernHemisphere = true;

        // --- Derived ellipsoid parameters ---
        // First eccentricity of the ellipsoid
        double e = Math.sqrt(1 - (1 - f) * (1 - f));
        // Square of the second eccentricity, used in curvature formulas
        double e1sq = e * e / (1 - e * e);

        // Remove the 500 km false easting that UTM adds to keep coordinates positive
        double x = easting - 500000.0;
        double y = northernHemisphere ? northing : northing - 10000000.0;

        // --- Footpoint latitude (phi_1) via series expansion ---
        // Arc length along the meridian from the equator to the footpoint
        double m = y / k0;
        // Rectifying latitude mu
        double mu = m / (a * (1 - e * e / 4 - 3 * Math.pow(e, 4) / 64 - 5 * Math.pow(e, 6) / 256));

        // Ratio used in the footpoint latitude series
        double e1 = (1 - Math.sqrt(1 - e * e)) / (1 + Math.sqrt(1 - e * e));

        // Series coefficients (J1–J4) for computing the footpoint latitude
        double j1 = 3 * e1 / 2 - 27 * Math.pow(e1, 3) / 32;
        double j2 = 21 * e1 * e1 / 16 - 55 * Math.pow(e1, 4) / 32;
        double j3 = 151 * Math.pow(e1, 3) / 96;
        double j4 = 1097 * Math.pow(e1, 4) / 512;

        // Footpoint latitude (radians)
        double fp = mu + j1 * Math.sin(2 * mu) + j2 * Math.sin(4 * mu) + j3 * Math.sin(6 * mu) + j4 * Math.sin(8 * mu);

        // --- Auxiliary quantities at the footpoint latitude ---
        double c1 = e1sq * Math.pow(Math.cos(fp), 2);      // C1
        double t1 = Math.pow(Math.tan(fp), 2);              // T1
        double r1 = a * (1 - e * e) / Math.pow(1 - e * e * Math.pow(Math.sin(fp), 2), 1.5);  // Meridional radius of curvature
        double n1 = a / Math.sqrt(1 - e * e * Math.pow(Math.sin(fp), 2));  // Prime-vertical radius of curvature
        double d = x / (n1 * k0);  // Normalized easting

        // --- Latitude computation (radians) ---
        double q1 = n1 * Math.tan(fp) / r1;
        double q2 = d * d / 2;
        double q3 = (5 + 3 * t1 + 10 * c1 - 4 * c1 * c1 - 9 * e1sq) * Math.pow(d, 4) / 24;
        double q4 = (61 + 90 * t1 + 298 * c1 + 45 * t1 * t1 - 252 * e1sq - 3 * c1 * c1) * Math.pow(d, 6) / 720;

        double lat = fp - q1 * (q2 - q3 + q4);

        // --- Longitude computation (radians) ---
        double q5 = d;
        double q6 = (1 + 2 * t1 + c1) * Math.pow(d, 3) / 6;
        double q7 = (5 - 2 * c1 + 28 * t1 - 3 * c1 * c1 + 8 * e1sq + 24 * t1 * t1) * Math.pow(d, 5) / 120;

        // Central meridian of the UTM zone: (zone-1)*6 - 180 + 3 degrees
        double lon = (q5 - q6 + q7) / Math.cos(fp) + Math.toRadians((zone - 1) * 6 - 180 + 3);

        return new double[]{Math.toDegrees(lat), Math.toDegrees(lon)};
    }
}

