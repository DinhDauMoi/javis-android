package com.dinh.javis.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Cơ sở dữ liệu Room cục bộ của JAVIS
 */
@Database(entities = [CustomCommand::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun customCommandDao(): CustomCommandDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "javis_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
