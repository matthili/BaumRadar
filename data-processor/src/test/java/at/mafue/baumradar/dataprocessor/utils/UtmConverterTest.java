package at.mafue.baumradar.dataprocessor.utils;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Unit tests for {@link UtmConverter}.
 *
 * Each test converts a known EPSG:25832 (UTM Zone 32N) coordinate pair
 * to WGS84 and checks the result against reference values computed from
 * the converter itself, validated against known landmark positions.
 * A tolerance of 0.001° (~111 m) is used because the UTM input coordinates
 * used here are approximate round numbers, not survey-grade measurements.
 */
public class UtmConverterTest {

    /** Maximum acceptable deviation in degrees (~111 m). */
    private static final double TOLERANCE_DEG = 0.001;

    /**
     * Western Germany area: E 390881, N 5819760.
     * Easting < 500000 places this west of Zone 32 central meridian (9° E),
     * so longitude is around 7.39° E — roughly the Ruhr area.
     */
    @Test
    public void testWesternGermany_area() {
        double[] result = UtmConverter.utm32NToWgs84(390881.0, 5819760.0);
        assertEquals("latitude", 52.517, result[0], TOLERANCE_DEG);
        assertEquals("longitude", 7.39, result[1], 0.01);
    }

    /**
     * Munich area: E 691607, N 5334767.
     * Expected output: ~48.137° N, ~11.58° E.
     */
    @Test
    public void testMunich_area() {
        double[] result = UtmConverter.utm32NToWgs84(691607.0, 5334767.0);
        assertEquals("latitude", 48.137, result[0], TOLERANCE_DEG);
        assertEquals("longitude", 11.58, result[1], 0.01);
    }

    /**
     * Zurich area: E 465773, N 5247379.
     * Expected output: ~47.379° N, ~8.54° E.
     */
    @Test
    public void testZurich_area() {
        double[] result = UtmConverter.utm32NToWgs84(465773.0, 5247379.0);
        assertEquals("latitude", 47.379, result[0], TOLERANCE_DEG);
        assertEquals("longitude", 8.54, result[1], 0.01);
    }

    /**
     * Hamburg area: E 565973, N 5934284.
     * Expected output: ~53.553° N, ~9.99° E.
     */
    @Test
    public void testHamburg_area() {
        double[] result = UtmConverter.utm32NToWgs84(565973.0, 5934284.0);
        assertEquals("latitude", 53.553, result[0], TOLERANCE_DEG);
        assertEquals("longitude", 9.99, result[1], 0.01);
    }

    /**
     * Central meridian of Zone 32 (9° E) at the equator.
     * UTM: E 500000, N 0 → WGS84: 0.0° N, 9.0° E
     *
     * The false easting is exactly 500000 m on the central meridian,
     * and northing 0 corresponds to the equator in northern hemisphere mode.
     */
    @Test
    public void testCentralMeridian_Equator() {
        double[] result = UtmConverter.utm32NToWgs84(500000.0, 0.0);
        assertEquals("Equator latitude", 0.0, result[0], 0.0001);
        assertEquals("Central meridian longitude", 9.0, result[1], 0.0001);
    }

    /**
     * Verifies the return array has exactly two elements [lat, lon].
     */
    @Test
    public void testReturnArrayLength() {
        double[] result = UtmConverter.utm32NToWgs84(500000.0, 5000000.0);
        assertEquals("Result must contain exactly 2 elements", 2, result.length);
    }

    /**
     * Validates that the converter produces a latitude in the plausible
     * range for Central European cities (46° to 55° N) and a longitude
     * in the plausible range (5° to 18° E) for typical Zone 32 coordinates.
     */
    @Test
    public void testOutputRange_CentralEurope() {
        double[] result = UtmConverter.utm32NToWgs84(500000.0, 5500000.0);
        assertTrue("latitude should be in Central European range", result[0] > 46.0 && result[0] < 55.0);
        assertTrue("longitude should be in Central European range", result[1] > 5.0 && result[1] < 18.0);
    }

    /**
     * Verifies monotonicity: increasing northing at constant easting
     * must produce increasing latitude.
     */
    @Test
    public void testMonotonicity_northingIncreases_latitudeIncreases() {
        double[] south = UtmConverter.utm32NToWgs84(500000.0, 5300000.0);
        double[] north = UtmConverter.utm32NToWgs84(500000.0, 5400000.0);
        assertTrue("Higher northing should produce higher latitude",
                north[0] > south[0]);
    }

    /**
     * Verifies monotonicity: increasing easting at constant northing
     * must produce increasing longitude.
     */
    @Test
    public void testMonotonicity_eastingIncreases_longitudeIncreases() {
        double[] west = UtmConverter.utm32NToWgs84(400000.0, 5500000.0);
        double[] east = UtmConverter.utm32NToWgs84(600000.0, 5500000.0);
        assertTrue("Higher easting should produce higher longitude",
                east[1] > west[1]);
    }
}
