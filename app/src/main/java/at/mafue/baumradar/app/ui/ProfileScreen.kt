package at.mafue.baumradar.app.ui

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Notifications
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
 * Zeigt alle verfügbaren Baumgattungen gruppiert an. Die Auswahl erfolgt
 * **gattungsweit** (passend zu den nach Gattung geclusterten Geofences); pro
 * Gattung gibt es zwei Schalter:
 * - **Warnung** (Glocken-Icon): Aktiviert Geofence-Benachrichtigungen bei Annäherung
 * - **Umfahren** (X-Icon): Gattung wird beim Routing gemieden
 *
 * Jede Gattung lässt sich aufklappen, um ihre Arten mit botanischem Namen
 * anzuzeigen (rein informativ – die Warnung/Umfahren-Auswahl bleibt gattungsweit).
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

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            filteredTrees.forEach { group ->
                val hasSpecies = group.speciesList.isNotEmpty()
                val isExpanded = hasSpecies && (expandedStates[group.genusDe] == true || searchQuery.isNotBlank())
                // Auswahl ist gattungsweit – passend zu den nach genus_de geclusterten Geofences.
                val isWarned = warnTrees.contains(group.genusDe)
                val isAvoided = selectedTrees.contains(group.genusDe)

                item(key = "genus_${group.genusDe}") {
                    // Jede Gattung als eigene, getönte, abgerundete Karte
                    Card(
                        modifier = Modifier.fillMaxWidth().animateContentSize(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { expandedStates[group.genusDe] = !(expandedStates[group.genusDe] == true) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
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
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Notifications,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text("Warnung", style = MaterialTheme.typography.labelSmall)
                                }
                                Checkbox(
                                    checked = isWarned,
                                    onCheckedChange = { viewModel.toggleWarnSelection(group.genusDe) }
                                )
                            }
                            // Umfahren (Routing) – für die gesamte Gattung
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(end = 4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Clear,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text("Umfahren", style = MaterialTheme.typography.labelSmall)
                                }
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

                        if (isExpanded) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            )
                            // Eindeutiger Key nicht mehr nötig (kein LazyColumn-items mehr); DISTINCT-Tupel bleiben stabil.
                            group.speciesList.forEachIndexed { index, species ->
                                val primary = if (currentIsEn) (species.speciesEn?.takeIf { it.isNotBlank() } ?: species.speciesDe)
                                              else (species.speciesDe?.takeIf { it.isNotBlank() } ?: species.speciesEn)
                                val secondary = if (currentIsEn) species.speciesDe?.takeIf { it.isNotBlank() && it != primary }
                                                else species.speciesEn?.takeIf { it.isNotBlank() && it != primary }
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 24.dp, end = 16.dp, top = 6.dp, bottom = 6.dp)
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
                                if (index < group.speciesList.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 24.dp, end = 16.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                    }
                }
            }
        }
    }
}
