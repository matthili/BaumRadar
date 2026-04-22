package at.mafue.baumradar.dataprocessor;

public class UtmConverter {

    // WGS84 ellipsoid constants
    private static final double a = 6378137.0;
    private static final double f = 1.0 / 298.257223563;
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

        double e = Math.sqrt(1 - (1 - f) * (1 - f));
        double e1sq = e * e / (1 - e * e);

        double x = easting - 500000.0;
        double y = northernHemisphere ? northing : northing - 10000000.0;

        double m = y / k0;
        double mu = m / (a * (1 - e * e / 4 - 3 * Math.pow(e, 4) / 64 - 5 * Math.pow(e, 6) / 256));

        double e1 = (1 - Math.sqrt(1 - e * e)) / (1 + Math.sqrt(1 - e * e));

        double j1 = 3 * e1 / 2 - 27 * Math.pow(e1, 3) / 32;
        double j2 = 21 * e1 * e1 / 16 - 55 * Math.pow(e1, 4) / 32;
        double j3 = 151 * Math.pow(e1, 3) / 96;
        double j4 = 1097 * Math.pow(e1, 4) / 512;

        double fp = mu + j1 * Math.sin(2 * mu) + j2 * Math.sin(4 * mu) + j3 * Math.sin(6 * mu) + j4 * Math.sin(8 * mu);

        double c1 = e1sq * Math.pow(Math.cos(fp), 2);
        double t1 = Math.pow(Math.tan(fp), 2);
        double r1 = a * (1 - e * e) / Math.pow(1 - e * e * Math.pow(Math.sin(fp), 2), 1.5);
        double n1 = a / Math.sqrt(1 - e * e * Math.pow(Math.sin(fp), 2));
        double d = x / (n1 * k0);

        double q1 = n1 * Math.tan(fp) / r1;
        double q2 = d * d / 2;
        double q3 = (5 + 3 * t1 + 10 * c1 - 4 * c1 * c1 - 9 * e1sq) * Math.pow(d, 4) / 24;
        double q4 = (61 + 90 * t1 + 298 * c1 + 45 * t1 * t1 - 252 * e1sq - 3 * c1 * c1) * Math.pow(d, 6) / 720;
        
        double lat = fp - q1 * (q2 - q3 + q4);

        double q5 = d;
        double q6 = (1 + 2 * t1 + c1) * Math.pow(d, 3) / 6;
        double q7 = (5 - 2 * c1 + 28 * t1 - 3 * c1 * c1 + 8 * e1sq + 24 * t1 * t1) * Math.pow(d, 5) / 120;
        
        double lon = (q5 - q6 + q7) / Math.cos(fp) + Math.toRadians((zone - 1) * 6 - 180 + 3);

        return new double[]{Math.toDegrees(lat), Math.toDegrees(lon)};
    }
}
