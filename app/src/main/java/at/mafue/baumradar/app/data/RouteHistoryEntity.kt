package at.mafue.baumradar.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "route_history")
data class RouteHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val startAddress: String,
    val endAddress: String,
    val startLat: Double,
    val startLon: Double,
    val endLat: Double,
    val endLon: Double,
    val timestamp: Long
)
