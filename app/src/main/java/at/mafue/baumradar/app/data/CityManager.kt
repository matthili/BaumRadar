package at.mafue.baumradar.app.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import at.mafue.baumradar.app.security.SignatureVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

data class CityCatalogEntry(
    val id: String,
    val name: String,
    val dbUrl: String,
    val sigUrl: String,
    val boundingBox: List<Double>? // minX, minY, maxX, maxY
)

class CityManager(private val context: Context) {
    private val client = OkHttpClient()
    private val PUBLIC_KEY_BASE64 = "MCowBQYDK2VwAyEAEb9KGg1K77SqnuTv78CTcdLyKEZd7xr1EbE4PnUF3Yc="
    private val CATALOG_URL = "https://raw.githubusercontent.com/matthili/BaumRadar/master/docs/data/catalog.json"
    private var cachedCatalog: List<CityCatalogEntry>? = null

    suspend fun getCatalog(forceRefresh: Boolean = false): List<CityCatalogEntry> = withContext(Dispatchers.IO) {
        if (!forceRefresh && cachedCatalog != null) {
            return@withContext cachedCatalog!!
        }
        val cacheBustedUrl = "$CATALOG_URL?t=${System.currentTimeMillis()}"
        val request = Request.Builder().url(cacheBustedUrl).build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            return@withContext emptyList()
        }
        val body = response.body?.string() ?: return@withContext emptyList()
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext emptyList()
        }
        val cities = json.getJSONArray("cities")
        val result = mutableListOf<CityCatalogEntry>()
        for (i in 0 until cities.length()) {
            val obj = cities.getJSONObject(i)
            val bbox = obj.optJSONArray("boundingBox")
            val boxList = if (bbox != null && bbox.length() == 4) {
                listOf(bbox.getDouble(0), bbox.getDouble(1), bbox.getDouble(2), bbox.getDouble(3))
            } else null
            
            result.add(CityCatalogEntry(
                id = obj.getString("id"),
                name = obj.getString("name"),
                dbUrl = obj.getString("dbUrl"),
                sigUrl = obj.getString("sigUrl"),
                boundingBox = boxList
            ))
        }
        result
    }

    suspend fun downloadAndMergeCity(city: CityCatalogEntry, onProgress: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        try {
            onProgress("Downloading ${city.name}...")
            val dbFile = File(context.cacheDir, "${city.id}.db")
            val sigFile = File(context.cacheDir, "${city.id}.db.sig")

            val cacheBusterSuffix = "?t=${System.currentTimeMillis()}"
            downloadFile(city.dbUrl + cacheBusterSuffix, dbFile)
            downloadFile(city.sigUrl + cacheBusterSuffix, sigFile)

            onProgress("Verifying signature...")
            if (!SignatureVerifier.verifyFile(dbFile, sigFile, PUBLIC_KEY_BASE64)) {
                dbFile.delete()
                sigFile.delete()
                return@withContext false
            }

            onProgress("Merging into your map...")
            val appDb = AppDatabase.getInstance(context)
            val helper = appDb.openHelper.writableDatabase
            
            // Delete old data for this city if any exists to prevent duplicates
            helper.execSQL("DELETE FROM trees WHERE city_id = '${city.id}'")
            
            helper.execSQL("ATTACH DATABASE '${dbFile.absolutePath}' AS new_city_db")
            helper.execSQL("INSERT INTO trees SELECT * FROM new_city_db.trees")
            helper.execSQL("DETACH DATABASE new_city_db")

            dbFile.delete()
            sigFile.delete()
            
            val prefs = context.getSharedPreferences("city_prefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("city_dn_${city.id}", true).apply()

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun deleteCity(cityId: String) = withContext(Dispatchers.IO) {
        val appDb = AppDatabase.getInstance(context)
        val helper = appDb.openHelper.writableDatabase
        helper.execSQL("DELETE FROM trees WHERE city_id = '$cityId'")
        val prefs = context.getSharedPreferences("city_prefs", Context.MODE_PRIVATE)
        prefs.edit().remove("city_dn_$cityId").apply()
    }

    fun isCityDownloaded(cityId: String): Boolean {
        val prefs = context.getSharedPreferences("city_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("city_dn_$cityId", false)
    }
    
    fun hasAnyCity(): Boolean {
        val prefs = context.getSharedPreferences("city_prefs", Context.MODE_PRIVATE)
        return prefs.all.keys.any { it.startsWith("city_dn_") }
    }

    private fun downloadFile(url: String, dest: File) {
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) throw Exception("Unexpected code $response from $url")

        val inputStream = response.body?.byteStream() ?: throw Exception("Empty body from $url")
        FileOutputStream(dest).use { outputStream ->
            inputStream.copyTo(outputStream)
        }
    }
}
