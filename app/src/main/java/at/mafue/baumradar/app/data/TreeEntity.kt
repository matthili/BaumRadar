package at.mafue.baumradar.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "trees")
data class TreeEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "city_id") val cityId: String,
    val lat: Double,
    val lon: Double,
    @ColumnInfo(name = "genus_de") val genusDe: String?,
    @ColumnInfo(name = "genus_en") val genusEn: String?,
    @ColumnInfo(name = "species_de") val speciesDe: String?,
    @ColumnInfo(name = "species_en") val speciesEn: String?
)
