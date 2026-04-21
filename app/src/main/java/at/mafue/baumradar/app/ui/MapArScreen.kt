package at.mafue.baumradar.app.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Clear
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
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

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        permissionGranted = isGranted
        if (isGranted) {
            viewModel.startTracking()
        }
    }

    LaunchedEffect(Unit) {
        if (!permissionGranted) {
            launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            viewModel.startTracking()
        }
        
        // Setup OSMDroid Config
        Configuration.getInstance().userAgentValue = context.packageName
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.stopTracking() }
    }

    val isMapLoading by viewModel.isMapLoading.collectAsState()
    val isExplorationMode by viewModel.isExplorationMode.collectAsState()

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
                        containerColor = Color(0xFFFF5252),
                        contentColor = Color.White,
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
                    FloatingActionButton(
                        onClick = { viewModel.triggerRecenter() },
                        containerColor = Color.White
                    ) {
                        Icon(Icons.Default.Place, contentDescription = "Zentrieren", tint = Color.Black)
                    }

                    FloatingActionButton(
                        onClick = { viewModel.isExplorationMode.value = !isExplorationMode },
                        containerColor = if (isExplorationMode) Color(0xFF4CAF50) else Color.LightGray
                    ) {
                        Icon(Icons.Default.Search, contentDescription = "Erkundungsmodus", tint = Color.White)
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
                                GpxGenerator.shareGpxRoute(context, routeResult!!, textDesc)
                            },
                            containerColor = MaterialTheme.colorScheme.primary
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "Route teilen", tint = MaterialTheme.colorScheme.onPrimary)
                        }
                    }

                    FloatingActionButton(
                        onClick = { viewModel.clearRoute() },
                        containerColor = Color(0xFFFF5252)
                    ) {
                        Icon(Icons.Default.Clear, contentDescription = "Route löschen", tint = Color.White)
                    }
                }
            }

            // Search UI Overlay
            val startAddr by viewModel.startAddress.collectAsState()
            val endAddr by viewModel.endAddress.collectAsState()
            val routingErr by viewModel.routingError.collectAsState()
            val history by viewModel.routeHistory.collectAsState()
            var historyExpanded by remember { mutableStateOf(false) }
            var searchExpanded by remember { mutableStateOf(true) }
            
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
                        modifier = Modifier.fillMaxWidth(),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                        shadowElevation = 4.dp
                    ) {
                        Text(
                            text = "ERKUNDUNGSMODUS\nAlle Bäume im Umkreis (100m)",
                            color = Color.White,
                            modifier = Modifier.padding(12.dp),
                            style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
                
                Card(
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Route planen",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                            IconButton(onClick = { searchExpanded = !searchExpanded }) {
                                Icon(
                                    imageVector = if (searchExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Suche einklappen/ausklappen"
                                )
                            }
                        }
                    
                        AnimatedVisibility(visible = searchExpanded) {
                            Column(modifier = Modifier.padding(12.dp)) {
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
                                        onClick = { viewModel.calculateGeocodedRoute() },
                                        modifier = Modifier.height(56.dp)
                                    ) {
                                        Icon(Icons.Default.Search, contentDescription = "Suchen")
                                    }
                                }
                                
                                val currentProfile by viewModel.routingProfile.collectAsState()
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
                            }
                        }
                        if (isRouting) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
                        }
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
                            
                            val title = if (alt.collisionCount == 0) "Allergiefrei \uD83D\uDFE2" else "Route ${index + 1}"
                            val subtitle = "${(alt.durationSec / 60).toInt()} min"
                            
                            androidx.compose.material3.Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = containerCol,
                                onClick = { viewModel.selectedRouteIndex.value = index }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(title, style = MaterialTheme.typography.labelLarge, color = contentCol)
                                        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = contentCol)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Location permission required for AR and Map.")
        }
    }
}

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
    val routeResult by viewModel.currentRouteResult.collectAsState()

    var lastRecenter by remember { mutableStateOf(0) }
    var isMapCentered by remember { mutableStateOf(false) }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            MapView(ctx).apply {
                setMultiTouchControls(true)
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
            
            // Clear old markers, preserve MyLocationOverlay and MapEventsOverlay
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

            // Add trees
            trees.forEach { tree ->
                val marker = Marker(map)
                marker.position = GeoPoint(tree.lat, tree.lon)
                
                val title = if (!tree.speciesDe.isNullOrEmpty() && tree.genusDe?.contains(tree.speciesDe) == false) {
                    "${tree.genusDe} (${tree.speciesDe})"
                } else {
                    val rawDe = tree.genusDe ?: "Unbekannter Baum"
                    if (rawDe.contains("(") && !rawDe.contains(")")) "$rawDe)" else rawDe
                }
                marker.title = title
                
                marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                map.overlays.add(marker)
            }

            // Draw Geofences (Red semi-transparent circles)
            currentGeofences.forEach { fence ->
                val polygon = Polygon(map).apply {
                    points = Polygon.pointsAsCircle(GeoPoint(fence.lat, fence.lon), fence.radius.toDouble())
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
                m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                map.overlays.add(m)
            }
            if (routeEnd != null) {
                val m = Marker(map)
                m.position = routeEnd
                m.title = "Zielpunkt"
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

@Composable
fun ArOverlay(viewModel: MapViewModel) {
    val location by viewModel.location.collectAsState()
    val azimuth by viewModel.azimuth.collectAsState()
    val trees by viewModel.nearbyAllergicTrees.collectAsState()
    val textMeasurer = rememberTextMeasurer()

    if (location == null) return

    val myLat = location!!.latitude
    val myLon = location!!.longitude

    // Field of View parameters
    val fov = 60f // Total FOV of screen
    val halfFov = fov / 2f

    // Calculate distances and take only top 15 closest trees to avoid arrow-blobs
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

            // If it's outside the FOV, draw an arrow on the edge
            if (Math.abs(relativeBearing) > halfFov) {
                // Map relative bearing to screen edge
                // We'll draw an arrow in a circle around the center of the screen pointing towards the tree
                rotate(degrees = relativeBearing, pivot = Offset(centerW, centerH)) {
                    // Draw at top of the rotated canvas (which means it's pointing in `relativeBearing` direction)
                    val arrowTop = Offset(centerW, centerH - radius)
                    
                    val path = Path().apply {
                        moveTo(arrowTop.x, arrowTop.y)
                        lineTo(arrowTop.x - 20f, arrowTop.y + 40f)
                        lineTo(arrowTop.x + 20f, arrowTop.y + 40f)
                        close()
                    }
                    drawPath(path, Color.Red.copy(alpha = 0.4f)) // Transparency so Map Tooltips stay readable!
                    
                    // Note: Drawing text needs to be unrotated or rotated carefully to be readable.
                    // For simplicity, we draw it right next to the arrow.
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
}
