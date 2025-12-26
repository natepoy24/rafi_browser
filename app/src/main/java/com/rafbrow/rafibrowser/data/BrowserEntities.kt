package com.rafbrow.rafibrowser.data

import androidx.room.*

@Entity(tableName = "history_table")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "url") val url: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "timestamp") val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "browser_data") // Ini adalah 'BrowserData' yang dicari MainActivity
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
