package at.mafue.baumradar.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import at.mafue.baumradar.app.data.AllergyDataStore
import at.mafue.baumradar.app.data.AppDatabase
import at.mafue.baumradar.app.data.TreeEntity
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MapViewModel(application: Application) : AndroidViewModel(application) {
    
    val arManager = ArNavigationManager(application)
    private val db = AppDatabase.getInstance(application)
    private val ds = AllergyDataStore(application)

    private val SEARCH_RADIUS_METERS = 500.0
    private val EXPLORE_RADIUS_METERS = 100.0

    val isMapLoading = MutableStateFlow(true)
    val isExplorationMode = MutableStateFlow(false)

    // Combine location, selected allergies, and mode to query the database
    val nearbyAllergicTrees: StateFlow<List<TreeEntity>> = combine(
        arManager.currentLocation.filterNotNull().sample(2000),
        ds.selectedTreesFlow,
        isExplorationMode
    ) { loc, selectedTrees, mode ->
        Triple(loc, selectedTrees, mode)
    }
    .onEach { 
        // Trigger loading state whenever an input changes
        isMapLoading.value = true 
    }
    .flatMapLatest { (loc, selectedSet, mode) ->
        if (!mode && selectedSet.isEmpty()) {
            flowOf(emptyList())
        } else {
            val radius = if (mode) EXPLORE_RADIUS_METERS else SEARCH_RADIUS_METERS
            val latDelta = (radius / 111000.0)
            val lonDelta = (radius / (111000.0 * Math.cos(Math.toRadians(loc.latitude))))
            
            db.treeDao().getTreesInBoundingBox(
                minLat = loc.latitude - latDelta,
                maxLat = loc.latitude + latDelta,
                minLon = loc.longitude - lonDelta,
                maxLon = loc.longitude + lonDelta
            ).map { trees ->
                // Filter accurately by distance and optional selection
                trees.filter { t -> 
                    val distanceOk = ArNavigationManager.calculateDistance(loc.latitude, loc.longitude, t.lat, t.lon) <= radius
                    if (mode) {
                        distanceOk // Exploration mode ignores selection and shows ALL trees in 100m
                    } else {
                        distanceOk && selectedSet.contains(t.genusDe)
                    }
                }
            }
        }
    }
    .onEach { 
        // Loading finished once DB emits
        isMapLoading.value = false 
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val location = arManager.currentLocation
    val azimuth = arManager.azimuth

    init {
        // Stop loading if no location permission granted or stuck?
        // Let's rely on UI to handle permission rendering.
    }

    fun startTracking() {
        arManager.startTracking()
    }

    fun stopTracking() {
        arManager.stopTracking()
    }

    override fun onCleared() {
        super.onCleared()
        arManager.stopTracking()
    }
}
