package at.mafue.baumradar.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import at.mafue.baumradar.app.data.AllergyDataStore
import at.mafue.baumradar.app.data.AppDatabase
import at.mafue.baumradar.app.data.TreeSpeciesDTO
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import at.mafue.baumradar.app.background.GeofenceManager
import com.google.android.gms.location.LocationServices
import android.annotation.SuppressLint

/**
 * Gruppiert Baumarten nach botanischer Gattung (Genus) für die Profilansicht.
 *
 * Jede Gattung (z. B. "Acer") enthält eine Liste ihrer Unterarten (z. B. "Acer platanoides").
 * Der Trivialname (z. B. "Ahorn") wird aus den Klammer-Bezeichnungen der Artenliste extrahiert.
 */
data class GenusGroup(
    val genusLatin: String,
    val genusTrivial: String,
    val speciesList: List<TreeSpeciesDTO>
)

/**
 * ViewModel für die Allergieprofil-Ansicht.
 *
 * Verwaltet die Auswahl allergener Baumgattungen in zwei Kategorien:
 * - **Umfahren** (selectedTrees): Gattungen werden beim Routing gemieden
 * - **Warnung** (warnTrees): Geofence-Benachrichtigungen sind aktiviert
 *
 * Die Baumarten werden nach lateinischer Gattung gruppiert und mit einem
 * Suchfilter versehen. Änderungen an der Warn-Auswahl lösen sofort eine
 * Neuregistrierung der Geofences aus.
 */
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

    private val _warnTrees = MutableStateFlow<Set<String>>(emptySet())
    val warnTrees: StateFlow<Set<String>> = _warnTrees

    init {
        loadData()
    }

    /**
     * Lädt und strukturiert die verfügbaren Baumarten aus der Datenbank.
     *
     * Ablauf:
     * 1. Alle Arten mit gültigem deutschen Gattungsnamen laden
     * 2. Ungültige Einträge herausfiltern (z. B. "Baumgruppe", "Laubbaum")
     * 3. Nach lateinischer Gattung gruppieren
     * 4. Trivialnamen aus Klammerausdrücken extrahieren (z. B. "spec. (Ahorn)" → "Ahorn")
     */
    private fun loadData() {
        viewModelScope.launch {
            val speciesList = db.treeDao().getAvailableSpecies()
            
            // Datenbereinigung: Bestimmte Einträge in kommunalen Baumkatastern sind
            // keine echten Gattungsnamen, sondern Platzhalterkategorien. Diese werden
            // hier anhand bekannter Präfixe herausgefiltert.
            val invalidPrefixes = setOf(
                "baumgruppe", "laubbaum", "obstbaum", "nicht", "jungbaum", "nadelbaum", "wald"
            )

            val validSpecies = speciesList.filter { species ->
                val name = species.genusDe?.lowercase() ?: ""
                name.isNotBlank() && invalidPrefixes.none { name.startsWith(it) }
            }

            // Gruppierung: Das erste Wort des deutschen Gattungsnamens ist der
            // lateinische Gattungsname (z. B. "Acer platanoides (Spitz-Ahorn)" → "Acer")
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
            _warnTrees.value = allergyDataStore.warnTreesFlow.first()
        }
    }

    /** Schaltet die Routing-Vermeidung für eine einzelne Baumart um. */
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

    fun toggleWarnSelection(speciesId: String) {
        val current = _warnTrees.value.toMutableSet()
        if (current.contains(speciesId)) {
            current.remove(speciesId)
        } else {
            current.add(speciesId)
        }
        _warnTrees.value = current
        viewModelScope.launch {
            allergyDataStore.saveWarnTrees(current)
            updateGeofencesBackground()
        }
    }

    /**
     * Schaltet alle Arten einer Gattung gleichzeitig ein oder aus.
     * Ermöglicht z. B. "alle Birken auf einmal auswählen".
     */
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
        _warnTrees.value = empty
        viewModelScope.launch {
            allergyDataStore.saveSelectedTrees(empty)
            allergyDataStore.saveWarnTrees(empty)
            updateGeofencesBackground()
        }
    }

    /**
     * Löst im Hintergrund eine Neuregistrierung der Geofences aus.
     *
     * Wird nach jeder Änderung der Warn-Auswahl aufgerufen, damit die
     * Geofences beim Betriebssystem aktualisiert werden, ohne dass der
     * Nutzer die App neu starten muss.
     */
    @SuppressLint("MissingPermission")
    private fun updateGeofencesBackground() {
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(getApplication<Application>())
        fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
            if (loc != null) {
                viewModelScope.launch {
                    val manager = GeofenceManager(getApplication())
                    manager.updateGeofences(loc)
                }
            }
        }
    }
}
