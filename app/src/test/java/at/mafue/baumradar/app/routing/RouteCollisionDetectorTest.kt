package at.mafue.baumradar.app.routing

import at.mafue.baumradar.app.data.GeofenceEntity
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit-Tests für [RouteCollisionDetector].
 *
 * Die Testdaten verwenden reale Wiener Koordinaten (Bereich 48.2° N / 16.37° E),
 * damit die Equirectangular-Näherung im Produktionscode realistisch geprüft wird.
 *
 * Konventionen:
 *  - "Kollision" = Route kommt näher als (Geofence-Radius + 60 m Toleranz) an den Mittelpunkt.
 *  - "Sichere Distanz" = Geofence-Radius + 100 m Puffer für Detour-Wegpunkte.
 */
class RouteCollisionDetectorTest {

    // ==================== Hilfsfunktionen ====================

    /**
     * Erzeugt eine [GeofenceEntity] mit den Pflichtfeldern.
     * [radius] in Metern, [id] ist nur für die Unterscheidbarkeit nötig.
     */
    private fun geofence(
        lat: Double,
        lon: Double,
        radius: Int = 100,
        id: String = "gf-${lat}-${lon}"
    ) = GeofenceEntity(
        id = id,
        lat = lat,
        lon = lon,
        radius = radius,
        count = 1,
        genusDe = "TestBaum"
    )

    /**
     * Erzeugt eine gerade Routenlinie zwischen zwei Punkten mit [steps] Zwischenpunkten.
     */
    private fun straightRoute(
        startLat: Double, startLon: Double,
        endLat: Double, endLon: Double,
        steps: Int = 10
    ): List<Pair<Double, Double>> {
        return (0..steps).map { i ->
            val fraction = i.toDouble() / steps
            Pair(
                startLat + (endLat - startLat) * fraction,
                startLon + (endLon - startLon) * fraction
            )
        }
    }

    // ==================== countCollisions ====================

    @Test
    fun countCollisions_routePassesThroughGeofence_returnsOne() {
        // Route verläuft geradeaus durch einen Geofence-Mittelpunkt
        val geofenceCenter = Pair(48.2050, 16.3700)
        val route = straightRoute(48.2040, 16.3700, 48.2060, 16.3700)
        val geofences = listOf(geofence(geofenceCenter.first, geofenceCenter.second, radius = 100))

        val collisions = RouteCollisionDetector.countCollisions(route, geofences)

        assertEquals("Route geht direkt durch den Geofence-Mittelpunkt", 1, collisions)
    }

    @Test
    fun countCollisions_routePassesThroughMultipleGeofences_countsAll() {
        // Zwei Geofences auf der Route, einer daneben
        val route = straightRoute(48.2000, 16.3700, 48.2100, 16.3700, steps = 50)
        val geofences = listOf(
            geofence(48.2030, 16.3700, radius = 80, id = "gf1"),   // auf der Route
            geofence(48.2070, 16.3700, radius = 80, id = "gf2"),   // auf der Route
            geofence(48.2050, 16.3900, radius = 80, id = "gf3")    // weit entfernt (~1.3 km östlich)
        )

        val collisions = RouteCollisionDetector.countCollisions(route, geofences)

        assertEquals("Zwei Geofences werden getroffen, der dritte nicht", 2, collisions)
    }

    @Test
    fun countCollisions_routeAvoidsAllGeofences_returnsZero() {
        // Route läuft 2 km nördlich an allen Geofences vorbei
        val route = straightRoute(48.2200, 16.3600, 48.2200, 16.3800)
        val geofences = listOf(
            geofence(48.2000, 16.3700, radius = 100, id = "gf1"),
            geofence(48.2010, 16.3750, radius = 50,  id = "gf2")
        )

        val collisions = RouteCollisionDetector.countCollisions(route, geofences)

        assertEquals("Keine Kollisionen bei weit entfernter Route", 0, collisions)
    }

    @Test
    fun countCollisions_routeBarelyTouchesEdge_detectsCollision() {
        // Route verläuft knapp innerhalb der Kollisionszone (radius + 60m Toleranz)
        // Geofence bei 48.2050 / 16.3700, Radius 100m
        // Kollisionszone = 160m
        // Route ca. 150m nördlich → sollte noch kollidieren (~0.00135° ≈ 150m)
        val fenceLat = 48.2050
        val fenceLon = 16.3700
        val offsetLat = 0.00135  // ~150m nördlich, innerhalb der 160m Kollisionszone

        val route = straightRoute(
            fenceLat + offsetLat, fenceLon - 0.005,
            fenceLat + offsetLat, fenceLon + 0.005
        )
        val geofences = listOf(geofence(fenceLat, fenceLon, radius = 100))

        val collisions = RouteCollisionDetector.countCollisions(route, geofences)

        assertEquals("Route bei ~150m Abstand kollidiert noch (Schwelle 160m)", 1, collisions)
    }

    @Test
    fun countCollisions_routeJustOutsideEdge_noCollision() {
        // Route verläuft knapp außerhalb der Kollisionszone
        // Geofence Radius 50m, Toleranz 60m → Schwelle 110m
        // Route ~130m entfernt → keine Kollision (~0.00117° ≈ 130m)
        val fenceLat = 48.2050
        val fenceLon = 16.3700
        val offsetLat = 0.00117  // ~130m nördlich, außerhalb der 110m Schwelle

        val route = straightRoute(
            fenceLat + offsetLat, fenceLon - 0.005,
            fenceLat + offsetLat, fenceLon + 0.005
        )
        val geofences = listOf(geofence(fenceLat, fenceLon, radius = 50))

        val collisions = RouteCollisionDetector.countCollisions(route, geofences)

        assertEquals("Route bei ~130m Abstand liegt außerhalb der 110m-Schwelle", 0, collisions)
    }

    @Test
    fun countCollisions_emptyRoute_returnsZero() {
        val geofences = listOf(geofence(48.2050, 16.3700, radius = 100))

        val collisions = RouteCollisionDetector.countCollisions(emptyList(), geofences)

        assertEquals(0, collisions)
    }

    @Test
    fun countCollisions_emptyGeofences_returnsZero() {
        val route = straightRoute(48.2000, 16.3700, 48.2100, 16.3700)

        val collisions = RouteCollisionDetector.countCollisions(route, emptyList())

        assertEquals(0, collisions)
    }

    @Test
    fun countCollisions_singlePointRoute_nearGeofence_detectsCollision() {
        // Einzelner Punkt direkt auf dem Geofence-Mittelpunkt (wird über die letzte-Punkt-Prüfung erfasst)
        val route = listOf(Pair(48.2050, 16.3700))
        val geofences = listOf(geofence(48.2050, 16.3700, radius = 100))

        val collisions = RouteCollisionDetector.countCollisions(route, geofences)

        assertEquals("Einzelpunkt auf Geofence-Zentrum muss als Kollision gelten", 1, collisions)
    }

    // ==================== findCollidingGeofences ====================

    @Test
    fun findCollidingGeofences_returnsCorrectSubset() {
        val route = straightRoute(48.2000, 16.3700, 48.2100, 16.3700, steps = 50)

        val hitFence = geofence(48.2050, 16.3700, radius = 100, id = "hit")
        val missFence = geofence(48.2050, 16.3900, radius = 50, id = "miss")  // ~1.3 km entfernt

        val result = RouteCollisionDetector.findCollidingGeofences(route, listOf(hitFence, missFence))

        assertEquals("Nur der getroffene Geofence wird zurückgegeben", 1, result.size)
        assertEquals("hit", result[0].id)
    }

    @Test
    fun findCollidingGeofences_noCollisions_returnsEmpty() {
        val route = straightRoute(48.2200, 16.3700, 48.2300, 16.3700)
        val geofences = listOf(geofence(48.2000, 16.3700, radius = 100))

        val result = RouteCollisionDetector.findCollidingGeofences(route, geofences)

        assertTrue("Keine kollidierenden Geofences erwartet", result.isEmpty())
    }

    @Test
    fun findCollidingGeofences_allCollide_returnsAll() {
        // Route durchquert beide Geofences
        val route = straightRoute(48.2000, 16.3700, 48.2100, 16.3700, steps = 50)
        val gf1 = geofence(48.2030, 16.3700, radius = 100, id = "gf1")
        val gf2 = geofence(48.2070, 16.3700, radius = 100, id = "gf2")

        val result = RouteCollisionDetector.findCollidingGeofences(route, listOf(gf1, gf2))

        assertEquals("Beide Geofences werden getroffen", 2, result.size)
    }

    // ==================== computeDetourWaypoints ====================

    @Test
    fun computeDetourWaypoints_returnsWaypointsAtSafeDistance() {
        // Route verläuft von Süd nach Nord, Geofence in der Mitte
        val route = straightRoute(48.2000, 16.3700, 48.2100, 16.3700, steps = 20)
        val fence = geofence(48.2050, 16.3700, radius = 100)
        val collidingFences = listOf(fence)

        val waypoints = RouteCollisionDetector.computeDetourWaypoints(route, collidingFences)

        assertFalse("Mindestens ein Wegpunkt muss erzeugt werden", waypoints.isEmpty())

        // Der Wegpunkt muss mindestens (radius + 100m) = 200m vom Geofence-Zentrum entfernt sein
        val safeDistance = fence.radius + 100.0
        for ((wpLat, wpLon) in waypoints) {
            val dist = approximateDistanceMeters(wpLat, wpLon, fence.lat, fence.lon)
            assertTrue(
                "Wegpunkt ($wpLat, $wpLon) muss mindestens ${safeDistance}m vom Geofence entfernt sein, " +
                    "tatsächlich: ${dist}m",
                dist >= safeDistance * 0.95  // 5% Toleranz wegen sphärischer Näherung
            )
        }
    }

    @Test
    fun computeDetourWaypoints_emptyRoute_returnsEmpty() {
        val fence = geofence(48.2050, 16.3700, radius = 100)

        val waypoints = RouteCollisionDetector.computeDetourWaypoints(emptyList(), listOf(fence))

        assertTrue("Leere Route ergibt keine Wegpunkte", waypoints.isEmpty())
    }

    @Test
    fun computeDetourWaypoints_emptyGeofences_returnsEmpty() {
        val route = straightRoute(48.2000, 16.3700, 48.2100, 16.3700)

        val waypoints = RouteCollisionDetector.computeDetourWaypoints(route, emptyList())

        assertTrue("Keine Geofences ergibt keine Wegpunkte", waypoints.isEmpty())
    }

    @Test
    fun computeDetourWaypoints_singleSegmentRoute_returnsEmpty() {
        // Nur ein Punkt (< 2 Punkte) → Bedingung routePoints.size < 2
        val route = listOf(Pair(48.2050, 16.3700))
        val fence = geofence(48.2050, 16.3700, radius = 100)

        val waypoints = RouteCollisionDetector.computeDetourWaypoints(route, listOf(fence))

        assertTrue("Route mit nur einem Punkt ergibt keine Wegpunkte", waypoints.isEmpty())
    }

    @Test
    fun computeDetourWaypoints_multipleGeofences_orderedByRoute() {
        // Route von Süd nach Nord, zwei Geofences – der südliche zuerst, dann der nördliche
        val route = straightRoute(48.2000, 16.3700, 48.2100, 16.3700, steps = 50)
        val southFence = geofence(48.2030, 16.3700, radius = 80, id = "south")
        val northFence = geofence(48.2070, 16.3700, radius = 80, id = "north")

        val waypoints = RouteCollisionDetector.computeDetourWaypoints(route, listOf(northFence, southFence))

        assertEquals("Zwei kollidierende Geofences erzeugen zwei Wegpunkte", 2, waypoints.size)

        // Die Wegpunkte müssen in Routenrichtung sortiert sein (Süd → Nord = aufsteigende Latitude)
        assertTrue(
            "Erster Wegpunkt (${waypoints[0].first}) muss südlicher sein als zweiter (${waypoints[1].first})",
            waypoints[0].first < waypoints[1].first
        )
    }

    @Test
    fun computeDetourWaypoints_waypointIsPerpendicularToRoute() {
        // Ost-West-Route: Wegpunkt muss auf der Nord- oder Südseite liegen (≈ gleiche Longitude)
        val route = straightRoute(48.2050, 16.3600, 48.2050, 16.3800, steps = 20)
        val fence = geofence(48.2050, 16.3700, radius = 100)

        val waypoints = RouteCollisionDetector.computeDetourWaypoints(route, listOf(fence))

        assertFalse("Mindestens ein Wegpunkt erwartet", waypoints.isEmpty())
        val wp = waypoints[0]

        // Senkrecht zur Ost-West-Route bedeutet: der Wegpunkt liegt nördlich oder südlich,
        // also unterscheidet sich die Latitude deutlich, die Longitude bleibt ähnlich
        val latDelta = Math.abs(wp.first - fence.lat)
        assertTrue(
            "Wegpunkt muss senkrecht zur Ost-West-Route liegen (Lat-Versatz > 0.001°), " +
                "tatsächlich: $latDelta°",
            latDelta > 0.001
        )
    }

    // ==================== Hilfsfunktion für Distanzberechnung ====================

    /**
     * Ungefähre Haversine-Distanz in Metern, nur für Testassertions.
     */
    private fun approximateDistanceMeters(
        lat1: Double, lon1: Double, lat2: Double, lon2: Double
    ): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return R * c
    }
}
