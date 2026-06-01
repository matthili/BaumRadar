/**
 * Einstiegspaket der BaumRadar-App.
 *
 * BaumRadar ist eine Android-App, die Allergiker vor allergenen Bäumen in ihrer
 * Umgebung warnt. Die App zeigt eine Karte mit Baum-Standorten, berechnet
 * allergenfreie Routen und nutzt Geofencing für Echtzeit-Benachrichtigungen.
 */
package at.mafue.baumradar.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import at.mafue.baumradar.app.background.GeofenceLifecycleObserver
import at.mafue.baumradar.app.ui.theme.BaumRadarTheme
import kotlinx.coroutines.launch

/**
 * Haupt-Activity und einziger Einstiegspunkt der BaumRadar-App.
 *
 * Verantwortlich für:
 * - Initialisierung des Jetpack-Compose-UI-Baums
 * - Registrierung des [GeofenceLifecycleObserver], damit Geofences bei jedem
 *   Vordergrund-Wechsel automatisch wiederhergestellt werden
 * - Anwendung des App-Themes ([BaumRadarTheme])
 *
 * Die Activity selbst enthält keine Geschäftslogik – diese wird an ViewModels
 * und Composables delegiert.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Re-register geofences whenever the app comes to the foreground.
        // This covers the case where the OS killed the process and geofences were lost.
        // Uses the Activity's own lifecycle – no ProcessLifecycleOwner needed.
        lifecycle.addObserver(
            GeofenceLifecycleObserver(applicationContext)
        )
        
        setContent {
            BaumRadarTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppContent()
                }
            }
        }
    }
}

/**
 * Wurzel-Composable der App-Navigation.
 *
 * Entscheidet beim Start, ob der Ersteinrichtungs-Assistent ("wizard") oder der
 * Hauptbildschirm ("main") angezeigt wird. Grundlage für die Entscheidung ist,
 * ob bereits mindestens eine Stadt in der lokalen Datenbank vorhanden ist.
 *
 * Die Navigation nutzt Jetpack Navigation Compose mit zwei Routen:
 * - `wizard` → [CitySelectionScreen] im Assistenten-Modus
 * - `main`   → [MainScreen] mit Bottom-Navigation
 */
@Composable
fun AppContent() {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext as android.app.Application
    val cityManager = remember { at.mafue.baumradar.app.data.CityManager(context) }
    // Prüfe, ob bereits Baumdaten für mindestens eine Stadt heruntergeladen wurden.
    // Falls nicht, wird der Ersteinrichtungs-Assistent angezeigt.
    val startDestination = if (cityManager.hasAnyCity()) "main" else "wizard"
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = startDestination) {
        composable("wizard") {
            at.mafue.baumradar.app.ui.CitySelectionScreen(
                isWizard = true,
                onWizardComplete = {
                    // Nach Abschluss des Assistenten zum Hauptbildschirm navigieren.
                    // popUpTo mit inclusive = true entfernt den Wizard komplett aus dem
                    // Back-Stack, damit die Zurück-Taste nicht dorthin zurückführt.
                    navController.navigate("main") {
                        popUpTo("wizard") { inclusive = true }
                    }
                }
            )
        }
        composable("main") {
            at.mafue.baumradar.app.ui.MainScreen()
        }
    }
}


