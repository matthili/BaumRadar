package at.mafue.baumradar.app.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Place
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
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

@Composable
fun MapArScreen() {
    val context = LocalContext.current.applicationContext as Application
    val viewModel: MapViewModel = viewModel(
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

            if (isExplorationMode) {
                androidx.compose.material3.Surface(
                    color = Color(0xFF4CAF50),
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp, start = 16.dp, end = 16.dp),
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

            val virtualLocation by viewModel.virtualLocation.collectAsState()
            if (virtualLocation != null) {
                androidx.compose.material3.Button(
                    onClick = { viewModel.virtualLocation.value = null },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) {
                    Text("Virtuellen Standort beenden")
                }
            }

            androidx.compose.foundation.layout.Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(32.dp),
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
                    Icon(Icons.Default.Search, contentDescription = "Erkundungsmodus")
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
                            val loc = android.location.Location("Virtual").apply {
                                latitude = it.latitude
                                longitude = it.longitude
                            }
                            viewModel.virtualLocation.value = loc
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
            val virtLoc = viewModel.virtualLocation.value
            if (!isMapCentered) {
                if (virtLoc != null) { 
                    map.controller.setCenter(GeoPoint(virtLoc.latitude, virtLoc.longitude))
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
            
            if (virtLoc == null) {
                myLoc?.let { map.overlays.add(it) }
            } else {
                val virtMarker = Marker(map)
                virtMarker.position = GeoPoint(virtLoc.latitude, virtLoc.longitude)
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
