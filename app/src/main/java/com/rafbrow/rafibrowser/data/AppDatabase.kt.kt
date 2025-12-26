package com.rafbrow.rafibrowser.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [HistoryEntity::class, BrowserData::class, PasswordData::class, DownloadData::class],
    version = 5,
    exportSchema = false
)
abstract class `AppDatabase.kt` : RoomDatabase() {
    abstract fun browserDao(): BrowserDao

    companion object {
        @Volatile private var INSTANCE: `AppDatabase.kt`? = null
        fun getDatabase(context: Context): `AppDatabase.kt` {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    `AppDatabase.kt`::class.java, "rafi_browser_db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}