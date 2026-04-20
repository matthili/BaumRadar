package at.mafue.baumradar.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import at.mafue.baumradar.app.data.AllergyDataStore
import at.mafue.baumradar.app.data.AppDatabase
import at.mafue.baumradar.app.data.TreeSpeciesDTO
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class GenusGroup(
    val genusLatin: String,
    val genusTrivial: String,
    val speciesList: List<TreeSpeciesDTO>
)

class ProfileViewModel(application: Application) : AndroidViewModel(application) {
    private val allergyDataStore = AllergyDataStore(application)
    private val db = AppDatabase.getInstance(application)
    
    // Internal cache of all groups
    private val _groupedTrees = MutableStateFlow<Map<String, GenusGroup>>(emptyMap())

    val searchQuery = MutableStateFlow("")

    val filteredTrees: StateFlow<List<GenusGroup>> = combine(_groupedTrees, searchQuery) { groupedMap, query ->
        if (query.isBlank()) {
            groupedMap.values.toList()
        } else {
            val lowerQuery = query.trim().lowercase()
            groupedMap.values.mapNotNull { group ->
                val matchesParent = group.genusLatin.lowercase().contains(lowerQuery) ||
                                    group.genusTrivial.lowercase().contains(lowerQuery)
                
                if (matchesParent) {
                    // Wenn die Haupt-Gattung passt (z.B. "Acer"), zeige alle Unterarten
                    group
                } else {
                    // Wenn nur spezielle Unterarten passen (z.B. "Eschen-Ahorn"), filtere den Rest der Gattung weg
                    val matchingChildren = group.speciesList.filter { s ->
                        s.genusDe?.lowercase()?.contains(lowerQuery) == true ||
                        s.speciesDe?.lowercase()?.contains(lowerQuery) == true
                    }
                    
                    if (matchingChildren.isNotEmpty()) {
                        group.copy(speciesList = matchingChildren)
                    } else {
                        null
                    }
                }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedTrees = MutableStateFlow<Set<String>>(emptySet())
    val selectedTrees: StateFlow<Set<String>> = _selectedTrees

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            val speciesList = db.treeDao().getAvailableSpecies()
            
            // 1. Data Sanitization (Filter out known garbage entries)
            val invalidPrefixes = setOf(
                "baumgruppe", "laubbaum", "obstbaum", "nicht", "jungbaum", "nadelbaum", "wald"
            )

            val validSpecies = speciesList.filter { species ->
                val name = species.genusDe?.lowercase() ?: ""
                name.isNotBlank() && invalidPrefixes.none { name.startsWith(it) }
            }

            // 2. Group by Latin Genus and Extract Trivial Names
            val tempGrouped = validSpecies
                .groupBy { it.genusDe!!.split(" ").first() }
                .toSortedMap()

            val structuredGroups = tempGrouped.mapValues { (latinGenus, list) ->
                val trivialName = TaxonomyUtils.extractTrivialName(list)
                GenusGroup(
                    genusLatin = latinGenus,
                    genusTrivial = trivialName,
                    speciesList = list
                )
            }
            
            _groupedTrees.value = structuredGroups
            _selectedTrees.value = allergyDataStore.selectedTreesFlow.first()
        }
    }

    // Removed internal extractTrivialName, using TaxonomyUtils instead

    fun toggleSpeciesSelection(speciesId: String) {
        val current = _selectedTrees.value.toMutableSet()
        if (current.contains(speciesId)) {
            current.remove(speciesId)
        } else {
            current.add(speciesId)
        }
        _selectedTrees.value = current
        viewModelScope.launch {
            allergyDataStore.saveSelectedTrees(current)
        }
    }

    fun toggleGenusGroup(genusLatin: String, isChecked: Boolean) {
        val current = _selectedTrees.value.toMutableSet()
        val group = _groupedTrees.value[genusLatin] ?: return
        
        group.speciesList.forEach { child ->
            child.genusDe?.let { 
                if (isChecked) {
                    current.add(it)
                } else {
                    current.remove(it)
                }
            }
        }
        
        _selectedTrees.value = current
        viewModelScope.launch {
            allergyDataStore.saveSelectedTrees(current)
        }
    }

    fun clearAllSelections() {
        val empty = emptySet<String>()
        _selectedTrees.value = empty
        viewModelScope.launch {
            allergyDataStore.saveSelectedTrees(empty)
        }
    }
}
