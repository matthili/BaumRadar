package at.mafue.baumradar.app.background

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.location.LocationServices
import at.mafue.baumradar.app.data.AllergyDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Observes the app's process lifecycle and re-registers geofences when
 * the app comes to the foreground.
 *
 * This covers the case where Android kills the app process (battery optimization,
 * force-stop, etc.) and all geofences are lost. When the user opens the app again,
 * geofences are silently restored.
 *
 * Energy cost: zero extra – the user is already opening the app.
 */
class GeofenceLifecycleObserver(private val context: Context) : DefaultLifecycleObserver {

    @SuppressLint("MissingPermission")
    override fun onStart(owner: LifecycleOwner) {
        // App came to foreground
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dataStore = AllergyDataStore(context)
                val warnTrees = dataStore.warnTreesFlow.first()

                if (warnTrees.isEmpty()) {
                    Log.d("GeofenceLifecycle", "No warn trees configured, skipping.")
                    return@launch
                }

                val fusedClient = LocationServices.getFusedLocationProviderClient(context)
                fusedClient.lastLocation.addOnSuccessListener { loc ->
                    if (loc != null) {
                        CoroutineScope(Dispatchers.IO).launch {
                            val manager = GeofenceManager(context)
                            manager.updateGeofences(loc)
                            Log.d("GeofenceLifecycle", "Geofences refreshed on app foreground.")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("GeofenceLifecycle", "Error refreshing geofences on foreground", e)
            }
        }
    }
}
