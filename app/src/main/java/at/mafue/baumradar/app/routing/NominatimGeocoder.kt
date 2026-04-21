package at.mafue.baumradar.app.routing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.osmdroid.util.GeoPoint
import java.io.IOException

object NominatimGeocoder {
    private val client = OkHttpClient()
    private const val BASE_URL = "https://nominatim.openstreetmap.org/search?format=json&limit=1&q="

    suspend fun getCoordinates(address: String): Result<GeoPoint> = withContext(Dispatchers.IO) {
        if (address.isBlank()) return@withContext Result.failure(Exception("Empty address"))
        try {
            val url = BASE_URL + java.net.URLEncoder.encode(address, "UTF-8")
            val request = Request.Builder()
                .url(url)
                // Nominatim requires a user agent
                .header("User-Agent", "BaumRadar/1.0")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(IOException("Nominatim error ${response.code}"))
            }

            val bodyString = response.body?.string() ?: return@withContext Result.failure(IOException("Empty body"))
            val rootArray = JSONArray(bodyString)
            
            if (rootArray.length() == 0) {
                return@withContext Result.failure(Exception("Adresse nicht gefunden."))
            }

            val firstResult = rootArray.getJSONObject(0)
            val lat = firstResult.optDouble("lat")
            val lon = firstResult.optDouble("lon")
            
            if (lat.isNaN() || lon.isNaN()) {
                val strLat = firstResult.getString("lat")
                val strLon = firstResult.getString("lon")
                Result.success(GeoPoint(strLat.toDouble(), strLon.toDouble()))
            } else {
                Result.success(GeoPoint(lat, lon))
            }
            
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
