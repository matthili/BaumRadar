package at.mafue.baumradar.app.ui

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.animateContentSize

/**
 * Bildschirm zur Verwaltung heruntergeladener Städte-Baumdaten.
 *
 * Wird in zwei Modi verwendet:
 * - **Wizard-Modus** (`isWizard = true`): Ersteinrichtung nach der Installation.
 *   Zeigt eine Begrüßungsnachricht und einen "Weiter"-Button.
 * - **Einstellungs-Modus** (`isWizard = false`): Spätere Verwaltung über den Städte-Tab.
 *   Zeigt zusätzlich einen "Zur Stadt springen"-Button.
 *
 * Die verfügbaren Städte werden nach Ländern gruppiert und können per Toggle
 * heruntergeladen oder gelöscht werden. Während eines Downloads wird ein
 * modales Overlay mit Fortschrittsanzeige eingeblendet.
 *
 * @param isWizard       true = Ersteinrichtungs-Modus, false = Einstellungs-Modus
 * @param onWizardComplete Callback bei Abschluss des Wizards
 * @param onJumpToCity   Callback zum Navigieren zur gewählten Stadt auf der Karte
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CitySelectionScreen(
    isWizard: Boolean, 
    onWizardComplete: () -> Unit,
    onJumpToCity: (at.mafue.baumradar.app.data.CityCatalogEntry) -> Unit = {}
) {
    val context = LocalContext.current.applicationContext as Application
    val viewModel: CitySelectionViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return CitySelectionViewModel(context) as T
            }
        }
    )

    val catalog by viewModel.catalog.collectAsState()
    val downloaded by viewModel.downloadedCities.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(if (isWizard) "Willkommen bei BaumRadar" else "Städte verwalten") })
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                Column {
                    if (isWizard) {
                        Text(
                            text = "Bitte wähle mindestens eine Stadt aus, für die du die Baum-Daten herunterladen möchtest.",
                            modifier = Modifier.padding(16.dp)
                        )
                    }

                    // Katalog nach Ländern gruppieren. Die Länder-Reihenfolge folgt bewusst dem
                    // ersten Auftreten in catalog.json (Aufnahme-Historie) – groupBy behält die
                    // Schlüssel-Reihenfolge. Die Städte je Land werden alphabetisch aufsteigend
                    // sortiert (deutsche Collation, damit Umlaute korrekt einsortiert werden).
                    val cityCollator = remember { java.text.Collator.getInstance(java.util.Locale.GERMAN) }
                    val groupedCatalog = catalog.groupBy { it.country }
                        .mapValues { (_, cities) -> cities.sortedWith(compareBy(cityCollator) { it.name }) }
                    val expandedCountries = remember { mutableStateListOf<String>() }

                    // Beim ersten Laden alle Länder-Sektionen aufklappen
                    LaunchedEffect(groupedCatalog) {
                        if (expandedCountries.isEmpty() && groupedCatalog.isNotEmpty()) {
                            expandedCountries.addAll(groupedCatalog.keys)
                        }
                    }

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        groupedCatalog.forEach { (country, cities) ->
                            val isExpanded = expandedCountries.contains(country)
                            item(key = "country_$country") {
                                Column(modifier = Modifier.animateContentSize()) {
                                    // Sektions-Label: schlanker, farbiger Text statt grauem Vollbreiten-Balken
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                if (isExpanded) expandedCountries.remove(country)
                                                else expandedCountries.add(country)
                                            }
                                            .padding(horizontal = 4.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = country,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Icon(
                                            imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                            contentDescription = if (isExpanded) "Einklappen" else "Ausklappen",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    if (isExpanded) {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        // Getönte, abgerundete Karte für alle Städte eines Landes
                                        Card(
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(20.dp),
                                            colors = CardDefaults.cardColors(
                                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                                            )
                                        ) {
                                            cities.forEachIndexed { index, city ->
                                                val isDownloaded = downloaded.contains(city.id)
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = city.name,
                                                            style = MaterialTheme.typography.titleMedium
                                                        )
                                                        Text(
                                                            text = if (isDownloaded) "Installiert" else "Nicht installiert",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = if (isDownloaded) MaterialTheme.colorScheme.primary
                                                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }

                                                    if (isDownloaded && !isWizard) {
                                                        IconButton(onClick = { onJumpToCity(city) }) {
                                                            Icon(Icons.Default.Place, contentDescription = "Zur Stadt springen")
                                                        }
                                                    }
                                                    Switch(
                                                        checked = isDownloaded,
                                                        onCheckedChange = { viewModel.toggleCity(city) },
                                                        enabled = downloadProgress == null
                                                    )
                                                }
                                                if (index < cities.lastIndex) {
                                                    HorizontalDivider(
                                                        modifier = Modifier.padding(horizontal = 16.dp),
                                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (isWizard) {
                        Button(
                            onClick = onWizardComplete,
                            enabled = downloaded.isNotEmpty() && downloadProgress == null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Text("Weiter")
                        }
                    }
                }
            }

            // Modales Download-Overlay: Blockiert die gesamte UI während eines Downloads,
            // um versehentliche Doppel-Downloads zu verhindern
            if (downloadProgress != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(text = downloadProgress ?: "")
                    }
                }
            }
        }
    }
}
