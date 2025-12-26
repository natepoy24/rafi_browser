package com.rafbrow.rafibrowser.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [HistoryEntity::class, BrowserData::class, PasswordData::class, DownloadData::class],
    version = 7, // Naikkan versi ke 7 untuk mereset cache
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() { // Nama kelas harus bersih tanpa titik
    abstract fun browserDao(): BrowserDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java, "rafi_browser_db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
