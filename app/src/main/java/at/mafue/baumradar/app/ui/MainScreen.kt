package at.mafue.baumradar.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import android.app.Application
import at.mafue.baumradar.app.R

/**
 * Hauptbildschirm mit Bottom-Navigation und drei Tabs:
 * - **Karte** (MapArScreen): Kartenansicht mit AR-Overlay
 * - **Profil** (ProfileScreen): Allergieprofil-Verwaltung
 * - **Städte** (CitySelectionScreen): Verwaltung heruntergeladener Stadtdaten
 *
 * Das [MapViewModel] wird auf Activity-Ebene gescoped (nicht auf Composable-Ebene),
 * damit der Kartenzustand beim Wechsel zwischen Tabs erhalten bleibt. Außerdem
 * ermöglicht dies der Städte-Ansicht, den virtuellen Standort im MapViewModel zu
 * setzen ("Zur Stadt springen"-Funktion).
 */
@Composable
fun MainScreen() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: "map"
    val context = LocalContext.current.applicationContext as Application
    val activity = LocalContext.current as androidx.activity.ComponentActivity
    
    // ViewModelProvider.Factory wird benötigt, weil MapViewModel den
    // Application-Context als Konstruktorparameter erwartet (AndroidViewModel).
    // Die Activity wird als ViewModelStoreOwner verwendet, damit dasselbe
    // ViewModel über alle Tabs hinweg geteilt wird.
    val mapViewModel: MapViewModel = viewModel(
        activity,
        factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return MapViewModel(context) as T
            }
        }
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = currentRoute == "map",
                    onClick = {
                        if (currentRoute != "map") {
                            navController.navigate("map") { popUpTo(0) }
                        }
                    },
                    icon = { Icon(Icons.Default.Place, contentDescription = "Map") },
                    label = { Text(stringResource(R.string.tab_map)) }
                )
                NavigationBarItem(
                    selected = currentRoute == "profile",
                    onClick = {
                        if (currentRoute != "profile") {
                            navController.navigate("profile") { popUpTo(0) }
                        }
                    },
                    icon = { Icon(Icons.Default.List, contentDescription = "Profile") },
                    label = { Text(stringResource(R.string.tab_profile)) }
                )
                NavigationBarItem(
                    selected = currentRoute == "settings",
                    onClick = {
                        if (currentRoute != "settings") {
                            navController.navigate("settings") { popUpTo(0) }
                        }
                    },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    label = { Text("Städte") }
                )
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "map",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("map") {
                MapArScreen()
            }
            composable("profile") {
                ProfileScreen()
            }
            composable("settings") {
                CitySelectionScreen(
                    isWizard = false, 
                    onWizardComplete = {},
                    onJumpToCity = { city ->
                        // "Zur Stadt springen": Berechnet den Mittelpunkt der Bounding Box
                        // und setzt ihn als virtuellen Standort. Dadurch zeigt die Karte
                        // automatisch die gewählte Stadt an, auch ohne physische Anwesenheit.
                        val bbox = city.boundingBox
                        if (bbox != null && bbox.size == 4) {
                            val targetLat = (bbox[0] + bbox[2]) / 2.0
                            val targetLon = (bbox[1] + bbox[3]) / 2.0
                            val targetLoc = android.location.Location("Virtual").apply {
                                latitude = targetLat
                                longitude = targetLon
                            }
                            mapViewModel.virtualLocation.value = targetLoc
                            mapViewModel.triggerRecenter()
                        }
                        navController.navigate("map") { popUpTo(0) }
                    }
                )
            }
        }
    }
}




