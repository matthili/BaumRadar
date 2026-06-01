package at.mafue.baumradar.app.background

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Location
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import at.mafue.baumradar.app.data.AppDatabase
import at.mafue.baumradar.app.data.AllergyDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Verwaltet die Registrierung und Aktualisierung von Geofences beim Android-Betriebssystem.
 *
 * Android erlaubt maximal 100 Geofences pro App. Diese Klasse nutzt daher eine
 * clevere 99+1-Strategie:
 * - **99 Slots** für die nächstgelegenen allergenen Baum-Hotspots
 * - **1 Slot** für eine große "Update-Zone" (2 km Radius) um den aktuellen Standort
 *
 * Verlässt der Nutzer die Update-Zone, werden die 99 Hotspots automatisch
 * anhand des neuen Standorts neu berechnet. So ist trotz des 100er-Limits
 * eine dynamische, standortbezogene Abdeckung gewährleistet.
 */
class GeofenceManager(private val context: Context) {

    private val geofencingClient: GeofencingClient = LocationServices.getGeofencingClient(context)
    private val db = AppDatabase.getInstance(context)
    private val dataStore = AllergyDataStore(context)

    /**
     * PendingIntent, das bei jedem Geofence-Übergang ausgelöst wird.
     *
     * FLAG_MUTABLE ist erforderlich, damit die Google Play Services dem Intent
     * zusätzliche Extras (z. B. welche Geofences ausgelöst wurden) hinzufügen können.
     * FLAG_UPDATE_CURRENT stellt sicher, dass derselbe PendingIntent wiederverwendet wird.
     */
    private val geofencePendingIntent: PendingIntent by lazy {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java)
        intent.action = "at.mafue.baumradar.ACTION_GEOFENCE_EVENT"
        PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /**
     * Aktualisiert alle registrierten Geofences basierend auf dem aktuellen Standort.
     *
     * Ablauf:
     * 1. Bestehende Geofences komplett entfernen
     * 2. Die 99 nächstgelegenen allergenen Hotspots aus der Datenbank laden
     * 3. Jeden Hotspot als ENTER-Geofence registrieren (Radius + 60 m Puffer für
     *    Pollenflug-Reichweite)
     * 4. Eine UPDATE_ZONE (2 km, EXIT-Trigger) um den aktuellen Standort registrieren
     */
    @SuppressLint("MissingPermission")
    suspend fun updateGeofences(currentLocation: Location) = withContext(Dispatchers.IO) {
        try {
            val warnTrees = dataStore.warnTreesFlow.first().toList()
            
            // Remove existing geofences first
            geofencingClient.removeGeofences(geofencePendingIntent)

            if (warnTrees.isEmpty()) {
                Log.d("GeofenceManager", "No warn trees selected, cleared geofences.")
                return@withContext
            }

            // Die 99 nächsten Geofence-Cluster laden (Android-Limit ist 100 pro App,
            // Slot 100 wird für die Update-Zone reserviert)
            val closestGeofences = db.treeDao().getClosestGeofences(
                allergicGenuses = warnTrees,
                lat = currentLocation.latitude,
                lon = currentLocation.longitude,
                limit = 99
            )

            val geofenceList = mutableListOf<Geofence>()

            // 1. Add Tree Geofences
            for (fence in closestGeofences) {
                geofenceList.add(
                    Geofence.Builder()
                        .setRequestId("TREE_${fence.id}_${fence.genusDe}")
                        // Radius + 60 m Sicherheitspuffer: Pollen können bei Wind
                        // auch über den unmittelbaren Baumbereich hinaus wirken
                        .setCircularRegion(fence.lat, fence.lon, fence.radius.toFloat() + 60f)
                        .setExpirationDuration(Geofence.NEVER_EXPIRE)
                        .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER) // Only warn on enter
                        .build()
                )
            }

            // 2. Add "Update Zone" Geofence (100th slot)
            // When the user leaves this large zone, we trigger an update for the next 99 closest trees.
            geofenceList.add(
                Geofence.Builder()
                    .setRequestId("UPDATE_ZONE")
                    .setCircularRegion(currentLocation.latitude, currentLocation.longitude, 2000f) // 2km radius
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT) // Trigger when leaving
                    .build()
            )

            if (geofenceList.isNotEmpty()) {
                val geofencingRequest = GeofencingRequest.Builder()
                    .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
                    .addGeofences(geofenceList)
                    .build()

                geofencingClient.addGeofences(geofencingRequest, geofencePendingIntent)
                    .addOnSuccessListener {
                        Log.d("GeofenceManager", "Successfully registered ${geofenceList.size} geofences.")
                    }
                    .addOnFailureListener {
                        Log.e("GeofenceManager", "Failed to register geofences.", it)
                    }
            }
        } catch (e: Exception) {
            Log.e("GeofenceManager", "Error updating geofences", e)
        }
    }
}
