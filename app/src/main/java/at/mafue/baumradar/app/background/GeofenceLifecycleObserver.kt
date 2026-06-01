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
 * Beobachtet den App-Lebenszyklus und registriert Geofences neu, wenn die App
 * in den Vordergrund kommt.
 *
 * Hintergrund: Android kann den App-Prozess jederzeit beenden (Batterie-Optimierung,
 * Force-Stop, etc.), wobei alle registrierten Geofences verloren gehen. Dieser Observer
 * stellt sie beim nächsten Öffnen der App automatisch wieder her.
 *
 * Wird an die Activity-Lifecycle gebunden (nicht an ProcessLifecycleOwner), da
 * nur eine einzige Activity existiert.
 *
 * Energiekosten: Null – der Nutzer hat die App bereits geöffnet, es wird nur
 * der zuletzt bekannte Standort (lastLocation) verwendet, kein neuer GPS-Fix angefordert.
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
