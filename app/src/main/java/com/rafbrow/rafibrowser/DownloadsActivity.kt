package com.rafbrow.rafibrowser

import android.app.DownloadManager
import android.content.Context
import android.os.Bundle
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class DownloadsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloads)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            finish()
        }

        val container = findViewById<LinearLayout>(R.id.downloadsContainer)
        loadDownloads(container)
    }

    private fun loadDownloads(container: LinearLayout) {
        val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query()
        val cursor = downloadManager.query(query)

        if (cursor != null && cursor.moveToFirst()) {
            val titleIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE)
            val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)

            do {
                if (titleIndex != -1 && statusIndex != -1) {
                    val title = cursor.getString(titleIndex)
                    val status = cursor.getInt(statusIndex)

                    var statusText = "Unknown"
                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> statusText = "Downloaded"
                        DownloadManager.STATUS_RUNNING -> statusText = "Downloading..."
                        DownloadManager.STATUS_FAILED -> statusText = "Failed"
                        DownloadManager.STATUS_PENDING -> statusText = "Pending"
                        DownloadManager.STATUS_PAUSED -> statusText = "Paused"
                    }

                    val itemView = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(0, 32, 0, 32)
                    }

                    val titleView = TextView(this).apply {
                        text = title
                        setTextColor(getColor(R.color.on_surface))
                        textSize = 18f
                    }

                    val statusView = TextView(this).apply {
                        text = statusText
                        setTextColor(getColor(R.color.on_surface_variant))
                        textSize = 14f
                        setPadding(0, 8, 0, 0)
                    }

                    itemView.addView(titleView)
                    itemView.addView(statusView)

                    container.addView(itemView)
                }
            } while (cursor.moveToNext())
        }
        cursor?.close()
        
        if (container.childCount == 0) {
            val emptyView = TextView(this).apply {
                text = "No downloads"
                setTextColor(getColor(R.color.on_surface_variant))
                textSize = 16f
                setPadding(0, 32, 0, 0)
            }
            container.addView(emptyView)
        }
    }
}
