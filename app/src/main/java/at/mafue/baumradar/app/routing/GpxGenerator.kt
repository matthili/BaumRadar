package at.mafue.baumradar.app.routing

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object GpxGenerator {

    fun generateGpxString(route: RouteResult): String {
        val sb = java.lang.StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"BaumRadar\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("  <metadata>\n")
        sb.append("    <name>BaumRadar Allergie-Route</name>\n")
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())
        sb.append("    <time>$timestamp</time>\n")
        sb.append("  </metadata>\n")
        sb.append("  <trk>\n")
        sb.append("    <name>Sichere Route</name>\n")
        sb.append("    <trkseg>\n")
        
        for (point in route.polylinePoints) {
            sb.append("      <trkpt lat=\"${point.first}\" lon=\"${point.second}\"></trkpt>\n")
        }
        
        sb.append("    </trkseg>\n")
        sb.append("  </trk>\n")
        sb.append("</gpx>\n")
        return sb.toString()
    }

    fun shareGpxRoute(context: Context, route: RouteResult, textDesc: String) {
        val gpxData = generateGpxString(route)
        
        // Write to cache dir
        val cachePath = File(context.cacheDir, "routes")
        cachePath.mkdir()
        val gpxFile = File(cachePath, "allergie_route.gpx")
        gpxFile.writeText(gpxData)

        // Generate Content URI
        val contentUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", gpxFile)

        val shareIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_STREAM, contentUri)
            putExtra(Intent.EXTRA_TEXT, textDesc)
            type = "application/gpx+xml"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(Intent.createChooser(shareIntent, "Route teilen mit..."))
    }
}
