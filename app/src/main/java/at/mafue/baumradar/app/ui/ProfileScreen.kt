package at.mafue.baumradar.app.ui

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
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
                            text = "Alle abwÃ¤hlen", 
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
                        Icon(Icons.Default.Clear, contentDescription = "LÃ¶schen")
                    }
                }
            },
            singleLine = true
        )

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            filteredTrees.forEach { group ->
                val isExpanded = expandedStates[group.genusLatin] == true || searchQuery.isNotBlank()
                val selectedCount = group.speciesList.count { it.genusDe?.let { de -> selectedTrees.contains(de) } == true }
                
                val triState = when {
                    selectedCount == 0 -> ToggleableState.Off
                    selectedCount == group.speciesList.size -> ToggleableState.On
                    else -> ToggleableState.Indeterminate
                }

                item(key = "header_${group.genusLatin}") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expandedStates[group.genusLatin] = !isExpanded }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TriStateCheckbox(
                            state = triState,
                            onClick = {
                                val newState = triState != ToggleableState.On
                                viewModel.toggleGenusGroup(group.genusLatin, newState)
                            }
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        
                        val title = if (group.genusTrivial.isNotEmpty()) {
                            "${group.genusLatin} (${group.genusTrivial})"
                        } else {
                            group.genusLatin
                        }
                        
                        Text(
                            text = title, 
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = if (isExpanded) "â–²" else "â–¼",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Divider()
                }

                if (isExpanded) {
                    items(group.speciesList, key = { it.genusDe ?: it.hashCode().toString() }) { species ->
                        val isSelected = species.genusDe?.let { selectedTrees.contains(it) } == true
                        val rawName = if (currentIsEn && !species.genusEn.isNullOrEmpty()) {
                            species.genusEn
                        } else {
                            species.genusDe ?: ""
                        }
                        
                        val displayName = if (rawName.contains("(") && !rawName.contains(")")) "$rawName)" else rawName

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { species.genusDe?.let { viewModel.toggleSpeciesSelection(it) } }
                                .padding(start = 48.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = { species.genusDe?.let { viewModel.toggleSpeciesSelection(it) } }
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(text = displayName, style = MaterialTheme.typography.bodyLarge)
                        }
                        Divider(modifier = Modifier.padding(start = 48.dp))
                    }
                }
            }
        }
    }
}
