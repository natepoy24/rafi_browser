package com.rafbrow.rafibrowser

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rafbrow.rafibrowser.data.AppDatabase
import com.rafbrow.rafibrowser.data.BlockedUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BlockedUrlActivity : AppCompatActivity() {

    private lateinit var db: AppDatabase
    private lateinit var etPattern: EditText
    private lateinit var btnAdd: Button
    private lateinit var rvBlocked: RecyclerView
    private lateinit var adapter: BlockedUrlAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_blocked_urls)
        db = AppDatabase.getDatabase(this)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        etPattern = findViewById(R.id.etBlockedPattern)
        btnAdd = findViewById(R.id.btnAddBlocked)
        rvBlocked = findViewById(R.id.rvBlockedUrls)

        adapter = BlockedUrlAdapter { blockedUrl ->
            deleteBlockedUrl(blockedUrl)
        }

        rvBlocked.layoutManager = LinearLayoutManager(this)
        rvBlocked.adapter = adapter

        btnAdd.setOnClickListener {
            val pattern = etPattern.text.toString().trim()
            if (pattern.isNotEmpty()) {
                addBlockedUrl(pattern)
            } else {
                Toast.makeText(this, "Masukkan pola URL", Toast.LENGTH_SHORT).show()
            }
        }

        loadBlockedUrls()
    }

    private fun loadBlockedUrls() {
        lifecycleScope.launch(Dispatchers.IO) {
            val list = db.browserDao().getAllBlockedUrls()
            withContext(Dispatchers.Main) {
                adapter.submitList(list)
            }
        }
    }

    private fun addBlockedUrl(pattern: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            db.browserDao().insertBlockedUrl(BlockedUrl(pattern = pattern))
            withContext(Dispatchers.Main) {
                etPattern.text.clear()
                loadBlockedUrls()
                Toast.makeText(this@BlockedUrlActivity, "Ditambahkan", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun deleteBlockedUrl(blockedUrl: BlockedUrl) {
        lifecycleScope.launch(Dispatchers.IO) {
            db.browserDao().deleteBlockedUrl(blockedUrl)
            withContext(Dispatchers.Main) {
                loadBlockedUrls()
                Toast.makeText(this@BlockedUrlActivity, "Dihapus", Toast.LENGTH_SHORT).show()
            }
        }
    }

    class BlockedUrlAdapter(private val onDelete: (BlockedUrl) -> Unit) :
        RecyclerView.Adapter<BlockedUrlAdapter.ViewHolder>() {

        private var list = listOf<BlockedUrl>()

        fun submitList(newList: List<BlockedUrl>) {
            list = newList
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_blocked_url, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = list[position]
            holder.tvPattern.text = item.pattern
            holder.btnDelete.setOnClickListener { onDelete(item) }
        }

        override fun getItemCount() = list.size

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvPattern: TextView = view.findViewById(R.id.tvPattern)
            val btnDelete: ImageButton = view.findViewById(R.id.btnDelete)
        }
    }
}