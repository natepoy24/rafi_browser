package com.rafbrow.rafibrowser

import android.os.Bundle
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.rafbrow.rafibrowser.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DownloadsActivity : AppCompatActivity() {

    private lateinit var db: AppDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloads)
        db = AppDatabase.getDatabase(this)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            finish()
        }

        val container = findViewById<LinearLayout>(R.id.downloadsContainer)
        loadDownloads(container)
    }

    private fun loadDownloads(container: LinearLayout) {
        lifecycleScope.launch(Dispatchers.IO) {
            val downloadsList = db.browserDao().getAllDownloads()
            withContext(Dispatchers.Main) {
                container.removeAllViews()
                if (downloadsList.isEmpty()) {
                    val emptyView = TextView(this@DownloadsActivity).apply {
                        text = "No downloads yet"
                        setTextColor(getColor(R.color.on_surface_variant))
                        textSize = 16f
                        setPadding(0, 64, 0, 0)
                    }
                    container.addView(emptyView)
                    return@withContext
                }
                for (item in downloadsList) {
                    val itemView = LinearLayout(this@DownloadsActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(0, 32, 0, 32)
                    }

                    val titleView = TextView(this@DownloadsActivity).apply {
                        text = item.fileName
                        setTextColor(getColor(R.color.on_surface))
                        textSize = 18f
                        maxLines = 1
                    }

                    val urlView = TextView(this@DownloadsActivity).apply {
                        text = item.url
                        setTextColor(getColor(R.color.on_surface_variant))
                        textSize = 14f
                        maxLines = 1
                        setPadding(0, 8, 0, 0)
                    }

                    itemView.addView(titleView)
                    itemView.addView(urlView)
                    container.addView(itemView)
                }
            }
        }
    }
}
