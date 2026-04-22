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
import org.osmdroid.util.GeoPoint
import at.mafue.baumradar.app.routing.OsrmRoutingClient
import at.mafue.baumradar.app.routing.RouteResult
import at.mafue.baumradar.app.routing.NominatimGeocoder
import at.mafue.baumradar.app.data.GeofenceEntity
import at.mafue.baumradar.app.data.RouteHistoryEntity

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
        arManager.currentLocation, virtualLocation
    ) { real, virt -> virt ?: real }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // UI Tracking for Map Clicks
    val longPressGeoPoint = MutableStateFlow<GeoPoint?>(null)

    // Routing State
    val routeStart = MutableStateFlow<GeoPoint?>(null)
    val routeEnd = MutableStateFlow<GeoPoint?>(null)
    val startAddress = MutableStateFlow("")
    val endAddress = MutableStateFlow("")
    val routingProfile = MutableStateFlow("foot")

    val routeAlternatives = MutableStateFlow<List<RouteResult>>(emptyList())
    val selectedRouteIndex = MutableStateFlow(0)
    
    val currentRouteResult: StateFlow<RouteResult?> = combine(
        routeAlternatives, selectedRouteIndex
    ) { alts, index ->
        if (alts.isEmpty() || index < 0 || index >= alts.size) null else alts[index]
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val showAllGeofences = MutableStateFlow(false)

    val visibleGeofences: StateFlow<List<GeofenceEntity>> = combine(
        effectiveLocation.filterNotNull().sample(2000),
        ds.selectedTreesFlow,
        showAllGeofences
    ) { loc, selectedTrees, showAll ->
        if (!showAll || selectedTrees.isEmpty()) {
            kotlinx.coroutines.flow.flowOf(emptyList())
        } else {
            val radius = 2000.0 // 2km Radius für die Kartendarstellung
            val latDelta = (radius / 111000.0)
            val lonDelta = (radius / (111000.0 * Math.cos(Math.toRadians(loc.latitude))))
            db.treeDao().getGeofencesInBoundingBoxFlow(
                selectedTrees.toList(),
                loc.latitude - latDelta,
                loc.latitude + latDelta,
                loc.longitude - lonDelta,
                loc.longitude + lonDelta
            )
        }
    }.flatMapLatest { it }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val currentRouteGeofences = MutableStateFlow<List<GeofenceEntity>>(emptyList())
    val isRouting = MutableStateFlow(false)
    val routingError = MutableStateFlow<String?>(null)

    private val routingClient = OsrmRoutingClient()

    val routeHistory = db.historyDao().getRecentHistory(10).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

    // Combine location, selected allergies, and warn trees to query the database
    val nearbyAllergicTrees: StateFlow<List<TreeEntity>> = combine(
        effectiveLocation.filterNotNull().sample(2000),
        ds.selectedTreesFlow,
        ds.warnTreesFlow,
        isExplorationMode
    ) { loc, selectedTrees, warnTrees, mode ->
        // Return a custom object or list, but let's just make it a tuple
        listOf(loc, selectedTrees.plus(warnTrees), mode)
    }
    .onEach { 
        // Trigger loading state whenever an input changes
        isMapLoading.value = true 
    }
    .flatMapLatest { tuple ->
        val loc = tuple[0] as android.location.Location
        @Suppress("UNCHECKED_CAST")
        val combinedTreesSet = tuple[1] as Set<String>
        val mode = tuple[2] as Boolean
        
        if (!mode && combinedTreesSet.isEmpty()) {
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
                        distanceOk && combinedTreesSet.contains(t.genusDe)
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

    fun calculateRoute(start: GeoPoint, end: GeoPoint) {
        viewModelScope.launch {
            isRouting.value = true
            routingError.value = null
            try {
                // 1. Fetch relevant Geofences in the Bounding Box of the route
                val minLat = minOf(start.latitude, end.latitude) - 0.05
                val maxLat = maxOf(start.latitude, end.latitude) + 0.05
                val minLon = minOf(start.longitude, end.longitude) - 0.05
                val maxLon = maxOf(start.longitude, end.longitude) + 0.05

                val selectedAllergies = ds.selectedTreesFlow.first()
                if (selectedAllergies.isEmpty()) {
                    // No allergies, pure direct routing
                    currentRouteGeofences.value = emptyList()
                    val result = routingClient.getRoute(start.latitude, start.longitude, end.latitude, end.longitude, emptyList(), routingProfile.value)
                    result.onSuccess { 
                        routeAlternatives.value = it 
                        selectedRouteIndex.value = 0
                    }.onFailure { routingError.value = it.message }
                } else {
                    val geofences = db.treeDao().getGeofencesInBoundingBox(
                        selectedAllergies.toList(), minLat, maxLat, minLon, maxLon
                    )
                    currentRouteGeofences.value = geofences

                    // Pass to routing client to compute best alternative
                    val result = routingClient.getRoute(
                        start.latitude, start.longitude, end.latitude, end.longitude, geofences, routingProfile.value
                    )
                    result.onSuccess { 
                        routeAlternatives.value = it 
                        selectedRouteIndex.value = 0
                    }.onFailure { routingError.value = it.message }
                }
            } catch (e: Exception) {
                routingError.value = e.message
            } finally {
                isRouting.value = false
            }
        }
    }
    
    fun calculateGeocodedRoute() {
        val startQ = startAddress.value
        val endQ = endAddress.value
        if (startQ.isBlank() || endQ.isBlank()) {
            routingError.value = "Start und Ziel dürfen nicht leer sein."
            return
        }
        
        viewModelScope.launch {
            routeAlternatives.value = emptyList()
            selectedRouteIndex.value = 0
            currentRouteGeofences.value = emptyList()
            isRouting.value = true
            routingError.value = null
            try {
                val startRes = NominatimGeocoder.getCoordinates(startQ)
                val endRes = NominatimGeocoder.getCoordinates(endQ)
                
                if (startRes.isSuccess && endRes.isSuccess) {
                    val s = startRes.getOrThrow()
                    val e = endRes.getOrThrow()
                    routeStart.value = s
                    routeEnd.value = e
                    
                    // Speichere in der Historie
                    db.historyDao().insertHistory(
                        RouteHistoryEntity(
                            startAddress = startQ,
                            endAddress = endQ,
                            startLat = s.latitude,
                            startLon = s.longitude,
                            endLat = e.latitude,
                            endLon = e.longitude,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                    db.historyDao().trimHistory(10) // Settings limit hardcoded to 10 for now.

                    calculateRoute(s, e)
                } else {
                    routingError.value = "Adresse konnte nicht aufgelöst werden."
                    isRouting.value = false
                }
            } catch (e: Exception) {
                routingError.value = e.message
                isRouting.value = false
            }
        }
    }

    fun clearRoute() {
        routeStart.value = null
        routeEnd.value = null
        startAddress.value = ""
        endAddress.value = ""
        routeAlternatives.value = emptyList()
        selectedRouteIndex.value = 0
        currentRouteGeofences.value = emptyList()
    }

    fun clearHistory() {
        viewModelScope.launch {
            db.historyDao().clearHistory()
        }
    }

    override fun onCleared() {
        super.onCleared()
        arManager.stopTracking()
    }
}
