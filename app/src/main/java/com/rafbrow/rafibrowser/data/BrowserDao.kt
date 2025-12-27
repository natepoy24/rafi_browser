package com.rafbrow.rafibrowser.data

import androidx.room.*

@Dao
interface BrowserDao {
    @Query("SELECT * FROM browser_data WHERE type = 'BOOKMARK' ORDER BY timestamp DESC")
    suspend fun getBookmarks(): List<BrowserData>

    @Query("SELECT * FROM history_table ORDER BY timestamp DESC LIMIT 100")
    suspend fun getAllHistory(): List<HistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(history: HistoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBrowserData(data: BrowserData)

    @Query("DELETE FROM history_table WHERE id = :id")
    suspend fun deleteHistoryItem(id: Int)

    @Query("DELETE FROM browser_data WHERE id = :id")
    suspend fun deleteBrowserDataItem(id: Int)

    @Query("DELETE FROM history_table")
    suspend fun clearHistory()

    @Insert
    suspend fun insertPassword(data: PasswordData)

    @Query("SELECT * FROM passwords")
    suspend fun getAllPasswords(): List<PasswordData>

    @Query("SELECT * FROM passwords WHERE site LIKE '%' || :site || '%' LIMIT 1")
    suspend fun getPasswordForSite(site: String): PasswordData?

    @Insert
    suspend fun insertDownload(data: DownloadData)

    @Query("SELECT * FROM downloads ORDER BY timestamp DESC")
    suspend fun getAllDownloads(): List<DownloadData>
}
