package at.mafue.baumradar.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "allergy_profile")

class AllergyDataStore(private val context: Context) {

    private val SELECTED_TREES = stringSetPreferencesKey("selected_trees")
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
