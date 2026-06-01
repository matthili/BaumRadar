package at.mafue.baumradar.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Zentrale Room-Datenbank der App.
 *
 * Enthält drei Entitäten:
 * - [TreeEntity] – Einzelne Baumstandorte mit Gattungs- und Artinformationen
 * - [GeofenceEntity] – Geofence-Cluster (Hotspots), die mehrere Bäume zusammenfassen
 * - [RouteHistoryEntity] – Gespeicherte Routen-Suchanfragen des Nutzers
 *
 * Die Datenbank wird per Singleton-Muster verwaltet ([getInstance]).
 * Neue Städte-Daten werden nicht per Room-Migration eingespielt, sondern
 * über `ATTACH DATABASE` aus heruntergeladenen SQLite-Dateien gemergt
 * (siehe [at.mafue.baumradar.app.data.CityManager.downloadAndMergeCity]).
 *
 * `fallbackToDestructiveMigration` ist bewusst aktiviert, da die Baumdaten
 * jederzeit erneut vom Server geladen werden können und kein Datenverlust
 * kritisch ist.
 */
@Database(entities = [TreeEntity::class, GeofenceEntity::class, RouteHistoryEntity::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun treeDao(): TreeDao
    abstract fun historyDao(): HistoryDao

    companion object {
        /** Thread-sicher gespeicherte Singleton-Instanz. @Volatile garantiert Sichtbarkeit über Threads hinweg. */
        @Volatile
        private var INSTANCE: AppDatabase? = null



        /**
         * Gibt die Singleton-Instanz der Datenbank zurück.
         *
         * Nutzt Double-Checked Locking: Zuerst wird ohne Sperre geprüft, ob
         * die Instanz bereits existiert. Nur wenn nicht, wird synchronisiert
         * und ggf. eine neue Instanz erzeugt. Das minimiert die Kosten der
         * Synchronisation im Regelfall.
         */
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                // If it doesn't exist, we just create a normal one. It will be empty until mounted from file.
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "trees_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
