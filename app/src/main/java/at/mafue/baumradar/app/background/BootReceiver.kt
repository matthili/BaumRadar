package at.mafue.baumradar.app.background

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import at.mafue.baumradar.app.data.AllergyDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Re-registers geofences after the device reboots.
 * Android removes all geofences on reboot, so this receiver restores them
 * using the user's saved allergy profile.
 *
 * This is zero-cost at runtime: it only fires once per boot cycle.
 */
class BootReceiver : BroadcastReceiver() {

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Log.d("BootReceiver", "Device booted – checking if geofences need re-registering")

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dataStore = AllergyDataStore(context)
                val warnTrees = dataStore.warnTreesFlow.first()

                if (warnTrees.isEmpty()) {
                    Log.d("BootReceiver", "No warn trees configured, skipping geofence registration.")
                    return@launch
                }

                val fusedClient = LocationServices.getFusedLocationProviderClient(context)
                // Use lastLocation first (cached, no GPS wakeup)
                val lastLoc = com.google.android.gms.tasks.Tasks.await(fusedClient.lastLocation)
                val loc = lastLoc ?: run {
                    // Fallback: get a fresh low-power location
                    Log.d("BootReceiver", "No cached location, requesting fresh low-power fix.")
                    val cts = CancellationTokenSource()
                    com.google.android.gms.tasks.Tasks.await(
                        fusedClient.getCurrentLocation(Priority.PRIORITY_LOW_POWER, cts.token)
                    )
                }

                if (loc != null) {
                    val manager = GeofenceManager(context)
                    manager.updateGeofences(loc)
                    Log.d("BootReceiver", "Geofences re-registered after boot at ${loc.latitude}, ${loc.longitude}")
                } else {
                    Log.w("BootReceiver", "Could not obtain location after boot. Geofences not registered.")
                }
            } catch (e: Exception) {
                Log.e("BootReceiver", "Error re-registering geofences after boot", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
