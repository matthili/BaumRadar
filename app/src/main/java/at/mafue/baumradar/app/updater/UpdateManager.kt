package at.mafue.baumradar.app.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Ergebnis einer Update-Prüfung gegen die GitHub Releases API.
 *
 * @property versionName  Versionsname des neuesten Release (z. B. "1.2.0")
 * @property downloadUrl  Direkte Download-URL des APK-Assets
 * @property releaseNotes Changelog / Release-Beschreibung aus GitHub
 */
data class UpdateInfo(
    val versionName: String,
    val downloadUrl: String,
    val releaseNotes: String
)

/**
 * Verwaltet den Self-Update-Mechanismus über GitHub Releases.
 *
 * Die Klasse führt folgende Schritte durch:
 * 1. Abfrage der GitHub API (`/repos/.../releases/latest`) nach dem neuesten Release
 * 2. Vergleich der Release-Version mit der aktuellen App-Version ([currentVersionName])
 * 3. Download des APK-Assets in den Cache
 * 4. Start der Android-Paketinstallation über [FileProvider] und `ACTION_VIEW`
 *
 * Falls die Berechtigung „Unbekannte Quellen" für diese App nicht erteilt wurde,
 * leitet [openInstallPermissionSettings] den Nutzer in die Android-Einstellungen.
 *
 * @param context            Application-Context für Dateizugriff und Intent-Start
 * @param currentVersionName Aktuelle App-Version (typischerweise aus `BuildConfig.VERSION_NAME`)
 */
class UpdateManager(
    private val context: Context,
    private val currentVersionName: String
) {
    companion object {
        private const val GITHUB_API_URL =
            "https://api.github.com/repos/matthili/BaumRadar/releases/latest"

        /**
         * Entfernt ein optionales führendes „v"/„V" (case-insensitive) aus einem
         * Versions-Tag. Aus „V1.5" oder „v1.5" wird „1.5"; „1.5" bleibt unverändert.
         *
         * Wichtig: Die Release-Tags dieses Projekts nutzen ein GROSSES „V" (z. B. „V1.5").
         * Ein früheres `removePrefix("v")` entfernte nur das kleine „v", wodurch „V1.5"
         * zu [5] geparst wurde und fälschlich als neuer galt (Endlos-Update-Schleife).
         */
        internal fun normalizeVersion(raw: String): String =
            raw.trim().let { if (it.startsWith("v", ignoreCase = true)) it.substring(1) else it }

        /**
         * Vergleicht zwei Versionsangaben (Release-Tag oder reine Version) im
         * Semantic-Versioning-Format (Major.Minor.Patch). Führende „v"/„V"-Präfixe
         * werden entfernt, fehlende Teile als 0 behandelt.
         *
         * Beispiel: „V1.5" vs „1.1.1" → [1,5] vs [1,1,1] → true (1.5 ist neuer).
         * „V1.5" vs „1.5" → gleich → false (kein Update).
         */
        internal fun isRemoteNewer(remoteTag: String, current: String): Boolean {
            val remoteParts = normalizeVersion(remoteTag).split(".").mapNotNull { it.toIntOrNull() }
            val currentParts = normalizeVersion(current).split(".").mapNotNull { it.toIntOrNull() }
            val maxLen = maxOf(remoteParts.size, currentParts.size)
            for (i in 0 until maxLen) {
                val r = remoteParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (r > c) return true
                if (r < c) return false
            }
            return false
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Prüft, ob auf GitHub ein neueres Release als die aktuelle App-Version existiert.
     *
     * Der Versionsvergleich basiert auf Semantic Versioning (Major.Minor.Patch).
     * Ein optionales „v"/„V"-Präfix im Tag-Namen wird automatisch entfernt.
     *
     * @return [UpdateInfo] mit Download-URL und Release-Notes, oder null wenn kein Update
     *         verfügbar ist oder ein Fehler auftrat (z. B. kein Netzwerk).
     */
    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(GITHUB_API_URL)
                .header("Accept", "application/vnd.github.v3+json")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val body = response.body?.string() ?: return@withContext null
            val json = JSONObject(body)

            val tagName = json.getString("tag_name")
            // Tag-Format: "v1.2.0", "V1.2.0" oder "1.2.0"
            val remoteVersion = normalizeVersion(tagName)
            val releaseNotes = json.optString("body", "Keine Details verfügbar.")

            // APK-Asset aus der Asset-Liste finden
            val assets = json.getJSONArray("assets")
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.getString("name")
                if (name.endsWith(".apk")) {
                    apkUrl = asset.getString("browser_download_url")
                    break
                }
            }

            if (apkUrl == null) return@withContext null

            if (isRemoteNewer(tagName, currentVersionName)) {
                UpdateInfo(
                    versionName = remoteVersion,
                    downloadUrl = apkUrl,
                    releaseNotes = releaseNotes
                )
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Lädt die APK-Datei von der angegebenen URL herunter.
     *
     * Die Datei wird im App-Cache unter `updates/BaumRadar-update.apk` gespeichert,
     * damit sie vom [FileProvider] an den Paketinstaller weitergegeben werden kann.
     *
     * @param url        Direkte Download-URL des APK-Assets auf GitHub
     * @param onProgress Callback mit dem Fortschritt in Prozent (0–100),
     *                   oder -1 falls die Gesamtgröße nicht bekannt ist
     * @return Die heruntergeladene APK-Datei, oder null bei einem Fehler
     */
    suspend fun downloadApk(url: String, onProgress: (Int) -> Unit): File? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val responseBody = response.body ?: return@withContext null
            val totalBytes = responseBody.contentLength()

            val updateDir = File(context.cacheDir, "updates")
            if (!updateDir.exists()) updateDir.mkdirs()
            val apkFile = File(updateDir, "BaumRadar-update.apk")

            responseBody.byteStream().use { input ->
                FileOutputStream(apkFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Long = 0
                    var lastReportedProgress = -1
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesRead += read
                        if (totalBytes > 0) {
                            val progress = ((bytesRead * 100) / totalBytes).toInt()
                            if (progress != lastReportedProgress) {
                                lastReportedProgress = progress
                                onProgress(progress)
                            }
                        } else {
                            onProgress(-1)
                        }
                    }
                }
            }

            apkFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Startet den Android-Paketinstaller mit der heruntergeladenen APK.
     *
     * Der [FileProvider] gewährt dem Installer temporäre Leserechte auf die Datei.
     * Ab Android 7.0 (Nougat) ist dies Pflicht – direkte `file://`-URIs sind verboten.
     */
    fun installApk(apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * Prüft, ob die App die Berechtigung hat, Pakete aus unbekannten Quellen zu installieren.
     *
     * Ab Android 8.0 (Oreo) muss jede App diese Berechtigung individuell anfordern.
     * Auf älteren Versionen ist eine globale Einstellung aktiv und diese Methode
     * gibt immer `true` zurück.
     */
    fun canInstallPackages(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /**
     * Öffnet die Android-Einstellungen, in denen der Nutzer die Berechtigung
     * „Unbekannte Quellen" für diese App erteilen kann.
     *
     * Die Methode springt direkt zur App-spezifischen Einstellungsseite,
     * nicht zur globalen Sicherheitseinstellung.
     */
    fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
