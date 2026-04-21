package at.mafue.baumradar.app.routing

import at.mafue.baumradar.app.data.GeofenceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

data class RouteResult(
    val polylinePoints: List<Pair<Double, Double>>,
    val rawGeoJson: String,
    val durationSec: Double,
    val distanceMeters: Double,
    val collisionCount: Int = 0
)

class OsrmRoutingClient {
    private val client = OkHttpClient()

    /**
     * Holt eine OSM Route.
     * @param avoidAreas Optional: Eine Liste an Geofences, die komplett umgangen werden sollen.
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
            val routeProfileUrl = when (profile) {
                "foot" -> "routed-foot/route/v1/driving"
                "bike" -> "routed-bike/route/v1/driving"
                else -> "routed-car/route/v1/driving"
            }
            val baseUrl = "https://routing.openstreetmap.de/$routeProfileUrl"
            // OSRM Format: lon,lat
            var url = "$baseUrl/$startLon,$startLat;$endLon,$endLat?geometries=geojson&overview=full&alternatives=false"
            
            // Note: Public OSRM doesn't easily support dynamic 'avoid_polygons' with high precision in URL freely.
            // Some specialized instances do. For OSRM, you typically post custom weights or use GraphHopper.
            // As a fallback for the public OSRM API, we compute intersection locally and rank the alternative routes.
            // So we request 'alternatives=true' to get possible bypasses if we have areas to avoid.
            if (avoidAreas.isNotEmpty()) {
                url = "$baseUrl/$startLon,$startLat;$endLon,$endLat?geometries=geojson&overview=full&alternatives=3"
            }

            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext Result.failure(IOException("OSRM error ${response.code}"))
            }

            val bodyString = response.body?.string() ?: return@withContext Result.failure(IOException("Empty body"))
            val root = JSONObject(bodyString)
            val routes = root.optJSONArray("routes")

            if (routes == null || routes.length() == 0) {
                return@withContext Result.failure(IOException("No route found"))
            }

            // Evaluate all routes
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
                        rawGeoJson = bodyString, // Or specific feature
                        durationSec = r.optDouble("duration", 0.0),
                        distanceMeters = r.optDouble("distance", 0.0),
                        collisionCount = collisions
                    )
                )
            }

            // Sort by collisions first, then by duration
            routeResults.sortBy { it.collisionCount * 100000.0 + it.durationSec }

            Result.success(routeResults)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

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
}
