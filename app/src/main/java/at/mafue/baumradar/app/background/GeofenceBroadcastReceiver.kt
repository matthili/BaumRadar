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

/**
 * BroadcastReceiver, der Geofence-Übergänge vom Android-Betriebssystem empfängt.
 *
 * Behandelt zwei Arten von Geofence-Ereignissen:
 * - **TREE_*-ENTER**: Der Nutzer betritt den Bereich eines allergenen Baum-Hotspots.
 *   Es wird eine Push-Benachrichtigung mit dem Gattungsnamen gesendet.
 * - **UPDATE_ZONE-EXIT**: Der Nutzer verlässt die 2-km-Update-Zone.
 *   Die 99 nächsten Geofences werden anhand des neuen Standorts neu registriert.
 *
 * `goAsync()` wird verwendet, um die asynchrone Geofence-Neuregistrierung
 * abzuschließen, bevor das System den Receiver beendet (BroadcastReceiver
 * haben standardmäßig nur ca. 10 Sekunden Laufzeit).
 */
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
                // Format der Request-ID: "TREE_{uuid}_{genusDe}"
                // Der Gattungsname wird nach dem zweiten Unterstrich extrahiert
                // und kann Leerzeichen/Klammern enthalten (z. B. "Acer (Ahorn)")
                val genus = requestId.removePrefix("TREE_").substringAfter("_")
                sendNotification(context, genus)
            }
        }
    }

    /**
     * Sendet eine lokale Push-Benachrichtigung über einen allergenen Baum in der Nähe.
     *
     * Ab Android O (API 26) ist ein NotificationChannel Pflicht. Der Channel
     * wird bei jedem Aufruf erstellt, was idempotent ist (Android ignoriert
     * doppelte Channel-Registrierungen).
     */
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

        // Notification-ID auf Basis des Baumnamens-Hashcodes: Damit können
        // mehrere verschiedene Baum-Warnungen gleichzeitig sichtbar sein,
        // ohne sich gegenseitig zu überschreiben
        notificationManager.notify(treeName.hashCode(), notification)
    }
}
