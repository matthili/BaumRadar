package at.mafue.baumradar.app.background

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class GeofenceBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "at.mafue.baumradar.ACTION_GEOFENCE_EVENT") return

        val geofencingEvent = GeofencingEvent.fromIntent(intent) ?: return
        if (geofencingEvent.hasError()) {
            Log.e("GeofenceReceiver", "Error receiving geofence event: ${geofencingEvent.errorCode}")
            return
        }

        val transition = geofencingEvent.geofenceTransition
        val triggeringGeofences = geofencingEvent.triggeringGeofences ?: return

        for (geofence in triggeringGeofences) {
            val requestId = geofence.requestId
            
            if (requestId == "UPDATE_ZONE" && transition == Geofence.GEOFENCE_TRANSITION_EXIT) {
                // User has left the 2km update zone. We need to re-register the 99 closest trees.
                val loc = geofencingEvent.triggeringLocation
                if (loc != null) {
                    val pendingResult = goAsync()
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val manager = GeofenceManager(context)
                            manager.updateGeofences(loc)
                        } finally {
                            pendingResult.finish()
                        }
                    }
                }
            } else if (requestId.startsWith("TREE_") && transition == Geofence.GEOFENCE_TRANSITION_ENTER) {
                // User entered an allergenic tree zone!
                // requestId format: "TREE_{uuid}_{genusDe}" – genusDe can contain spaces/parens
                val genus = requestId.removePrefix("TREE_").substringAfter("_")
                sendNotification(context, genus)
            }
        }
    }

    private fun sendNotification(context: Context, treeName: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "baumradar_alerts"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Allergie Warnungen",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Benachrichtigt dich, wenn du dich einem allergenen Baum näherst."
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert) // Fallback icon, ideally replace with app icon
            .setContentTitle("Allergie Warnung: $treeName")
            .setContentText("Du befindest dich in der Nähe eines potenziell allergenen Baumes ($treeName).")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        // Use a semi-random ID to allow multiple distinct tree alerts
        notificationManager.notify(treeName.hashCode(), notification)
    }
}
