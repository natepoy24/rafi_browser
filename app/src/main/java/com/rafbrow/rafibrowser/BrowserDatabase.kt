package com.rafbrow.rafibrowser

import android.content.Context
import androidx.room.*

// TABEL UNTUK HISTORY & BOOKMARK
@Entity(tableName = "browser_data")
data class BrowserData(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "url") val url: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "type") val type: String, // "HISTORY" atau "BOOKMARK"
    @ColumnInfo(name = "timestamp") val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "passwords")
data class PasswordData(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "site") val site: String,
    @ColumnInfo(name = "username") val username: String,
    @ColumnInfo(name = "password") val password: String
)

@Entity(tableName = "downloads")
data class DownloadData(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "file_name") val fileName: String,
    @ColumnInfo(name = "url") val url: String,
    @ColumnInfo(name = "timestamp") val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface BrowserDao {
    @Query("SELECT * FROM browser_data WHERE type = 'HISTORY' ORDER BY timestamp DESC LIMIT 100")
    suspend fun getHistory(): List<BrowserData>

    @Query("SELECT * FROM browser_data WHERE type = 'BOOKMARK' ORDER BY timestamp DESC")
    suspend fun getBookmarks(): List<BrowserData>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(data: BrowserData)

    @Query("DELETE FROM browser_data WHERE type = 'HISTORY'")
    suspend fun clearHistory()

    // --- PASSWORD MANAGER ---
    @Insert
    suspend fun insertPassword(data: PasswordData)

    @Query("SELECT * FROM passwords")
    suspend fun getAllPasswords(): List<PasswordData>

    @Query("SELECT * FROM passwords WHERE site = :site LIMIT 1")
    suspend fun getPasswordForSite(site: String): PasswordData?

    @Query("DELETE FROM passwords WHERE id = :id")
    suspend fun deletePassword(id: Int)

    @Insert
    suspend fun insertDownload(data: DownloadData)

    @Query("SELECT * FROM downloads ORDER BY timestamp DESC")
    suspend fun getAllDownloads(): List<DownloadData>
}

@Database(entities = [BrowserData::class, PasswordData::class, DownloadData::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun browserDao(): BrowserDao
    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(context.applicationContext,
                    AppDatabase::class.java, "rafi_browser_db")
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}