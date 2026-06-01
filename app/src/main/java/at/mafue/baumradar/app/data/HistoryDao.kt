package at.mafue.baumradar.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object für die Routenverlauf-Tabelle.
 *
 * Verwaltet die letzten N Routen-Suchanfragen des Nutzers. Alte Einträge
 * werden durch [trimHistory] automatisch entfernt, sodass die Tabelle
 * eine feste Maximalgröße nicht überschreitet.
 */
@Dao
interface HistoryDao {
    @Query("SELECT * FROM route_history ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentHistory(limit: Int): Flow<List<RouteHistoryEntity>>

    @JvmSuppressWildcards
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(history: RouteHistoryEntity): Long

    @JvmSuppressWildcards
    @Query("DELETE FROM route_history")
    suspend fun clearHistory(): Int

    /**
     * Behält nur die [limit] neuesten Einträge und löscht alle älteren.
     *
     * Die Unterabfrage selektiert die IDs der neuesten Einträge (sortiert nach
     * Zeitstempel absteigend). Alle Einträge, deren ID nicht in dieser Teilmenge
     * enthalten ist, werden gelöscht. Gibt die Anzahl der gelöschten Zeilen zurück.
     */
    @JvmSuppressWildcards
    @Query("DELETE FROM route_history WHERE id NOT IN (SELECT id FROM route_history ORDER BY timestamp DESC LIMIT :limit)")
    suspend fun trimHistory(limit: Int): Int
}
