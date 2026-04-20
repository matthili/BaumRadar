package at.mafue.baumradar.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import at.mafue.baumradar.app.data.AllergyDataStore
import at.mafue.baumradar.app.data.AppDatabase
import at.mafue.baumradar.app.data.TreeEntity
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MapViewModel(application: Application) : AndroidViewModel(application) {
    
    val arManager = ArNavigationManager(application)
    private val db = AppDatabase.getInstance(application)
    private val ds = AllergyDataStore(application)
    private val cityManager = at.mafue.baumradar.app.data.CityManager(application)

    private val SEARCH_RADIUS_METERS = 500.0
    private val EXPLORE_RADIUS_METERS = 100.0

    val isMapLoading = MutableStateFlow(true)
    val isExplorationMode = MutableStateFlow(false)
    val recenterTrigger = MutableStateFlow(0)
    
    val suggestedCityToDownload = MutableStateFlow<at.mafue.baumradar.app.data.CityCatalogEntry?>(null)
    private val ignoredCities = mutableSetOf<String>()

    val virtualLocation = MutableStateFlow<android.location.Location?>(null)

    val effectiveLocation: StateFlow<android.location.Location?> = combine(
        arManager.currentLocation,
        virtualLocation
    ) { real, virtual ->
        virtual ?: real
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        viewModelScope.launch {
            effectiveLocation.filterNotNull().sample(5000).collect { loc ->
                if (suggestedCityToDownload.value != null || isMapLoading.value) return@collect
                
                val catalog = cityManager.getCatalog()
                for (city in catalog) {
                    if (ignoredCities.contains(city.id)) continue
                    if (cityManager.isCityDownloaded(city.id)) continue

                    val bbox = city.boundingBox
                    if (bbox != null && bbox.size == 4) {
                        val minLat = bbox[0]
                        val minLon = bbox[1]
                        val maxLat = bbox[2]
                        val maxLon = bbox[3]
                        
                        if (loc.latitude in minLat..maxLat && loc.longitude in minLon..maxLon) {
                            suggestedCityToDownload.value = city
                            break
                        }
                    }
                }
            }
        }
    }

    fun ignoreSuggestedCity() {
        suggestedCityToDownload.value?.let {
            ignoredCities.add(it.id)
            suggestedCityToDownload.value = null
        }
    }

    fun downloadSuggestedCity(onProgress: (String) -> Unit) {
        val city = suggestedCityToDownload.value ?: return
        suggestedCityToDownload.value = null
        viewModelScope.launch {
            cityManager.downloadAndMergeCity(city, onProgress)
            // Trigger recomposition/reload of map data if needed
            isMapLoading.value = true
            kotlinx.coroutines.delay(500)
            isMapLoading.value = false
        }
    }

    // Combine location, selected allergies, and mode to query the database
    val nearbyAllergicTrees: StateFlow<List<TreeEntity>> = combine(
        effectiveLocation.filterNotNull().sample(2000),
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

    val location = effectiveLocation
    val azimuth = arManager.azimuth

    init {
        // Stop loading if no location permission granted or stuck?
        // Let's rely on UI to handle permission rendering.
    }

    fun triggerRecenter() {
        recenterTrigger.value++
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
