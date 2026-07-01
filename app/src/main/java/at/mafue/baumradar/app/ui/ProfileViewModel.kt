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
 * Gruppiert Baumarten nach deutscher Gattung (Genus) für die Profilansicht.
 *
 * Seit der Backend-Harmonisierung trägt jeder Baum eine saubere deutsche Gattung
 * ({@code genusDe}, z. B. "Ahorn") und – separat – seinen Artnamen ({@code speciesDe}
 * z. B. "Spitz-Ahorn", {@code speciesEn} = botanisch). Eine [GenusGroup] bündelt daher
 * alle Arten einer Gattung; die Allergie-Auswahl erfolgt auf Gattungsebene (passend zu
 * den nach {@code genus_de} geclusterten Geofences), die Artenliste ist informativ.
 *
 * @property genusDe     Deutscher Gattungsname (Auswahl-Schlüssel), z. B. "Ahorn"
 * @property genusEn     Englischer Gattungsname, z. B. "Maple"
 * @property speciesList Eindeutige Arten dieser Gattung (für die aufklappbare Detailansicht)
 */
data class GenusGroup(
    val genusDe: String,
    val genusEn: String,
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
                val matchesGenus = group.genusDe.lowercase().contains(lowerQuery) ||
                                   group.genusEn.lowercase().contains(lowerQuery)

                if (matchesGenus) {
                    // Gattung passt (z. B. "Ahorn"/"Maple") → alle Arten zeigen
                    group
                } else {
                    // Sonst nur die passenden Arten behalten (z. B. "Spitz-Ahorn")
                    val matchingChildren = group.speciesList.filter { s ->
                        s.speciesDe?.lowercase()?.contains(lowerQuery) == true ||
                        s.speciesEn?.lowercase()?.contains(lowerQuery) == true
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

            // Gruppierung nach der (bereits harmonisierten) deutschen Gattung, z. B. "Ahorn".
            val tempGrouped = validSpecies
                .groupBy { it.genusDe!! }
                .toSortedMap()

            val structuredGroups = tempGrouped.mapValues { (genusDe, list) ->
                GenusGroup(
                    genusDe = genusDe,
                    // genusEn ist pro Gattung konstant; ersten nicht-leeren Wert nehmen.
                    genusEn = list.firstOrNull { !it.genusEn.isNullOrBlank() }?.genusEn ?: genusDe,
                    // Nur echte Arten (mit deutschem oder botanischem Namen), eindeutig & sortiert.
                    speciesList = list
                        .filter { !it.speciesDe.isNullOrBlank() || !it.speciesEn.isNullOrBlank() }
                        .distinctBy { (it.speciesDe ?: "") + "|" + (it.speciesEn ?: "") }
                        .sortedBy { (it.speciesDe ?: it.speciesEn ?: "").lowercase() }
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
