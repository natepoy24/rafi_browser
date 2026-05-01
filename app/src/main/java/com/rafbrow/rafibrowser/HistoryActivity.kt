package com.rafbrow.rafibrowser

import android.content.Context
import android.os.Bundle
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class HistoryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            finish()
        }

        val container = findViewById<LinearLayout>(R.id.historyContainer)
        
        findViewById<ImageButton>(R.id.btnClear).setOnClickListener {
            val sharedPref = getSharedPreferences("RafiBrowserPrefs", Context.MODE_PRIVATE)
            sharedPref.edit().putString("history", "").apply()
            container.removeAllViews()
        }

        loadHistory(container)
    }

    private fun loadHistory(container: LinearLayout) {
        val sharedPref = getSharedPreferences("RafiBrowserPrefs", Context.MODE_PRIVATE)
        val historyStr = sharedPref.getString("history", "") ?: ""
        val historyList = historyStr.split(";;;").filter { it.isNotBlank() }.reversed()

        for (item in historyList) {
            val parts = item.split(":::")
            if (parts.size >= 2) {
                val url = parts[0]
                val title = parts[1]

                val itemView = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, 32, 0, 32)
                    // Removed bottom border to follow "No-Line" rule
                }

                val titleView = TextView(this).apply {
                    text = title
                    setTextColor(getColor(R.color.on_surface))
                    textSize = 18f
                }

                val urlView = TextView(this).apply {
                    text = url
                    setTextColor(getColor(R.color.on_surface_variant))
                    textSize = 14f
                    setPadding(0, 8, 0, 0)
                }

                itemView.addView(titleView)
                itemView.addView(urlView)

                container.addView(itemView)
            }
        }
    }
}
