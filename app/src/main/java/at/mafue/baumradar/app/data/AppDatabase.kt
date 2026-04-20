package at.mafue.baumradar.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [TreeEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun treeDao(): TreeDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null



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
