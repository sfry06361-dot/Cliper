package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ProjectEntity::class], version = 1, exportSchema = false)
abstract class ClipCraftDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao

    companion object {
        @Volatile
        private var instance: ClipCraftDatabase? = null

        fun getInstance(context: Context): ClipCraftDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ClipCraftDatabase::class.java,
                    "clipcraft_projects.db"
                ).build().also { instance = it }
            }
        }
    }
}
