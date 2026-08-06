package at.mafue.baumradar.dataprocessor.utils;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for the LV95 → WGS-84 conversion, checked against reference points
 * whose true position is known independently.
 */
public class SwissConverterTest {

    /** swisstopo's own reference point: the Zimmerwald observatory south of Bern. */
    @Test
    public void convertsSwisstopoReferencePoint() {
        double[] latlon = SwissConverter.lv95ToWgs84(2_602_030.74, 1_191_775.03);
        assertEquals(46.8771, latlon[0], 0.001);
        assertEquals(7.4653, latlon[1], 0.001);
    }

    /** Second anchor in the flat part of the country: Bern's Bundeshaus. */
    @Test
    public void convertsBernFederalPalace() {
        double[] latlon = SwissConverter.lv95ToWgs84(2_600_325, 1_199_885);
        assertEquals(46.9500, latlon[0], 0.005);
        assertEquals(7.4429, latlon[1], 0.005);
    }

    /** A real Winterthur tree — must land in Winterthur, not in the Alps. */
    @Test
    public void convertsWinterthurTreePosition() {
        double[] latlon = SwissConverter.lv95ToWgs84(2_697_749.048, 1_260_793.062);
        assertEquals(47.4906, latlon[0], 0.002);
        assertEquals(8.7358, latlon[1], 0.002);
    }

    /** The older LV03 frame (600k/200k origin) is shifted onto LV95 automatically. */
    @Test
    public void acceptsLegacyLv03Coordinates() {
        double[] lv95 = SwissConverter.lv95ToWgs84(2_697_749.048, 1_260_793.062);
        double[] lv03 = SwissConverter.lv95ToWgs84(697_749.048, 260_793.062);
        assertEquals(lv95[0], lv03[0], 1e-9);
        assertEquals(lv95[1], lv03[1], 1e-9);
    }
}
