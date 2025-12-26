package com.rafbrow.rafibrowser.data

import androidx.room.*

// Entity untuk Bookmark & Fitur Lainnya
@Entity(tableName = "browser_extras")
data class BrowserExtra(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "url") val url: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "type") val type: String // "BOOKMARK"
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
    // Bookmark
    @Query("SELECT * FROM browser_extras WHERE type = 'BOOKMARK' ORDER BY id DESC")
    suspend fun getBookmarks(): List<BrowserExtra>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExtra(data: BrowserExtra)

    // Password Manager
    @Insert
    suspend fun insertPassword(data: PasswordData)

    @Query("SELECT * FROM passwords")
    suspend fun getAllPasswords(): List<PasswordData>

    @Query("SELECT * FROM passwords WHERE site = :site LIMIT 1")
    suspend fun getPasswordForSite(site: String): PasswordData?

    // Download Manager
    @Insert
    suspend fun insertDownload(data: DownloadData)

    @Query("SELECT * FROM downloads ORDER BY timestamp DESC")
    suspend fun getAllDownloads(): List<DownloadData>
}
