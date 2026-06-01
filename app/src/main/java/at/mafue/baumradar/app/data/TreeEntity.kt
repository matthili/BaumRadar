package at.mafue.baumradar.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Repräsentiert einen einzelnen Baum in der lokalen Room-Datenbank.
 *
 * Jeder Datensatz stammt aus einer heruntergeladenen Städte-Datenbank und enthält
 * die geographischen Koordinaten sowie die botanische Klassifikation (Gattung und Art)
 * in deutscher und englischer Sprache.
 *
 * Das Feld [cityId] ermöglicht es, die Baumdaten einer bestimmten Stadt zuzuordnen,
 * um beim Löschen oder Aktualisieren gezielt nur die Daten einer Stadt zu entfernen.
 *
 * Gattung (genus) und Art (species) sind nullable, da nicht alle kommunalen
 * Baumkataster vollständige taxonomische Daten liefern.
 */
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
