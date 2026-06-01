package at.mafue.baumradar.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Repräsentiert einen Geofence-Cluster ("Hotspot") in der lokalen Datenbank.
 *
 * Ein Geofence-Cluster fasst mehrere nahe beieinanderstehende Bäume derselben
 * Gattung zu einem einzigen kreisförmigen Bereich zusammen. Dadurch wird die
 * Anzahl der beim Betriebssystem registrierten Geofences reduziert (Android
 * erlaubt maximal 100 pro App).
 *
 * @property id      Eindeutige Kennung des Clusters
 * @property lat     Breitengrad des Cluster-Mittelpunkts
 * @property lon     Längengrad des Cluster-Mittelpunkts
 * @property radius  Radius des Clusters in Metern
 * @property count   Anzahl der Bäume innerhalb dieses Clusters
 * @property genusDe Deutsche Bezeichnung der Baumgattung (z. B. "Birke")
 */
@Entity(tableName = "geofences")
data class GeofenceEntity(
    @PrimaryKey val id: String,
    val lat: Double,
    val lon: Double,
    val radius: Int,
    val count: Int,
    @ColumnInfo(name = "genus_de") val genusDe: String?
)
