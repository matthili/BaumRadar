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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import at.mafue.baumradar.app.background.GeofenceLifecycleObserver
import at.mafue.baumradar.app.data.CityCatalogEntry
import at.mafue.baumradar.app.ui.theme.BaumRadarTheme
import at.mafue.baumradar.app.updater.UpdateInfo
import at.mafue.baumradar.app.updater.UpdateManager
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
 * Prüft außerdem beim Start automatisch auf App-Updates via GitHub Releases.
 *
 * Die Navigation nutzt Jetpack Navigation Compose mit zwei Routen:
 * - `wizard` → [CitySelectionScreen] im Assistenten-Modus
 * - `main`   → [MainScreen] mit Bottom-Navigation
 */
@Composable
fun AppContent() {
    val context = LocalContext.current.applicationContext as android.app.Application
    val cityManager = remember { at.mafue.baumradar.app.data.CityManager(context) }
    // Prüfe, ob bereits Baumdaten für mindestens eine Stadt heruntergeladen wurden.
    // Falls nicht, wird der Ersteinrichtungs-Assistent angezeigt.
    val startDestination = if (cityManager.hasAnyCity()) "main" else "wizard"
    val navController = rememberNavController()

    // --- In-App Update ---
    val updateManager = remember { UpdateManager(context, BuildConfig.VERSION_NAME) }
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf<Int?>(null) }
    var isDownloading by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    // --- Daten-Update (aktualisierte Baumdaten für bereits geladene Städte) ---
    var staleCities by remember { mutableStateOf<List<CityCatalogEntry>>(emptyList()) }
    var showDataUpdateDialog by remember { mutableStateOf(false) }
    var isRefreshingData by remember { mutableStateOf(false) }
    var dataRefreshStatus by remember { mutableStateOf("") }

    // Einmalige Prüfung beim App-Start: zuerst App-Update, sonst Baumdaten-Update.
    LaunchedEffect(Unit) {
        val info = updateManager.checkForUpdate()
        if (info != null) {
            updateInfo = info
            showUpdateDialog = true
        } else {
            // Kein App-Update nötig → prüfen, ob für geladene Städte neue Baumdaten vorliegen.
            val stale = cityManager.citiesNeedingDataUpdate(cityManager.getCatalog())
            if (stale.isNotEmpty()) {
                staleCities = stale
                showDataUpdateDialog = true
            }
        }
    }

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

    // --- Update-Dialog: Informiert über verfügbare Version ---
    if (showUpdateDialog && updateInfo != null) {
        AlertDialog(
            onDismissRequest = { showUpdateDialog = false },
            icon = {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text("Neue Version verfügbar!")
            },
            text = {
                Column {
                    Text(
                        "Version ${updateInfo!!.versionName}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        updateInfo!!.releaseNotes.take(500),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    showUpdateDialog = false
                    if (updateManager.canInstallPackages()) {
                        // Berechtigung vorhanden → direkt herunterladen
                        isDownloading = true
                        coroutineScope.launch {
                            val apk = updateManager.downloadApk(updateInfo!!.downloadUrl) { progress ->
                                downloadProgress = progress
                            }
                            isDownloading = false
                            downloadProgress = null
                            if (apk != null) {
                                updateManager.installApk(apk)
                            }
                        }
                    } else {
                        // Berechtigung fehlt → Erklärungs-Dialog anzeigen
                        showPermissionDialog = true
                    }
                }) {
                    Text("Aktualisieren")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUpdateDialog = false }) {
                    Text("Später")
                }
            }
        )
    }

    // --- Berechtigungs-Dialog: Erklärt „Unbekannte Quellen" ---
    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = {
                Text("Berechtigung benötigt")
            },
            text = {
                Column {
                    Text(
                        "Um das Update zu installieren, muss BaumRadar die Erlaubnis haben, " +
                        "Apps aus unbekannten Quellen zu installieren.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "So geht's:",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "1. Tippe auf 'Einstellungen öffnen'\n" +
                        "2. Aktiviere 'Aus dieser Quelle erlauben'\n" +
                        "3. Komm zurück zu BaumRadar\n" +
                        "4. Tippe erneut auf 'Aktualisieren'",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Diese Einstellung gilt nur für BaumRadar und ist jederzeit widerrufbar.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    showPermissionDialog = false
                    updateManager.openInstallPermissionSettings()
                }) {
                    Text("Einstellungen öffnen")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionDialog = false }) {
                    Text("Abbrechen")
                }
            }
        )
    }

    // --- Download-Fortschritt-Overlay ---
    if (isDownloading) {
        AlertDialog(
            onDismissRequest = { /* Nicht abbrechbar */ },
            title = { Text("Update wird heruntergeladen…") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (downloadProgress != null && downloadProgress!! >= 0) {
                        LinearProgressIndicator(
                            progress = { downloadProgress!! / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "${downloadProgress}%",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Bitte warten…",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            },
            confirmButton = {}
        )
    }

    // --- Daten-Update-Dialog: aktualisierte Baumdaten für bereits geladene Städte ---
    if (showDataUpdateDialog && staleCities.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showDataUpdateDialog = false },
            icon = {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text("Aktualisierte Baumdaten")
            },
            text = {
                Column {
                    Text(
                        "Für diese bereits geladenen Städte stehen aktualisierte Baumdaten bereit:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        staleCities.joinToString(", ") { it.name },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Jetzt herunterladen, um die neuesten Korrekturen und Bäume zu erhalten.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    showDataUpdateDialog = false
                    val toUpdate = staleCities
                    isRefreshingData = true
                    coroutineScope.launch {
                        for (c in toUpdate) {
                            dataRefreshStatus = c.name
                            cityManager.downloadAndMergeCity(c) { msg ->
                                dataRefreshStatus = "${c.name}: $msg"
                            }
                        }
                        isRefreshingData = false
                        dataRefreshStatus = ""
                    }
                }) {
                    Text("Jetzt aktualisieren")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDataUpdateDialog = false }) {
                    Text("Später")
                }
            }
        )
    }

    // --- Daten-Refresh-Overlay: läuft, während Städte neu geladen werden ---
    if (isRefreshingData) {
        AlertDialog(
            onDismissRequest = { /* Nicht abbrechbar */ },
            title = { Text("Baumdaten werden aktualisiert…") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        dataRefreshStatus.ifEmpty { "Bitte warten…" },
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                }
            },
            confirmButton = {}
        )
    }
}
