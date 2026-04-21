package at.mafue.baumradar.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "geofences")
data class GeofenceEntity(
    @PrimaryKey val id: String,
    val lat: Double,
    val lon: Double,
    val radius: Int,
    val count: Int,
    @ColumnInfo(name = "genus_de") val genusDe: String?
)
