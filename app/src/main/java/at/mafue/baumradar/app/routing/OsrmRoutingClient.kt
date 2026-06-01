package at.mafue.baumradar.app.routing

import at.mafue.baumradar.app.data.GeofenceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import android.util.Log

/**
 * Ergebnis einer einzelnen berechneten Route.
 *
 * @property polylinePoints  Liste von (lat, lon)-Paaren, die den Routenverlauf beschreiben
 * @property rawGeoJson      Rohe OSRM-Antwort im GeoJSON-Format (für Debugging/Export)
 * @property durationSec     Geschätzte Reisezeit in Sekunden
 * @property distanceMeters  Gesamtlänge der Route in Metern
 * @property collisionCount  Anzahl der Allergie-Hotspots, die diese Route durchquert
 */
data class RouteResult(
    val polylinePoints: List<Pair<Double, Double>>,
    val rawGeoJson: String,
    val durationSec: Double,
    val distanceMeters: Double,
    val collisionCount: Int = 0
)

/**
 * HTTP-Client für die Kommunikation mit dem OSRM-Routing-Service (OpenStreetMap).
 *
 * Implementiert eine mehrstufige Routing-Strategie zur Allergen-Vermeidung:
 * 1. Standard-Route und bis zu 3 Alternativen von OSRM anfordern
 * 2. Kollisionen jeder Route mit den Allergie-Geofences zählen
 * 3. Falls Kollisionen existieren: Ausweich-Wegpunkte berechnen und
 *    eine Umfahrungsroute über OSRM anfordern (bis zu 2 Iterationen)
 * 4. Alle Routen deduplizieren und nach Kollisionsanzahl + Dauer sortieren
 *
 * Die OSRM-Instanz wird von openstreetmap.de gehostet (kostenlos, ohne API-Key).
 */
class OsrmRoutingClient {
    private val client = OkHttpClient()
    private val TAG = "OsrmRoutingClient"

    /**
     * Holt eine OSM Route mit intelligenter Allergen-Umfahrung.
     *
     * Strategie:
     * 1. Standard-Route + Alternativen von OSRM holen
     * 2. Kollisionen mit Geofences zählen
     * 3. Falls beste Route Kollisionen hat: Waypoint-basierte Umfahrung versuchen
     * 4. Bis zu 2 Umfahrungs-Iterationen (für kaskadierte Kollisionen)
     *
     * @param avoidAreas Liste an Geofences, die umgangen werden sollen.
     * @param profile Das OSRM Profil (foot, bike, driving).
     */
    suspend fun getRoute(
        startLat: Double, 
        startLon: Double, 
        endLat: Double, 
        endLon: Double,
        avoidAreas: List<GeofenceEntity> = emptyList(),
        profile: String = "foot"
    ): Result<List<RouteResult>> = withContext(Dispatchers.IO) {
        try {
            val baseUrl = buildProfileUrl(profile)

            // Phase 1: Standard-Routen holen (mit Alternativen wenn avoid nötig)
            val alternativesParam = if (avoidAreas.isNotEmpty()) "alternatives=3" else "alternatives=false"
            val standardUrl = "$baseUrl/$startLon,$startLat;$endLon,$endLat?geometries=geojson&overview=full&$alternativesParam"

            val standardRoutes = fetchAndParseRoutes(standardUrl, avoidAreas)
                ?: return@withContext Result.failure(IOException("No route found"))

            if (avoidAreas.isEmpty()) {
                return@withContext Result.success(standardRoutes.sortedWith(routeComparator))
            }

            val allRoutes = standardRoutes.toMutableList()

            // Phase 2: Waypoint-basierte Umfahrung (bis zu 2 Iterationen)
            var bestRoute = allRoutes.minByOrNull { it.collisionCount * 100000.0 + it.durationSec }
            
            for (iteration in 1..2) {
                if (bestRoute == null || bestRoute.collisionCount == 0) break

                Log.d(TAG, "Avoidance iteration $iteration: best route has ${bestRoute.collisionCount} collisions")

                val collidingFences = RouteCollisionDetector.findCollidingGeofences(
                    bestRoute.polylinePoints, avoidAreas
                )
                if (collidingFences.isEmpty()) break

                val detourWaypoints = RouteCollisionDetector.computeDetourWaypoints(
                    bestRoute.polylinePoints, collidingFences
                )
                if (detourWaypoints.isEmpty()) break

                // OSRM Waypoint-Route: start;wp1;wp2;...;end
                val waypointCoords = buildString {
                    append("$startLon,$startLat")
                    for ((wpLat, wpLon) in detourWaypoints) {
                        append(";$wpLon,$wpLat")
                    }
                    append(";$endLon,$endLat")
                }
                val waypointUrl = "$baseUrl/$waypointCoords?geometries=geojson&overview=full&alternatives=false"

                try {
                    val detourRoutes = fetchAndParseRoutes(waypointUrl, avoidAreas)
                    if (detourRoutes != null) {
                        allRoutes.addAll(detourRoutes)
                        
                        // Update best for next iteration
                        bestRoute = allRoutes.minByOrNull { it.collisionCount * 100000.0 + it.durationSec }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Waypoint route request failed in iteration $iteration", e)
                    // Weiter mit den bisherigen Routen
                }
            }

            // Deduplizierung: Routen mit identischer Punktanzahl, Distanz und
            // Kollisionszahl sind mit hoher Wahrscheinlichkeit identische Routen
            // aus verschiedenen Anfragen
            val uniqueRoutes = allRoutes.distinctBy { 
                "${it.polylinePoints.size}_${it.distanceMeters.toInt()}_${it.collisionCount}" 
            }

            Result.success(uniqueRoutes.sortedWith(routeComparator))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Baut die OSRM-Service-URL basierend auf dem Fortbewegungsprofil.
     *
     * Die openstreetmap.de-Instanz bietet separate Endpunkte für
     * Fußgänger, Radfahrer und Autofahrer. Trotz der Profile-Namen
     * enden alle Pfade auf `/v1/driving` – das ist kein Fehler, sondern
     * die OSRM-Konvention dieser Hosting-Instanz.
     */
    private fun buildProfileUrl(profile: String): String {
        val routeProfile = when (profile) {
            "foot" -> "routed-foot/route/v1/driving"
            "bike" -> "routed-bike/route/v1/driving"
            else -> "routed-car/route/v1/driving"
        }
        return "https://routing.openstreetmap.de/$routeProfile"
    }

    /**
     * Führt eine OSRM-Anfrage aus und parst die Ergebnisse.
     * Zählt Kollisionen falls avoidAreas vorhanden.
     */
    private fun fetchAndParseRoutes(
        url: String, 
        avoidAreas: List<GeofenceEntity>
    ): List<RouteResult>? {
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            Log.w(TAG, "OSRM error ${response.code} for URL: $url")
            return null
        }

        val bodyString = response.body?.string() ?: return null
        val root = JSONObject(bodyString)
        val routes = root.optJSONArray("routes")

        if (routes == null || routes.length() == 0) return null

        val routeResults = mutableListOf<RouteResult>()
        for (i in 0 until routes.length()) {
            val r = routes.getJSONObject(i)
            val geom = r.getJSONObject("geometry")
            val coords = geom.getJSONArray("coordinates")
            val poly = parseCoordinates(coords)
            
            val collisions = if (avoidAreas.isNotEmpty()) {
                RouteCollisionDetector.countCollisions(poly, avoidAreas)
            } else 0
            
            routeResults.add(
                RouteResult(
                    polylinePoints = poly,
                    rawGeoJson = bodyString,
                    durationSec = r.optDouble("duration", 0.0),
                    distanceMeters = r.optDouble("distance", 0.0),
                    collisionCount = collisions
                )
            )
        }
        return routeResults
    }

    /**
     * Parst die GeoJSON-Koordinaten aus der OSRM-Antwort.
     *
     * OSRM liefert Koordinaten im Format [lon, lat] (GeoJSON-Konvention),
     * die hier in die App-Konvention (lat, lon) umgewandelt werden.
     */
    private fun parseCoordinates(coordinates: org.json.JSONArray): List<Pair<Double, Double>> {
        val polyList = mutableListOf<Pair<Double, Double>>()
        for (i in 0 until coordinates.length()) {
            val point = coordinates.getJSONArray(i)
            val lon = point.getDouble(0)
            val lat = point.getDouble(1)
            polyList.add(Pair(lat, lon))
        }
        return polyList
    }

    companion object {
        /**
         * Sortiert Routen: zuerst nach Kollisionsanzahl (weniger = besser),
         * dann nach Dauer (kürzer = besser).
         */
        val routeComparator = compareBy<RouteResult>(
            { it.collisionCount },
            { it.durationSec }
        )
    }
}
