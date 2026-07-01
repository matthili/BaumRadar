package at.mafue.baumradar.app.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import at.mafue.baumradar.app.routing.GpxGenerator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import at.mafue.baumradar.app.data.TreeEntity
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent

/**
 * Kombinierter Karten- und AR-Bildschirm – das Herzstück der App-Oberfläche.
 *
 * Schichtet drei Ebenen übereinander:
 * 1. [MapViewContent] – OSMDroid-Kartenansicht mit Baum-Markern, Geofence-Kreisen und Routen
 * 2. [ArOverlay] – Semitransparentes Canvas mit Richtungspfeilen zu nahen Bäumen
 * 3. UI-Controls – Routing-Formular, FABs, Routen-Chips, Dialoge
 *
 * Behandelt außerdem die Standortberechtigungs-Anfrage: Bei fehlender Berechtigung
 * wird ein erklärender Bildschirm mit einem Button zur Berechtigungsanfrage angezeigt.
 * Nach dauerhafter Ablehnung wird stattdessen zu den Systemeinstellungen weitergeleitet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapArScreen() {
    val context = LocalContext.current.applicationContext as Application
    val activity = LocalContext.current as androidx.activity.ComponentActivity
    val viewModel: MapViewModel = viewModel(
        activity,
        factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return MapViewModel(context) as T
            }
        }
    )

    var permissionGranted by remember { 
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
    }

    var permissionRequested by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionRequested = true
        // Nicht auf die Ergebnis-Map verlassen (liefert bei mitgesendeter Hintergrund-
        // Berechtigung auf manchen Geräten fälschlich false) – den echten Systemzustand prüfen.
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        permissionGranted = granted
        if (granted) {
            viewModel.startTracking()
        }
    }

    // Berechtigungen können auch außerhalb des Callbacks erteilt werden (System-Dialog auf
    // manchen Geräten, oder die App-Einstellungen). Beim Zurückkehren in den Vordergrund den
    // tatsächlichen Zustand neu prüfen, damit der Hinweis sofort verschwindet – ohne Neustart
    // oder Tab-Wechsel.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && !permissionGranted) {
                val granted = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    permissionGranted = true
                    viewModel.startTracking()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        if (!permissionGranted) {
            val permissionsToRequest = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                permissionsToRequest.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            launcher.launch(permissionsToRequest.toTypedArray())
        } else {
            viewModel.startTracking()
        }
        
        // OSMDroid benötigt einen gültigen User-Agent-String, da der Tile-Server
        // anonyme Anfragen blockiert. Der Package-Name ist eindeutig und ausreichend.
        Configuration.getInstance().userAgentValue = context.packageName
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.stopTracking() }
    }

    val isMapLoading by viewModel.isMapLoading.collectAsState()
    val isExplorationMode by viewModel.isExplorationMode.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var showRouteSheet by remember { mutableStateOf(false) }

    if (permissionGranted) {
        Box(modifier = Modifier.fillMaxSize()) {
            MapViewContent(viewModel)
            ArOverlay(viewModel)

            if (isMapLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            }



            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp)
                    .padding(bottom = 16.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                val virtualLocation by viewModel.virtualLocation.collectAsState()
                if (virtualLocation != null) {
                    ExtendedFloatingActionButton(
                        onClick = { viewModel.virtualLocation.value = null },
                        containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.92f),
                        contentColor = MaterialTheme.colorScheme.onError,
                        icon = { Icon(androidx.compose.material.icons.Icons.Default.Clear, contentDescription = "Beenden") },
                        text = { Text("Virtueller Standort", style = androidx.compose.material3.MaterialTheme.typography.labelLarge) }
                    )
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }

                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)
                ) {
                    // Route planen öffnen – als Bottom-Sheet, damit der obere Kartenbereich
                    // (die geradeaus zeigenden AR-Pfeile) frei bleibt.
                    MapToolFab(
                        ImageVector.vectorResource(at.mafue.baumradar.app.R.drawable.ic_directions),
                        "Route planen",
                        active = showRouteSheet
                    ) {
                        showRouteSheet = true
                    }

                    // Karte auf den eigenen Standort zentrieren (momentane Aktion)
                    MapToolFab(Icons.Default.Place, "Zentrieren", active = false) {
                        viewModel.triggerRecenter()
                    }

                    // Allergiezonen ein-/ausblenden (grün = aktiv)
                    val showAllGeofences by viewModel.showAllGeofences.collectAsState()
                    MapToolFab(Icons.Default.Warning, "Allergiezonen anzeigen", active = showAllGeofences) {
                        val newState = !showAllGeofences
                        viewModel.showAllGeofences.value = newState
                        coroutineScope.launch {
                            snackbarHostState.currentSnackbarData?.dismiss()
                            snackbarHostState.showSnackbar(
                                message = if (newState) "Allergiezonen eingeblendet" else "Allergiezonen ausgeblendet",
                                duration = SnackbarDuration.Short
                            )
                        }
                    }

                    // Erkundungsmodus – alle Bäume im Umkreis (grün = aktiv)
                    MapToolFab(Icons.Default.Search, "Erkundungsmodus", active = isExplorationMode) {
                        viewModel.isExplorationMode.value = !isExplorationMode
                    }
                }
            }
            
            val suggestedCity by viewModel.suggestedCityToDownload.collectAsState()
            if (suggestedCity != null) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { viewModel.ignoreSuggestedCity() },
                    title = { Text("Neue Region entdeckt!") },
                    text = { Text("Du befindest dich im Gebiet von ${suggestedCity!!.name}. Möchtest du die Daten dafür jetzt laden?") },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = { 
                            viewModel.downloadSuggestedCity {}
                        }) {
                            Text("Herunterladen")
                        }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { viewModel.ignoreSuggestedCity() }) {
                            Text("Ignorieren")
                        }
                    }
                )
            }

            // Long Press Dialog
            val longPressPoint by viewModel.longPressGeoPoint.collectAsState()
            val hasStart by viewModel.routeStart.collectAsState()
            if (longPressPoint != null) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { viewModel.longPressGeoPoint.value = null },
                    title = { Text("Aktion auswählen") },
                    text = { Text("Was möchtest du an diesem Punkt tun?") },
                    confirmButton = {
                        Column {
                            androidx.compose.material3.TextButton(onClick = { 
                                val loc = android.location.Location("Virtual").apply {
                                    latitude = longPressPoint!!.latitude
                                    longitude = longPressPoint!!.longitude
                                }
                                viewModel.virtualLocation.value = loc
                                viewModel.longPressGeoPoint.value = null
                            }) {
                                Text("Virtueller Standort hier setzen")
                            }
                            
                            androidx.compose.material3.TextButton(onClick = { 
                                viewModel.routeStart.value = longPressPoint
                                viewModel.longPressGeoPoint.value = null
                                viewModel.clearRoute()
                                viewModel.routeStart.value = longPressPoint
                            }) {
                                Text("Route HIER starten")
                            }

                            if (hasStart != null) {
                                androidx.compose.material3.TextButton(onClick = { 
                                    viewModel.routeEnd.value = longPressPoint
                                    viewModel.longPressGeoPoint.value = null
                                    viewModel.calculateRoute(hasStart!!, longPressPoint!!)
                                }) {
                                    Text("Route HIER beenden & Berechnen")
                                }
                            }
                        }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { viewModel.longPressGeoPoint.value = null }) {
                            Text("Abbrechen")
                        }
                    }
                )
            }

            // Routing Overlay (Top right clear button)
            val isRouting by viewModel.isRouting.collectAsState()
            val routeAlternatives by viewModel.routeAlternatives.collectAsState()
            val selectedRouteIndex by viewModel.selectedRouteIndex.collectAsState()
            
            if (isRouting) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)
                )
            }
            if (hasStart != null || routeAlternatives.isNotEmpty()) {
                val routeResult by viewModel.currentRouteResult.collectAsState()
                Column(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .padding(top = if (routeAlternatives.isEmpty()) 130.dp else 220.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)
                ) {
                    if (routeResult != null) {
                        FloatingActionButton(
                            onClick = { 
                                val startName = viewModel.startAddress.value.ifBlank { "Markierung" }
                                val endName = viewModel.endAddress.value.ifBlank { "Markierung" }
                                val textDesc = "Route von $startName nach $endName unter Vermeidung von ausgewählten Bäumen."
                                GpxGenerator.shareGpxRoute(activity, routeResult!!, textDesc)
                            },
                            containerColor = MaterialTheme.colorScheme.primary
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "Route teilen", tint = MaterialTheme.colorScheme.onPrimary)
                        }
                    }

                    FloatingActionButton(
                        onClick = { viewModel.clearRoute() },
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ) {
                        Icon(Icons.Default.Clear, contentDescription = "Route löschen")
                    }
                }
            }

            // Kontextuelle Overlays am oberen Rand: Erkundungs-Banner, Routing-Fehler und
            // Ergebnis-Chips. Das Eingabeformular liegt jetzt im Bottom-Sheet (FAB rechts unten).
            val routingErr by viewModel.routingError.collectAsState()

            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(16.dp)
                    .padding(top = 16.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
            ) {
                if (isExplorationMode) {
                    androidx.compose.material3.Surface(
                        color = Color(0xFF4CAF50),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.isExplorationMode.value = false },
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                        shadowElevation = 4.dp
                    ) {
                        Text(
                            text = "ERKUNDUNGSMODUS  ✕\nAlle Bäume im Umkreis (100m)",
                            color = Color.White,
                            modifier = Modifier.padding(12.dp),
                            style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
                
                
                if (routingErr != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = "Fehler", tint = MaterialTheme.colorScheme.onErrorContainer)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(routingErr!!, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
                
                // Route Alternatives Chips
                if (routeAlternatives.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
                    ) {
                        routeAlternatives.forEachIndexed { index, alt ->
                            val isSelected = index == selectedRouteIndex
                            val containerCol = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                            val contentCol = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            
                            val title = when {
                                alt.collisionCount == 0 -> "Allergiefrei"
                                else -> "Route ${index + 1}"
                            }
                            val subtitle = buildString {
                                append("${(alt.durationSec / 60).toInt()} min")
                                if (alt.collisionCount > 0) {
                                    append(" · ${alt.collisionCount} Hotspot")
                                    if (alt.collisionCount > 1) append("s")
                                }
                            }
                            
                            androidx.compose.material3.Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = containerCol,
                                onClick = { 
                                    viewModel.selectedRouteIndex.value = index
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = if (alt.collisionCount == 0) Icons.Default.CheckCircle else Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = contentCol,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Column {
                                        Text(title, style = MaterialTheme.typography.labelLarge, color = contentCol)
                                        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = contentCol)
                                    }
                                }
                            }
                        }
                    }

                    // Warning if no allergen-free route was found
                    val bestCollisions = routeAlternatives.minOfOrNull { it.collisionCount } ?: 0
                    if (routeAlternatives.isNotEmpty() && bestCollisions > 0) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "Keine allergenfreie Route gefunden. Die beste Route berührt $bestCollisions Hotspot${if (bestCollisions > 1) "s" else ""}.",
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }

            // Routen-Planung als modales Bottom-Sheet (über den FAB rechts unten geöffnet).
            // Ersetzt das früher dauerhaft oben eingeblendete Eingabefeld.
            if (showRouteSheet) {
                ModalBottomSheet(onDismissRequest = { showRouteSheet = false }) {
                    RoutePlanningSheetContent(viewModel) { showRouteSheet = false }
                }
            }

            // Snackbar für Feedback-Meldungen (z.B. Geofence-Toggle)
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 100.dp)
            )
        }
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.Place,
                    contentDescription = null,
                    modifier = Modifier.padding(bottom = 16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    "Berechtigungen benötigt",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    "Damit BaumRadar voll funktioniert, braucht die App:\n\n" +
                    "📍  Standort – für die Karte, die AR-Ansicht und das Finden naher Bäume\n\n" +
                    "🔔  Benachrichtigungen – für die Annäherungs-Warnung vor allergenen Bäumen\n\n" +
                    "📦  „Apps aus unbekannten Quellen“ – nur für die automatische App-Aktualisierung (wird erst beim Update abgefragt)",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Start,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                val shouldShowRationale = activity.shouldShowRequestPermissionRationale(
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
                // Erkennung der dauerhaften Ablehnung: shouldShowRationale ist false
                // sowohl beim allerersten Aufruf ALS AUCH nach "Nicht erneut fragen".
                // Nur anhand von permissionRequested lässt sich unterscheiden, ob
                // die Berechtigung bereits einmal angefragt wurde.
                val canAskAgain = shouldShowRationale || !permissionRequested
                Button(onClick = {
                    if (canAskAgain) {
                        // System will show the permission dialog (first time or rationale)
                        val permissionsToRequest = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                            permissionsToRequest.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        }
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        launcher.launch(permissionsToRequest.toTypedArray())
                    } else {
                        // Permission permanently denied — open app settings
                        val intent = android.content.Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.fromParts("package", context.packageName, null)
                        )
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    }
                }) {
                    Text(
                        if (canAskAgain) "Berechtigung erteilen"
                        else "Einstellungen öffnen"
                    )
                }
            }
        }
    }
}

/**
 * Kartenansicht basierend auf OSMDroid (OpenStreetMap).
 *
 * Verwendet [AndroidView], um die imperative MapView in Compose einzubetten.
 * Bei jedem Recompose (update-Lambda) werden alle Overlays neu gezeichnet:
 * - Baum-Marker (gelb)
 * - Geofence-Kreise (rot, halbtransparent)
 * - Start/Ziel-Marker (grün/blau)
 * - Routenpolyline
 *
 * MyLocationOverlay und MapEventsOverlay werden beim Neuzeichnen bewusst
 * beibehalten, da sie internen Zustand führen (GPS-Tracking, Touch-Events).
 */
@Composable
fun MapViewContent(viewModel: MapViewModel) {
    val context = LocalContext.current
    val trees by viewModel.nearbyAllergicTrees.collectAsState()
    val location by viewModel.location.collectAsState()
    val recenterTrigger by viewModel.recenterTrigger.collectAsState()
    val virtLoc by viewModel.virtualLocation.collectAsState()
    val routeStart by viewModel.routeStart.collectAsState()
    val routeEnd by viewModel.routeEnd.collectAsState()
    val currentGeofences by viewModel.currentRouteGeofences.collectAsState()
    val visibleGeofences by viewModel.visibleGeofences.collectAsState()
    val routeResult by viewModel.currentRouteResult.collectAsState()

    var lastRecenter by remember { mutableStateOf(0) }
    var isMapCentered by remember { mutableStateOf(false) }
    // Zoom-abhängiges Clustering: ein Zähler, der bei Wechsel der ganzzahligen Zoomstufe
    // erhöht wird und so ein Neu-Bündeln der Baum-Marker auslöst.
    val zoomTick = remember { mutableStateOf(0) }
    val lastZoomInt = remember { mutableStateOf(Int.MIN_VALUE) }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            MapView(ctx).apply {
                setMultiTouchControls(true)
                // osmdroids weiße +/- Zoom-Buttons ausblenden – Pinch-Zoom bleibt aktiv und
                // passt besser zum reduzierten Glas-Look der FABs.
                zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
                controller.setZoom(16.0)
                
                val mapEventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                        return false
                    }

                    override fun longPressHelper(p: GeoPoint?): Boolean {
                        p?.let {
                            viewModel.longPressGeoPoint.value = it
                        }
                        return true
                    }
                })
                overlays.add(mapEventsOverlay)
                
                val myLocOverlay = MyLocationNewOverlay(this)
                myLocOverlay.enableMyLocation()
                overlays.add(myLocOverlay)

                // Bei Wechsel der (ganzzahligen) Zoomstufe neu clustern – nicht bei jedem Pixel-Zoom.
                addMapListener(object : MapListener {
                    override fun onZoom(event: ZoomEvent?): Boolean {
                        val zi = (event?.zoomLevel ?: zoomLevelDouble).toInt()
                        if (zi != lastZoomInt.value) {
                            lastZoomInt.value = zi
                            zoomTick.value++
                        }
                        return false
                    }
                    override fun onScroll(event: ScrollEvent?): Boolean = false
                })
            }
        },
        update = { map ->
            val currentVirtLoc = virtLoc
            if (!isMapCentered) {
                if (currentVirtLoc != null) { 
                    map.controller.setCenter(GeoPoint(currentVirtLoc.latitude, currentVirtLoc.longitude))
                    isMapCentered = true
                } else if (location != null) {
                    map.controller.setCenter(GeoPoint(location!!.latitude, location!!.longitude))
                    isMapCentered = true
                }
            }

            if (recenterTrigger > lastRecenter) {
                lastRecenter = recenterTrigger
                val currentEffLoc = viewModel.effectiveLocation.value
                if (currentEffLoc != null) {
                    map.controller.animateTo(GeoPoint(currentEffLoc.latitude, currentEffLoc.longitude))
                }
            }
            
            // Alle dynamischen Overlays entfernen, aber MyLocationOverlay und
            // MapEventsOverlay erhalten. Diese führen internen Zustand (GPS-Tracking,
            // Touch-Events) und dürfen nicht neu erstellt werden.
            val myLoc = map.overlays.find { it is MyLocationNewOverlay }
            val mapEv = map.overlays.find { it is MapEventsOverlay }
            map.overlays.clear()
            mapEv?.let { map.overlays.add(it) }
            
            if (currentVirtLoc == null) {
                myLoc?.let { map.overlays.add(it) }
            } else {
                val virtMarker = Marker(map)
                virtMarker.position = GeoPoint(currentVirtLoc.latitude, currentVirtLoc.longitude)
                virtMarker.title = "Virtueller Standort"
                virtMarker.icon = ContextCompat.getDrawable(context, android.R.drawable.ic_menu_mylocation)
                virtMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                map.overlays.add(virtMarker)
            }

            // Bäume rendern – dicht beieinander liegende werden zoom-abhängig zu grünen
            // Zähl-Bubbles gebündelt. zoomTick lesen, damit bei einem Zoom-Stufenwechsel
            // neu geclustert wird (der update-Block reagiert dann auf die Zustandsänderung).
            if (zoomTick.value >= 0) {
                addTreeMarkers(map, trees, context)
            }

            // Geofence-Kreise zeichnen: Die Vereinigung aus Routing-Geofences und
            // sichtbaren Geofences wird dedupliziert, damit kein Kreis doppelt erscheint
            val geofencesToDraw = (currentGeofences + visibleGeofences).distinctBy { it.id }
            geofencesToDraw.forEach { fence ->
                val polygon = Polygon(map).apply {
                    points = Polygon.pointsAsCircle(GeoPoint(fence.lat, fence.lon), fence.radius.toDouble() + 60.0)
                    fillPaint.color = android.graphics.Color.argb(80, 255, 0, 0)
                    outlinePaint.color = android.graphics.Color.argb(150, 255, 0, 0)
                    outlinePaint.strokeWidth = 2f
                    title = "Allergie Hotspot: ${fence.count} Bäume"
                }
                map.overlays.add(polygon)
            }

            // Draw Route Start & End Markers
            if (routeStart != null) {
                val m = Marker(map)
                m.position = routeStart
                m.title = "Startpunkt"
                val drawable = ContextCompat.getDrawable(context, at.mafue.baumradar.app.R.drawable.ic_pin)?.mutate()
                drawable?.setTint(android.graphics.Color.GREEN)
                m.icon = drawable
                m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                map.overlays.add(m)
            }
            if (routeEnd != null) {
                val m = Marker(map)
                m.position = routeEnd
                m.title = "Zielpunkt"
                val drawable = ContextCompat.getDrawable(context, at.mafue.baumradar.app.R.drawable.ic_pin)?.mutate()
                drawable?.setTint(android.graphics.Color.BLUE)
                m.icon = drawable
                m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                map.overlays.add(m)
            }

            // Draw Polyline
            routeResult?.let { result ->
                val polyline = Polyline(map).apply {
                    outlinePaint.color = android.graphics.Color.BLUE
                    outlinePaint.strokeWidth = 10f
                    val points = result.polylinePoints.map { GeoPoint(it.first, it.second) }
                    setPoints(points)
                }
                map.overlays.add(polyline)
                
                // Animate to route bounds
                if (!isMapCentered) {
                   // Only needed if we want to zoom to fit the route. 
                }
            }

            map.invalidate()
        }
    )
}

/**
 * AR-ähnliches Overlay, das Richtungspfeile zu nahen allergenen Bäumen zeichnet.
 *
 * Für jeden der 15 nächsten Bäume wird ein halbtransparenter roter Pfeil am Rand
 * eines virtuellen Kreises um die Bildschirmmitte gezeichnet, der in die Richtung
 * des Baumes zeigt; die Distanz steht als Beschriftung daneben. Auch (fast)
 * geradeaus liegende Bäume bekommen einen Pfeil (12-Uhr-Position) – genau die
 * braucht man beim Zugehen.
 *
 * Die Pfeilrichtung ergibt sich aus der Differenz zwischen Kompass-Azimut
 * und dem Bearing zum Baum (relativeBearing).
 */
@Composable
fun ArOverlay(viewModel: MapViewModel) {
    val location by viewModel.location.collectAsState()
    val azimuth by viewModel.azimuth.collectAsState()
    val trees by viewModel.nearbyAllergicTrees.collectAsState()
    val textMeasurer = rememberTextMeasurer()

    if (location == null) return

    val myLat = location!!.latitude
    val myLon = location!!.longitude

    // Nur die 15 nächsten Bäume berücksichtigen, um die Canvas-Darstellung
    // übersichtlich zu halten (zu viele Pfeile wären unleserlich)
    val treesWithDistance = remember(trees, location) {
        trees.map { it to ArNavigationManager.calculateDistance(myLat, myLon, it.lat, it.lon) }
            .sortedBy { it.second }
            .take(15)
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val centerW = size.width / 2f
        val centerH = size.height / 2f
        val radius = minOf(centerW, centerH) * 0.9f

        treesWithDistance.forEach { (tree, distance) ->
            val treeBearing = ArNavigationManager.calculateBearing(myLat, myLon, tree.lat, tree.lon)

            var relativeBearing = (treeBearing - azimuth).toFloat()
            // Normalize to [-180, 180]
            if (relativeBearing > 180) relativeBearing -= 360
            if (relativeBearing < -180) relativeBearing += 360

            // Pfeil auf einem virtuellen Kreis um die Bildschirmmitte, der in Richtung
            // des Baumes zeigt – für JEDEN nahen Baum, auch die (fast) geradeaus
            // liegenden (12-Uhr-Position). Genau die braucht man beim Zugehen; früher
            // wurden Bäume „im Sichtfeld" (±30°) ausgelassen – ohne Kamerabild auf der
            // Karte unlogisch und verwirrend, weil der Pfeil beim Zugehen verschwand.
            rotate(degrees = relativeBearing, pivot = Offset(centerW, centerH)) {
                // Oben auf dem (um relativeBearing gedrehten) Canvas = Zielrichtung
                val arrowTop = Offset(centerW, centerH - radius)

                val path = Path().apply {
                    moveTo(arrowTop.x, arrowTop.y)
                    lineTo(arrowTop.x - 20f, arrowTop.y + 40f)
                    lineTo(arrowTop.x + 20f, arrowTop.y + 40f)
                    close()
                }
                drawPath(path, Color.Red.copy(alpha = 0.4f)) // Transparenz, damit Karten-Tooltips lesbar bleiben

                // Distanz-Label dreht mit dem Pfeil mit (kopfüber bei Bäumen hinter einem –
                // bewusst so gelassen: ruhiger als ständiges Nachdrehen).
                val distStr = "${distance.toInt()}m"
                drawText(
                    textMeasurer = textMeasurer,
                    text = distStr,
                    topLeft = Offset(arrowTop.x - 30f, arrowTop.y + 50f)
                )
            }
        }
    }
}

/**
 * Fügt die Bäume als Karten-Overlays hinzu und bündelt dicht beieinander liegende zu
 * grünen Zähl-Bubbles. Das Raster ist zoom-abhängig (höherer Zoom → kleinere Zellen →
 * weniger Bündelung); ein Tap auf eine Bubble zoomt hinein und löst sie auf.
 */
private fun addTreeMarkers(map: MapView, trees: List<TreeEntity>, context: Context) {
    if (trees.isEmpty()) return
    val degPerTile = 360.0 / Math.pow(2.0, map.zoomLevelDouble)
    val cellLat = degPerTile * (72.0 / 256.0)                         // ~72px-Rasterzelle
    val cosLat = Math.max(0.2, Math.cos(Math.toRadians(map.mapCenter.latitude)))
    val cellLon = cellLat / cosLat                                    // auf dem Schirm ~quadratisch

    val buckets = HashMap<Long, MutableList<TreeEntity>>()
    for (t in trees) {
        val gx = Math.floor(t.lat / cellLat).toLong()
        val gy = Math.floor(t.lon / cellLon).toLong()
        val key = (gx shl 32) xor (gy and 0xffffffffL)
        buckets.getOrPut(key) { mutableListOf() }.add(t)
    }

    for (group in buckets.values) {
        if (group.size == 1) {
            map.overlays.add(makeTreeMarker(map, group[0], context))
        } else {
            val avgLat = group.sumOf { it.lat } / group.size
            val avgLon = group.sumOf { it.lon } / group.size
            val cluster = Marker(map)
            cluster.position = GeoPoint(avgLat, avgLon)
            cluster.icon = makeClusterIcon(context, group.size)
            cluster.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            cluster.setInfoWindow(null)
            cluster.setOnMarkerClickListener { m, mv ->
                mv.controller.animateTo(m.position)
                mv.controller.setZoom(mv.zoomLevelDouble + 2.0)
                true
            }
            map.overlays.add(cluster)
        }
    }
}

/** Einzelner Baum-Marker (gelber Pin) mit Sprechblase (Titel + botanischer Name). */
private fun makeTreeMarker(map: MapView, tree: TreeEntity, context: Context): Marker {
    val marker = Marker(map)
    marker.position = GeoPoint(tree.lat, tree.lon)
    val title = if (!tree.speciesDe.isNullOrEmpty() && tree.genusDe?.contains(tree.speciesDe) == false) {
        "${tree.genusDe} (${tree.speciesDe})"
    } else {
        val rawDe = tree.genusDe ?: "Unbekannter Baum"
        if (rawDe.contains("(") && !rawDe.contains(")")) "$rawDe)" else rawDe
    }
    marker.title = title
    marker.snippet = tree.speciesEn?.takeIf { it.isNotBlank() }
    val drawable = ContextCompat.getDrawable(context, at.mafue.baumradar.app.R.drawable.ic_pin)?.mutate()
    drawable?.setTint(android.graphics.Color.YELLOW)
    marker.icon = drawable
    marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
    return marker
}

/** Grüne, runde Zähl-Bubble für einen Cluster (Größe wächst leicht mit der Anzahl). */
private fun makeClusterIcon(context: Context, count: Int): BitmapDrawable {
    val d = context.resources.displayMetrics.density
    val sizeDp = (36f + Math.min(count, 200) * 0.12f).coerceAtMost(60f)
    val size = (sizeDp * d).toInt().coerceAtLeast(1)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val c = size / 2f
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = android.graphics.Color.argb(90, 76, 175, 80)   // Blattgrün, halbtransparenter Ring
    canvas.drawCircle(c, c, c, p)
    p.color = android.graphics.Color.rgb(46, 107, 46)        // dunkles Marken-Grün
    canvas.drawCircle(c, c, c * 0.72f, p)
    p.color = android.graphics.Color.WHITE
    p.textAlign = Paint.Align.CENTER
    p.isFakeBoldText = true
    p.textSize = size * (if (count > 99) 0.30f else 0.36f)
    val label = if (count > 999) "999+" else count.toString()
    val fm = p.fontMetrics
    canvas.drawText(label, c, c - (fm.ascent + fm.descent) / 2f, p)
    return BitmapDrawable(context.resources, bmp)
}

/**
 * Einheitlicher, glasig-transluzenter Karten-Werkzeug-FAB.
 *
 * Inaktiv: neutrale, halbtransparente Oberfläche (die Karte scheint dezent durch).
 * Aktiv: Marken-Grün (`primary`). Ersetzt die früher bunt gemischten FAB-Farben.
 */
@Composable
private fun MapToolFab(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean,
    onClick: () -> Unit
) {
    FloatingActionButton(
        onClick = onClick,
        containerColor = if (active) MaterialTheme.colorScheme.primary
                         else MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
        contentColor = if (active) MaterialTheme.colorScheme.onPrimary
                       else MaterialTheme.colorScheme.onSurface,
        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 3.dp, pressedElevation = 6.dp)
    ) {
        Icon(icon, contentDescription = contentDescription)
    }
}

/**
 * Inhalt des Routen-Planungs-Bottom-Sheets: Start-/Zieladresse (mit Verlaufs-Menü),
 * Fortbewegungsart und Such-Button. Früher ein dauerhaft oben eingeblendetes Feld –
 * jetzt per FAB rechts unten aufrufbar, damit der obere Kartenbereich für die
 * geradeaus zeigenden AR-Pfeile frei bleibt. [onClose] schließt das Sheet, sobald
 * eine Suche ausgelöst wurde.
 */
@Composable
private fun RoutePlanningSheetContent(viewModel: MapViewModel, onClose: () -> Unit) {
    val startAddr by viewModel.startAddress.collectAsState()
    val endAddr by viewModel.endAddress.collectAsState()
    val history by viewModel.routeHistory.collectAsState()
    val isRouting by viewModel.isRouting.collectAsState()
    val currentProfile by viewModel.routingProfile.collectAsState()
    var historyExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 16.dp)
    ) {
        Text(
            text = "Route planen",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = startAddr,
                onValueChange = { viewModel.startAddress.value = it },
                label = { Text("Startadresse") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            IconButton(onClick = { historyExpanded = !historyExpanded }) {
                Icon(Icons.Default.Menu, contentDescription = "Historie")
            }
            DropdownMenu(
                expanded = historyExpanded,
                onDismissRequest = { historyExpanded = false }
            ) {
                if (history.isEmpty()) {
                    DropdownMenuItem(text = { Text("Keine Historie") }, onClick = { historyExpanded = false })
                } else {
                    history.forEach { item ->
                        DropdownMenuItem(
                            text = { Text("${item.startAddress} -> ${item.endAddress}", maxLines = 1) },
                            onClick = {
                                viewModel.startAddress.value = item.startAddress
                                viewModel.endAddress.value = item.endAddress
                                historyExpanded = false
                                viewModel.calculateGeocodedRoute()
                                onClose()
                            }
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Historie leeren", color = Color.Red) },
                        leadingIcon = { Icon(Icons.Default.Delete, tint = Color.Red, contentDescription = null) },
                        onClick = {
                            viewModel.clearHistory()
                            historyExpanded = false
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = endAddr,
                onValueChange = { viewModel.endAddress.value = it },
                label = { Text("Zieladresse") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    viewModel.calculateGeocodedRoute()
                    onClose()
                },
                modifier = Modifier.height(56.dp)
            ) {
                Icon(Icons.Default.Search, contentDescription = "Suchen")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly
        ) {
            val profiles = listOf("foot" to "Zu Fuß", "bike" to "Fahrrad", "driving" to "Mobil")
            profiles.forEach { (key, label) ->
                if (currentProfile == key) {
                    Button(
                        onClick = { },
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                    ) {
                        Text(label, maxLines = 1, style = MaterialTheme.typography.labelMedium)
                    }
                } else {
                    OutlinedButton(
                        onClick = {
                            viewModel.routingProfile.value = key
                            val start = viewModel.routeStart.value
                            val end = viewModel.routeEnd.value
                            if (start != null && end != null) {
                                viewModel.calculateRoute(start, end)
                            }
                        },
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                    ) {
                        Text(label, maxLines = 1, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        if (isRouting) {
            Spacer(modifier = Modifier.height(12.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
        }
    }
}
