package at.mafue.baumradar.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ArNavigationManagerTest {

    @Test
    fun testCalculateDistance() {
        // Point A: Stephansdom, Vienna
        val latA = 48.208493
        val lonA = 16.373118

        // Point B: Schönbrunn Palace, Vienna
        val latB = 48.184865
        val lonB = 16.312240

        val distance = ArNavigationManager.calculateDistance(latA, lonA, latB, lonB)

        // Known distance between Stephansdom and Schönbrunn is ~5.2 km (5200m)
        // Adjust precision delta to 50 meters
        assertEquals(5245.0, distance, 50.0)
    }

    @Test
    fun testCalculateDistance_SamePoint() {
        val lat = 48.208493
        val lon = 16.373118
        
        val distance = ArNavigationManager.calculateDistance(lat, lon, lat, lon)
        assertEquals(0.0, distance, 0.1)
    }

    @Test
    fun testCalculateBearing_North() {
        // A point 1 degree South of another point
        val bearing = ArNavigationManager.calculateBearing(47.0, 16.0, 48.0, 16.0)
        
        // Should be exactly North (0.0 degrees)
        assertEquals(0.0, bearing, 0.1)
    }

    @Test
    fun testCalculateBearing_East() {
        // A point on Equator, moving East
        val bearing = ArNavigationManager.calculateBearing(0.0, 10.0, 0.0, 11.0)
        
        // Should be exactly East (90.0 degrees)
        assertEquals(90.0, bearing, 0.1)
    }
}
