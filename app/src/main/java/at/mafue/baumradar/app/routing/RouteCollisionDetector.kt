package at.mafue.baumradar.app.routing

import at.mafue.baumradar.app.data.GeofenceEntity
import kotlin.math.*

object RouteCollisionDetector {
    private const val EARTH_RADIUS_METERS = 6371000.0

    /**
     * Zählt, mit wie vielen Geofences (Hotspots) die berechnete Route kollidiert.
     */
    fun countCollisions(routePoints: List<Pair<Double, Double>>, geofences: List<GeofenceEntity>): Int {
        var collisionCount = 0
        
        for (fence in geofences) {
            if (isGeofenceHit(routePoints, fence)) {
                collisionCount++
            }
        }
        return collisionCount
    }

    /**
     * Prüft, ob irgendein Segment der Route den Geofence-Radius schneidet.
     */
    private fun isGeofenceHit(routePoints: List<Pair<Double, Double>>, fence: GeofenceEntity): Boolean {
        if (routePoints.isEmpty()) return false
        
        for (i in 0 until routePoints.size - 1) {
            val p1 = routePoints[i]
            val p2 = routePoints[i + 1]
            
            // Einfache Heuristik: Abstand zwischen Mittelpunkt und Liniensegment
            val dist = pointToLineDistance(fence.lat, fence.lon, p1.first, p1.second, p2.first, p2.second)
            // Füge 60m Toleranz hinzu, da man auch in über 50m Entfernung allergisch reagieren kann
            if (dist <= fence.radius + 60.0) {
                return true
            }
        }
        // Auch den letzten Punkt separat prüfen
        val lastPoint = routePoints.last()
        val distToLast = haversineDistance(fence.lat, fence.lon, lastPoint.first, lastPoint.second)
        if (distToLast <= fence.radius + 60.0) {
            return true
        }

        return false
    }

    /**
     * Berechnet die minimale Distanz eines Punktes (ptLat/ptLon) zu einem Liniensegment (A, B).
     * Näherung für sehr kleine Distanzen auf einer Sphäre (nutzt equirectangulare Projektion lokal).
     */
    private fun pointToLineDistance(
        ptLat: Double, ptLon: Double,
        aLat: Double, aLon: Double,
        bLat: Double, bLon: Double
    ): Double {
        // Konvertiere alles in ein metrisches lokales Grid bezogen auf ptLat/ptLon
        val dLatA = Math.toRadians(aLat - ptLat) * EARTH_RADIUS_METERS
        val dLonA = Math.toRadians(aLon - ptLon) * EARTH_RADIUS_METERS * cos(Math.toRadians(ptLat))
        
        val dLatB = Math.toRadians(bLat - ptLat) * EARTH_RADIUS_METERS
        val dLonB = Math.toRadians(bLon - ptLon) * EARTH_RADIUS_METERS * cos(Math.toRadians(ptLat))

        // P ist im lokalen Grid (0,0)
        // Wir suchen die Distanz von (0,0) zur Linie (dLonA, dLatA) nach (dLonB, dLatB)
        val x1 = dLonA
        val y1 = dLatA
        val x2 = dLonB
        val y2 = dLatB

        val dx = x2 - x1
        val dy = y2 - y1
        val lengthSquared = dx * dx + dy * dy

        if (lengthSquared == 0.0) {
            // A und B sind derselbe Punkt
            return sqrt(x1 * x1 + y1 * y1)
        }

        // Projektion von (0,0) auf die Linie
        // t = dot((0-x1, 0-y1), (dx, dy)) / lengthSquared
        var t = (-x1 * dx - y1 * dy) / lengthSquared
        t = max(0.0, min(1.0, t)) // Beschneiden auf das Segment [0, 1]

        val projX = x1 + t * dx
        val projY = y1 + t * dy

        return sqrt(projX * projX + projY * projY)
    }

    private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_METERS * c
    }
}
