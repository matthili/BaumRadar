package at.mafue.baumradar.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

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

    // Keep only the most recent N items
    @JvmSuppressWildcards
    @Query("DELETE FROM route_history WHERE id NOT IN (SELECT id FROM route_history ORDER BY timestamp DESC LIMIT :limit)")
    suspend fun trimHistory(limit: Int): Int
}
