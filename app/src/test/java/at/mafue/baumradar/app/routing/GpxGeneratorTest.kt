package at.mafue.baumradar.app.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date
import java.util.TimeZone

/**
 * Tests the pure GPX serialization ([GpxGenerator.generateGpxString]); the
 * share path ([GpxGenerator.shareGpxRoute]) is framework-coupled and excluded.
 */
class GpxGeneratorTest {

    private fun route(points: List<Pair<Double, Double>>) =
        RouteResult(
            polylinePoints = points,
            rawGeoJson = "",
            durationSec = 0.0,
            distanceMeters = 0.0
        )

    @Test
    fun producesWellFormedGpxWithOneTrkptPerPoint() {
        val gpx = GpxGenerator.generateGpxString(route(listOf(48.2 to 16.37, 48.21 to 16.38)))

        assertTrue(gpx.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertTrue(gpx.contains("<gpx version=\"1.1\""))
        assertTrue(gpx.contains("xmlns=\"http://www.topografix.com/GPX/1/1\""))
        assertTrue(gpx.contains("<trkpt lat=\"48.2\" lon=\"16.37\">"))
        assertTrue(gpx.contains("<trkpt lat=\"48.21\" lon=\"16.38\">"))
        assertTrue(gpx.trim().endsWith("</gpx>"))
        assertEquals(2, Regex("<trkpt ").findAll(gpx).count())
    }

    @Test
    fun emptyRouteStillProducesValidSkeleton() {
        val gpx = GpxGenerator.generateGpxString(route(emptyList()))
        assertTrue(gpx.contains("<trkseg>"))
        assertTrue(gpx.contains("</trkseg>"))
        assertEquals(0, Regex("<trkpt ").findAll(gpx).count())
    }

    @Test
    fun timeIsWrittenInUtcRegardlessOfDeviceTimeZone() {
        val originalTz = TimeZone.getDefault()
        try {
            // Gerät in einer Nicht-UTC-Zone simulieren (New York = UTC-5/-4).
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            // Epoch 0 ist exakt 1970-01-01T00:00:00 in UTC.
            val gpx = GpxGenerator.generateGpxString(route(listOf(48.2 to 16.37)), Date(0L))
            // Bei korrektem UTC-Bezug muss genau diese Zeit dastehen – NICHT die lokale
            // (die wäre 1969-12-31T19:00:00, fälschlich als 'Z' ausgezeichnet).
            assertTrue(
                "Zeitstempel muss in UTC formatiert sein",
                gpx.contains("<time>1970-01-01T00:00:00Z</time>")
            )
        } finally {
            TimeZone.setDefault(originalTz)
        }
    }

    @Test
    fun declaresGpx11SchemaLocation() {
        val gpx = GpxGenerator.generateGpxString(route(listOf(48.2 to 16.37)))
        assertTrue(gpx.contains("xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\""))
        assertTrue(
            gpx.contains(
                "xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 " +
                    "http://www.topografix.com/GPX/1/1/gpx.xsd\""
            )
        )
    }
}
