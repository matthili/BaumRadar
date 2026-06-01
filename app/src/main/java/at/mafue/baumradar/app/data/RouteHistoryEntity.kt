package at.mafue.baumradar.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Speichert eine einzelne Routen-Suchanfrage im Verlauf.
 *
 * Enthält sowohl die Textadressen (für die Anzeige im Verlaufs-Dropdown) als auch
 * die aufgelösten Koordinaten (für die sofortige Neuberechnung ohne erneutes
 * Geocoding). Der [timestamp] dient der chronologischen Sortierung.
 */
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
