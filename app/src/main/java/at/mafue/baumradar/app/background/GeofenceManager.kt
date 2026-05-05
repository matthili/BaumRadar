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

class GeofenceManager(private val context: Context) {

    private val geofencingClient: GeofencingClient = LocationServices.getGeofencingClient(context)
    private val db = AppDatabase.getInstance(context)
    private val dataStore = AllergyDataStore(context)

    private val geofencePendingIntent: PendingIntent by lazy {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java)
        intent.action = "at.mafue.baumradar.ACTION_GEOFENCE_EVENT"
        // FLAG_MUTABLE is recommended for Geofence PendingIntents so Play Services can add extras
        PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

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

            // Get the 99 closest tree geofences from DB (OS limit is 100 per app)
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
