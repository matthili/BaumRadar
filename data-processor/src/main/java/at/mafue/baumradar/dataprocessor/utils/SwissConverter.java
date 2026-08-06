package at.mafue.baumradar.dataprocessor.utils;

/**
 * Converts Swiss national coordinates to WGS-84.
 *
 * <p>Switzerland does not use UTM but its own oblique Mercator projection.
 * The current reference frame <strong>LV95 / EPSG:2056</strong> counts easting
 * from 2,600,000 m and northing from 1,200,000 m (its predecessor LV03 /
 * EPSG:21781 uses 600,000 / 200,000 — those values are accepted too and
 * shifted automatically).
 *
 * <p>Implemented with swisstopo's published approximation formulas, which are
 * accurate to about one metre — three orders of magnitude finer than a tree
 * crown, and they avoid pulling in a full projection library for two cities.
 */
public final class SwissConverter {

    private SwissConverter() {
    }

    /** Offset between the LV95 and the older LV03 false origin. */
    private static final double LV95_EASTING_OFFSET = 2_000_000;
    private static final double LV95_NORTHING_OFFSET = 1_000_000;

    /**
     * @param easting  E / y in metres (LV95 ≈ 2.5–2.8 M, LV03 ≈ 480–840 k)
     * @param northing N / x in metres (LV95 ≈ 1.07–1.3 M, LV03 ≈ 70–300 k)
     * @return {@code [latitude, longitude]} in WGS-84 degrees
     */
    public static double[] lv95ToWgs84(double easting, double northing) {
        // Accept LV03 input as well: shift it onto the LV95 origin first.
        double e = easting < LV95_EASTING_OFFSET ? easting + LV95_EASTING_OFFSET : easting;
        double n = northing < LV95_NORTHING_OFFSET ? northing + LV95_NORTHING_OFFSET : northing;

        // Distances from the projection origin (Bern) in units of 1000 km.
        double y = (e - 2_600_000) / 1_000_000.0;
        double x = (n - 1_200_000) / 1_000_000.0;

        double lambda = 2.6779094
                + 4.728982 * y
                + 0.791484 * y * x
                + 0.1306 * y * x * x
                - 0.0436 * y * y * y;

        double phi = 16.9023892
                + 3.238272 * x
                - 0.270978 * y * y
                - 0.002528 * x * x
                - 0.0447 * y * y * x
                - 0.0140 * x * x * x;

        // The formulas yield units of 10000", hence the conversion to degrees.
        return new double[]{phi * 100 / 36, lambda * 100 / 36};
    }
}
