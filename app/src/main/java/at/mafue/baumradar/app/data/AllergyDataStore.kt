package at.mafue.baumradar.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Extension-Property, das einen Preferences-DataStore an den Application-Context bindet.
 *
 * DataStore ist der moderne Ersatz für SharedPreferences und bietet typsicheren,
 * asynchronen Zugriff über Kotlin Flows. Der Name "allergy_profile" bestimmt den
 * Dateinamen auf dem Dateisystem.
 */
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "allergy_profile")

/**
 * Persistiert das Allergieprofil des Nutzers mittels Jetpack DataStore.
 *
 * Es gibt zwei getrennte Auswahllisten:
 * - **selectedTrees** ("Umfahren"): Baumgattungen, die beim Routing gemieden werden sollen.
 *   Diese Gattungen werden als Allergie-Hotspots auf der Karte markiert und bei der
 *   Routenberechnung als Hindernisse behandelt.
 * - **warnTrees** ("Warnung"): Baumgattungen, für die Geofence-Benachrichtigungen
 *   aktiviert sind. Nähert sich der Nutzer einem dieser Bäume, erscheint eine
 *   Push-Benachrichtigung.
 *
 * Beide Listen speichern die deutschen Gattungsnamen (genusDe) als String-Set.
 */
class AllergyDataStore(private val context: Context) {

    /** Schlüssel für die Menge der zum Umfahren ausgewählten Baumgattungen. */
    private val SELECTED_TREES = stringSetPreferencesKey("selected_trees")
    /** Schlüssel für die Menge der Baumgattungen mit aktivierter Geofence-Warnung. */
    private val WARN_TREES = stringSetPreferencesKey("warn_trees")

    val selectedTreesFlow: Flow<Set<String>> = context.dataStore.data
        .map { preferences ->
            preferences[SELECTED_TREES] ?: emptySet()
        }

    val warnTreesFlow: Flow<Set<String>> = context.dataStore.data
        .map { preferences ->
            preferences[WARN_TREES] ?: emptySet()
        }

    suspend fun saveSelectedTrees(trees: Set<String>) {
        context.dataStore.edit { preferences ->
            preferences[SELECTED_TREES] = trees
        }
    }

    suspend fun saveWarnTrees(trees: Set<String>) {
        context.dataStore.edit { preferences ->
            preferences[WARN_TREES] = trees
        }
    }
}
