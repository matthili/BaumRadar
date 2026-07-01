package at.mafue.baumradar.app.ui

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import at.mafue.baumradar.app.R
import java.util.Locale

/**
 * Allergieprofil-Bildschirm mit erweiterbarer Baumgattungs-Liste.
 *
 * Zeigt alle verfügbaren Baumgattungen gruppiert an. Jede Gattung kann aufgeklappt
 * werden, um einzelne Arten auszuwählen. Pro Art gibt es zwei Optionen:
 * - **Warnung** (⚠️): Aktiviert Geofence-Benachrichtigungen bei Annäherung
 * - **Umfahren** (🚫): Gattung wird beim Routing gemieden
 *
 * Die Gattungs-Überschrift nutzt einen [TriStateCheckbox]:
 * - Alle Arten ausgewählt → On
 * - Einige Arten ausgewählt → Indeterminate
 * - Keine Art ausgewählt → Off
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen() {
    val context = LocalContext.current.applicationContext as Application
    val viewModel: ProfileViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return ProfileViewModel(context) as T
            }
        }
    )

    val filteredTrees by viewModel.filteredTrees.collectAsState()
    val selectedTrees by viewModel.selectedTrees.collectAsState()
    val warnTrees by viewModel.warnTrees.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()

    val currentIsEn = Locale.getDefault().language == "en"

    val expandedStates = remember { mutableStateMapOf<String, Boolean>() }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.tab_profile)) },
            actions = {
                if (selectedTrees.isNotEmpty()) {
                    TextButton(onClick = { viewModel.clearAllSelections() }) {
                        Text(
                            text = "Alle abwählen", 
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        )
        
        // Search Field
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { viewModel.searchQuery.value = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("Suche nach Gattung oder Art...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Suchen") },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { viewModel.searchQuery.value = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "Löschen")
                    }
                }
            },
            singleLine = true
        )

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            filteredTrees.forEach { group ->
                val hasSpecies = group.speciesList.isNotEmpty()
                val isExpanded = hasSpecies && (expandedStates[group.genusDe] == true || searchQuery.isNotBlank())
                // Auswahl ist gattungsweit – passend zu den nach genus_de geclusterten Geofences.
                val isWarned = warnTrees.contains(group.genusDe)
                val isAvoided = selectedTrees.contains(group.genusDe)

                item(key = "header_${group.genusDe}") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expandedStates[group.genusDe] = !(expandedStates[group.genusDe] == true) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            val primaryGenus = if (currentIsEn && group.genusEn.isNotBlank()) group.genusEn else group.genusDe
                            val secondaryGenus = if (currentIsEn) group.genusDe
                                                 else group.genusEn.takeIf { it.isNotBlank() && it != group.genusDe }
                            Text(text = primaryGenus, style = MaterialTheme.typography.titleMedium)
                            if (!secondaryGenus.isNullOrBlank()) {
                                Text(
                                    text = secondaryGenus,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Warnung (Geofence-Benachrichtigung) – für die gesamte Gattung
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(end = 8.dp)) {
                            Text("Warnung ⚠️", style = MaterialTheme.typography.labelSmall)
                            Checkbox(
                                checked = isWarned,
                                onCheckedChange = { viewModel.toggleWarnSelection(group.genusDe) }
                            )
                        }
                        // Umfahren (Routing) – für die gesamte Gattung
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(end = 4.dp)) {
                            Text("Umfahren 🚫", style = MaterialTheme.typography.labelSmall)
                            Checkbox(
                                checked = isAvoided,
                                onCheckedChange = { viewModel.toggleSpeciesSelection(group.genusDe) }
                            )
                        }

                        if (hasSpecies) {
                            Icon(
                                imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = if (isExpanded) "Einklappen" else "Ausklappen",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Spacer(modifier = Modifier.width(24.dp))
                        }
                    }
                    Divider()
                }

                if (isExpanded) {
                    // Eindeutiger Key aus Gattung + beiden Artnamen (die DAO-Query liefert DISTINCT-Tupel).
                    items(
                        group.speciesList,
                        key = { "sp_${group.genusDe}|${it.speciesDe ?: ""}|${it.speciesEn ?: ""}" }
                    ) { species ->
                        val primary = if (currentIsEn) (species.speciesEn?.takeIf { it.isNotBlank() } ?: species.speciesDe)
                                      else (species.speciesDe?.takeIf { it.isNotBlank() } ?: species.speciesEn)
                        val secondary = if (currentIsEn) species.speciesDe?.takeIf { it.isNotBlank() && it != primary }
                                        else species.speciesEn?.takeIf { it.isNotBlank() && it != primary }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 40.dp, end = 16.dp, top = 6.dp, bottom = 6.dp)
                        ) {
                            Text(text = "• ${primary ?: ""}", style = MaterialTheme.typography.bodyMedium)
                            if (!secondary.isNullOrBlank()) {
                                Text(
                                    text = secondary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 12.dp)
                                )
                            }
                        }
                        Divider(modifier = Modifier.padding(start = 40.dp))
                    }
                }
            }
        }
    }
}
