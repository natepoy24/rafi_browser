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

class HistoryActivity : AppCompatActivity() {

    private lateinit var db: AppDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        db = AppDatabase.getDatabase(this)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            finish()
        }

        val container = findViewById<LinearLayout>(R.id.historyContainer)

        findViewById<ImageButton>(R.id.btnClear).setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                db.browserDao().clearHistory()
                withContext(Dispatchers.Main) {
                    container.removeAllViews()
                    addEmptyState(container)
                }
            }
        }

        loadHistory(container)
    }

    private fun loadHistory(container: LinearLayout) {
        lifecycleScope.launch(Dispatchers.IO) {
            val historyList = db.browserDao().getAllHistory()
            withContext(Dispatchers.Main) {
                container.removeAllViews()
                if (historyList.isEmpty()) {
                    addEmptyState(container)
                    return@withContext
                }
                for (item in historyList) {
                    val itemView = LinearLayout(this@HistoryActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(0, 32, 0, 32)
                        isClickable = true
                        isFocusable = true
                        setBackgroundResource(android.R.attr.selectableItemBackground.let {
                            val a = obtainStyledAttributes(intArrayOf(it))
                            val resId = a.getResourceId(0, 0)
                            a.recycle()
                            resId
                        })
                        setOnClickListener {
                            val intent = android.content.Intent()
                            intent.putExtra("url", item.url)
                            setResult(RESULT_OK, intent)
                            finish()
                        }
                    }

                    val titleView = TextView(this@HistoryActivity).apply {
                        text = item.title
                        setTextColor(getColor(R.color.on_surface))
                        textSize = 18f
                        maxLines = 1
                    }

                    val urlView = TextView(this@HistoryActivity).apply {
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

    private fun addEmptyState(container: LinearLayout) {
        val emptyView = TextView(this).apply {
            text = "No history yet"
            setTextColor(getColor(R.color.on_surface_variant))
            textSize = 16f
            setPadding(0, 64, 0, 0)
        }
        container.addView(emptyView)
    }
}
