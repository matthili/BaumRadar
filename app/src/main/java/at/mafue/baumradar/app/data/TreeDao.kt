package at.mafue.baumradar.app.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.ColumnInfo
import kotlinx.coroutines.flow.Flow

@Dao
interface TreeDao {
    @Query("SELECT * FROM trees")
    fun getAllTrees(): Flow<List<TreeEntity>>

    @JvmSuppressWildcards
    @Query("SELECT DISTINCT genus_de, genus_en, species_de, species_en FROM trees WHERE genus_de IS NOT NULL AND genus_de != ''")
    suspend fun getAvailableSpecies(): List<TreeSpeciesDTO>
    
    @Query("SELECT * FROM trees WHERE lat BETWEEN :minLat AND :maxLat AND lon BETWEEN :minLon AND :maxLon")
    fun getTreesInBoundingBox(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): Flow<List<TreeEntity>>

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
}

data class TreeSpeciesDTO(
    @ColumnInfo(name = "genus_de") val genusDe: String?,
    @ColumnInfo(name = "genus_en") val genusEn: String?,
    @ColumnInfo(name = "species_de") val speciesDe: String?,
    @ColumnInfo(name = "species_en") val speciesEn: String?
)
