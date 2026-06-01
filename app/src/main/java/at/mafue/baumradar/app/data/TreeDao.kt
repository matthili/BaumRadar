package at.mafue.baumradar.app.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.ColumnInfo
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object (DAO) für Baum- und Geofence-Abfragen.
 *
 * Stellt reaktive Abfragen (via [Flow]) für die Kartenansicht und suspending
 * Abfragen für einmalige Zugriffe (z. B. Routing, Geofence-Registrierung) bereit.
 *
 * Hinweis: `@JvmSuppressWildcards` ist an mehreren Methoden nötig, weil Room
 * bei generischen Kotlin-Typen (z. B. `List<String>`) sonst Wildcard-Typen
 * generiert, die der Annotation-Processor nicht verarbeiten kann.
 */
@Dao
interface TreeDao {
    @Query("SELECT * FROM trees")
    fun getAllTrees(): Flow<List<TreeEntity>>

    /** Liefert alle eindeutigen Baumarten mit gültiger deutscher Gattungsbezeichnung. */
    @JvmSuppressWildcards
    @Query("SELECT DISTINCT genus_de, genus_en, species_de, species_en FROM trees WHERE genus_de IS NOT NULL AND genus_de != ''")
    suspend fun getAvailableSpecies(): List<TreeSpeciesDTO>
    
    /**
     * Liefert alle Bäume innerhalb einer Bounding Box als reaktiven Flow.
     *
     * Die Bounding-Box-Filterung ist eine schnelle Vorab-Eingrenzung; die
     * exakte Entfernungsprüfung (Haversine) erfolgt anschließend im ViewModel.
     */
    @Query("SELECT * FROM trees WHERE lat BETWEEN :minLat AND :maxLat AND lon BETWEEN :minLon AND :maxLon")
    fun getTreesInBoundingBox(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): Flow<List<TreeEntity>>

    /**
     * Liefert Geofence-Cluster innerhalb einer Bounding Box,
     * gefiltert auf die allergenen Gattungen des Nutzers.
     *
     * Wird beim Routing verwendet, um relevante Allergie-Hotspots
     * entlang der geplanten Route zu identifizieren.
     */
    @JvmSuppressWildcards
    @Query("SELECT * FROM geofences WHERE genus_de IN (:allergicGenuses) AND lat BETWEEN :minLat AND :maxLat AND lon BETWEEN :minLon AND :maxLon")
    suspend fun getGeofencesInBoundingBox(
        allergicGenuses: List<String>,
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double
    ): List<GeofenceEntity>

    @JvmSuppressWildcards
    @Query("SELECT * FROM geofences WHERE genus_de IN (:allergicGenuses) AND lat BETWEEN :minLat AND :maxLat AND lon BETWEEN :minLon AND :maxLon")
    fun getGeofencesInBoundingBoxFlow(
        allergicGenuses: List<String>,
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double
    ): Flow<List<GeofenceEntity>>

    /**
     * Liefert die [limit] nächstgelegenen allergenen Geofence-Cluster.
     *
     * Die Sortierung nutzt eine euklidische Distanz-Näherung
     * `(Δlat² + Δlon²)` anstelle der exakten Haversine-Formel.
     * Bei kleinen Entfernungen und mittleren Breitengraden (z. B. Wien ≈ 48°N)
     * ist diese Näherung ausreichend genau und deutlich performanter für die
     * Datenbank-Sortierung.
     *
     * Wird primär vom [GeofenceManager] genutzt, um die 99 nächsten Hotspots
     * beim Betriebssystem zu registrieren.
     */
    @JvmSuppressWildcards
    @Query("SELECT * FROM geofences WHERE genus_de IN (:allergicGenuses) ORDER BY ((lat - :lat)*(lat - :lat) + (lon - :lon)*(lon - :lon)) ASC LIMIT :limit")
    suspend fun getClosestGeofences(
        allergicGenuses: List<String>,
        lat: Double,
        lon: Double,
        limit: Int
    ): List<GeofenceEntity>
}

/**
 * Projektions-DTO für die Abfrage verfügbarer Baumarten.
 *
 * Enthält nur die taxonomischen Felder ohne Koordinaten oder IDs, da in der
 * Profilansicht nur die Artenliste – nicht die einzelnen Standorte – benötigt wird.
 * Room erzeugt diese Projektion direkt aus der SQL-SELECT-Klausel.
 */
data class TreeSpeciesDTO(
    @ColumnInfo(name = "genus_de") val genusDe: String?,
    @ColumnInfo(name = "genus_en") val genusEn: String?,
    @ColumnInfo(name = "species_de") val speciesDe: String?,
    @ColumnInfo(name = "species_en") val speciesEn: String?
)
