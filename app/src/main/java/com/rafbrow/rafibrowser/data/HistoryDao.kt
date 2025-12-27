package com.rafbrow.rafibrowser.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history_table ORDER BY timestamp DESC LIMIT 100")
    suspend fun getAllHistory(): List<HistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(history: HistoryEntity)

    @Query("DELETE FROM history_table")
    suspend fun clearHistory()

    @Query("DELETE FROM history_table WHERE id = :id")
    suspend fun deleteHistoryItem(id: Int)
}
