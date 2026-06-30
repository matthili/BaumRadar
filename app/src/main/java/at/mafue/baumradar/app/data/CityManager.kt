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
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream

/**
 * Beschreibt eine verfügbare Stadt im zentralen Katalog.
 *
 * Der Katalog wird von GitHub gehostet und enthält Metadaten für jede Stadt,
 * deren Baumdaten heruntergeladen werden können.
 *
 * @property id           Eindeutige Kennung (z. B. "wien")
 * @property name         Anzeigename (z. B. "Wien")
 * @property country      Ländername (Fallback: "Unbekannt")
 * @property dbUrl        URL zur komprimierten SQLite-Datenbankdatei (.db.gz)
 * @property dbUrlChunks  Optionale Liste von Chunk-URLs für große Datenbanken,
 *                        die die GitHub-Dateigrößenbeschränkung umgehen
 * @property sigUrl       URL zur Ed25519-Signaturdatei für die Integritätsprüfung
 * @property boundingBox  Geographische Begrenzung [minLat, minLon, maxLat, maxLon]
 * @property dataVersion  Inhaltsbasierter Fingerprint der Baumdaten (ID-unabhängig).
 *                        Ändert sich nur, wenn sich die Daten tatsächlich ändern;
 *                        dient der App zur Erkennung veralteter lokaler Daten.
 *                        null/leer, falls der Katalog (noch) keine Version liefert.
 */
data class CityCatalogEntry(
    val id: String,
    val name: String,
    val country: String,
    val dbUrl: String,
    val dbUrlChunks: List<String>?,
    val sigUrl: String,
    val boundingBox: List<Double>?, // minX, minY, maxX, maxY
    val dataVersion: String?
)

/**
 * Verwaltet den Download, die Verifizierung und das Einlesen von Städte-Baumdaten.
 *
 * Der Ablauf beim Hinzufügen einer neuen Stadt:
 * 1. Katalog von GitHub abrufen (JSON mit allen verfügbaren Städten)
 * 2. Komprimierte Datenbank herunterladen (ggf. in Chunks bei großen Dateien)
 * 3. Ed25519-Signatur herunterladen und die Datei kryptographisch verifizieren
 * 4. GZIP dekomprimieren
 * 5. Daten per `ATTACH DATABASE` in die Haupt-Datenbank mergen
 * 6. Temporäre Dateien löschen und Download-Status in SharedPreferences speichern
 *
 * Der Katalog wird im Speicher gecacht, um wiederholte Netzwerkzugriffe zu vermeiden.
 */
class CityManager(private val context: Context) {
    private val client = OkHttpClient()
    /** Ed25519-Public-Key (Base64-kodiert) zur Verifizierung heruntergeladener Datenbanken. */
    private val PUBLIC_KEY_BASE64 = "MCowBQYDK2VwAyEAEb9KGg1K77SqnuTv78CTcdLyKEZd7xr1EbE4PnUF3Yc="
    /** URL des zentralen Stadtkatalogs auf GitHub. */
    private val CATALOG_URL = "https://raw.githubusercontent.com/matthili/BaumRadar/master/docs/data/catalog.json"
    private var cachedCatalog: List<CityCatalogEntry>? = null

    /**
     * Lädt den Stadtkatalog vom Server und gibt die Liste verfügbarer Städte zurück.
     *
     * Ein Cache-Buster-Query-Parameter (`?t=...`) verhindert, dass CDN-Caches
     * veraltete Katalogversionen ausliefern.
     *
     * @param forceRefresh Erzwingt einen Netzwerkzugriff, auch wenn der Katalog bereits gecacht ist.
     */
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
            
            val chunkArr = obj.optJSONArray("dbUrlChunks")
            val chunksList = if (chunkArr != null) {
                val list = mutableListOf<String>()
                for (j in 0 until chunkArr.length()) list.add(chunkArr.getString(j))
                list
            } else null

            result.add(CityCatalogEntry(
                id = obj.getString("id"),
                name = obj.getString("name"),
                country = obj.optString("country", "Unbekannt"),
                dbUrl = obj.getString("dbUrl"),
                dbUrlChunks = chunksList,
                sigUrl = obj.getString("sigUrl"),
                boundingBox = boxList,
                dataVersion = obj.optString("dataVersion", "").ifEmpty { null }
            ))
        }
        result
    }

    /**
     * Lädt die Datenbank einer Stadt herunter, verifiziert die Signatur und
     * mergt die Daten in die App-Datenbank.
     *
     * Große Datenbanken werden in Chunks heruntergeladen und lokal zusammengefügt,
     * da GitHub eine Dateigrößenbeschränkung von 100 MB hat.
     *
     * @param city       Die herunterzuladende Stadt aus dem Katalog
     * @param onProgress Callback für Fortschrittstexte, die in der UI angezeigt werden
     * @return true bei Erfolg, false bei Fehler (z. B. ungültige Signatur)
     */
    suspend fun downloadAndMergeCity(city: CityCatalogEntry, onProgress: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        try {
            onProgress("Downloading ${city.name}...")
            val gzFile = File(context.cacheDir, "${city.id}.db.gz")
            val dbFile = File(context.cacheDir, "${city.id}.db")
            val sigFile = File(context.cacheDir, "${city.id}.db.gz.sig")

            val cacheBusterSuffix = "?t=${System.currentTimeMillis()}"
            if (city.dbUrlChunks != null && city.dbUrlChunks.isNotEmpty()) {
                if (gzFile.exists()) gzFile.delete()
                gzFile.createNewFile()
                for ((index, chunkUrl) in city.dbUrlChunks.withIndex()) {
                    onProgress("Downloading ${city.name} (Part ${index + 1}/${city.dbUrlChunks.size})...")
                    val chunkFile = File(context.cacheDir, "${city.id}.chunk")
                    downloadFile(chunkUrl + cacheBusterSuffix, chunkFile)
                    FileOutputStream(gzFile, true).use { out ->
                        FileInputStream(chunkFile).use { input ->
                            input.copyTo(out)
                        }
                    }
                    chunkFile.delete()
                }
            } else {
                downloadFile(city.dbUrl + cacheBusterSuffix, gzFile)
            }
            downloadFile(city.sigUrl + cacheBusterSuffix, sigFile)

            onProgress("Verifying signature...")
            if (!SignatureVerifier.verifyFile(gzFile, sigFile, PUBLIC_KEY_BASE64)) {
                gzFile.delete()
                sigFile.delete()
                return@withContext false
            }

            onProgress("Decompressing data...")
            GZIPInputStream(FileInputStream(gzFile)).use { gzipIn ->
                FileOutputStream(dbFile).use { out ->
                    gzipIn.copyTo(out)
                }
            }

            onProgress("Merging into your map...")
            val appDb = AppDatabase.getInstance(context)
            val helper = appDb.openHelper.writableDatabase
            
            // Delete old data for this city if any exists to prevent duplicates
            helper.execSQL("DELETE FROM trees WHERE city_id = '${city.id}'")
            
            // Merge-Strategie: Die heruntergeladene SQLite-Datei wird als zweite
            // Datenbank angehängt (ATTACH) und alle Zeilen per INSERT in die
            // Hauptdatenbank kopiert. Danach wird die externe DB wieder getrennt.
            helper.execSQL("ATTACH DATABASE '${dbFile.absolutePath}' AS new_city_db")
            helper.execSQL("INSERT INTO trees SELECT * FROM new_city_db.trees")
            // Geofence-Cluster mit INSERT OR REPLACE importieren, damit vorhandene
            // Cluster bei Aktualisierung überschrieben werden
            helper.execSQL("INSERT OR REPLACE INTO geofences SELECT * FROM new_city_db.geofences")
            helper.execSQL("DETACH DATABASE new_city_db")

            gzFile.delete()
            dbFile.delete()
            sigFile.delete()
            
            val prefs = context.getSharedPreferences("city_prefs", Context.MODE_PRIVATE)
            // Download-Status UND die Daten-Version merken, damit später erkannt
            // werden kann, ob auf dem Server aktualisierte Baumdaten bereitstehen.
            prefs.edit()
                .putBoolean("city_dn_${city.id}", true)
                .putString("city_ver_${city.id}", city.dataVersion ?: "")
                .apply()

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /** Entfernt alle Baumdaten einer Stadt und löscht den Download-Status. */
    suspend fun deleteCity(cityId: String) = withContext(Dispatchers.IO) {
        val appDb = AppDatabase.getInstance(context)
        val helper = appDb.openHelper.writableDatabase
        helper.execSQL("DELETE FROM trees WHERE city_id = '$cityId'")
        val prefs = context.getSharedPreferences("city_prefs", Context.MODE_PRIVATE)
        prefs.edit().remove("city_dn_$cityId").remove("city_ver_$cityId").apply()
    }

    fun isCityDownloaded(cityId: String): Boolean {
        val prefs = context.getSharedPreferences("city_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("city_dn_$cityId", false)
    }

    /**
     * Liefert die beim letzten Download gespeicherte Daten-Version einer Stadt,
     * oder null, wenn die Stadt nicht (oder vor Einführung der Versionierung) geladen wurde.
     */
    fun downloadedDataVersion(cityId: String): String? {
        val prefs = context.getSharedPreferences("city_prefs", Context.MODE_PRIVATE)
        return prefs.getString("city_ver_$cityId", null)
    }

    /**
     * Ermittelt bereits heruntergeladene Städte, deren lokale Baumdaten veraltet sind –
     * d. h. deren gespeicherte [downloadedDataVersion] von der aktuellen
     * [CityCatalogEntry.dataVersion] im Katalog abweicht.
     *
     * Städte, die vor Einführung der Versionierung geladen wurden (keine gespeicherte
     * Version), gelten als veraltet, sobald der Katalog eine Version liefert – so erhalten
     * Bestandsnutzer:innen einmalig die korrigierten Daten.
     *
     * @param catalog die aktuelle Katalogliste (typischerweise aus [getCatalog])
     * @return Liste der Städte mit veralteten Daten (leer, wenn alles aktuell ist)
     */
    fun citiesNeedingDataUpdate(catalog: List<CityCatalogEntry>): List<CityCatalogEntry> {
        return catalog.filter { city ->
            isDataStale(isCityDownloaded(city.id), downloadedDataVersion(city.id), city.dataVersion)
        }
    }

    companion object {
        /**
         * Reine Vergleichslogik für [citiesNeedingDataUpdate] (ohne Android-Context, damit testbar).
         *
         * Eine Stadt gilt als „veraltet", wenn sie heruntergeladen ist, der Katalog eine
         * nicht-leere [remoteVersion] liefert und diese von der lokal gespeicherten
         * [localVersion] abweicht. Ein `null` als [localVersion] (Stadt vor Einführung der
         * Versionierung geladen) zählt als veraltet → einmaliges Refresh für Bestandsdaten.
         */
        internal fun isDataStale(downloaded: Boolean, localVersion: String?, remoteVersion: String?): Boolean {
            if (!downloaded || remoteVersion.isNullOrEmpty()) return false
            return localVersion != remoteVersion
        }
    }
    
    /** Prüft, ob mindestens eine Stadt heruntergeladen wurde (für die Wizard-Logik). */
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
