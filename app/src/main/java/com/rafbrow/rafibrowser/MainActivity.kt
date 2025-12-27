package com.rafbrow.rafibrowser

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.*
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.rafbrow.rafibrowser.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var db: AppDatabase
    private lateinit var etUrl: EditText
    private lateinit var webViewContainer: FrameLayout
    private lateinit var tabContainer: LinearLayout
    private lateinit var lockOverlay: LinearLayout
    private lateinit var swipeRefresh: SwipeRefreshLayout

    private val tabList = mutableListOf<WebView>()
    private var currentTabIndex = -1
    private var isIncognito = false

    private val PREFS_NAME = "RafiBrowserPrefs"
    private val KEY_PIN = "app_pin"

    inner class WebAppInterface {
        @JavascriptInterface
        fun processLogin(site: String, user: String, pass: String) {
            runOnUiThread {
                if (!isIncognito && user.isNotEmpty() && pass.isNotEmpty()) {
                    val cleanSite = site.replace("https://", "").replace("http://", "").split("/")[0]
                    showSavePasswordDialog(cleanSite, user, pass)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Enable edge-to-edge display. This should be called before setContentView.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        db = AppDatabase.getDatabase(this)

        // --- UI BINDING ---
        etUrl = findViewById(R.id.etUrl)
        webViewContainer = findViewById(R.id.webViewContainer)
        tabContainer = findViewById(R.id.tabContainer)
        lockOverlay = findViewById(R.id.lockOverlay)
        swipeRefresh = findViewById(R.id.swipeRefresh)

        // --- NOTCH & STATUS BAR FIX ---
        // This listener adds padding to the topBar to prevent content from overlapping with the status bar.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.topBar)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            v.setPadding(0, Math.max(bars.top, cutout.top), 0, 0)
            insets
        }

        // --- LISTENERS ---
        findViewById<ImageButton>(R.id.btnNewTabIcon).setOnClickListener { addNewTab("https://www.google.com") }
        findViewById<ImageButton>(R.id.btnCloseTabIcon).setOnClickListener { closeCurrentTab() }
        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener { showChromeMenu(it) }
        findViewById<ImageButton>(R.id.btnUndo).setOnClickListener { getCurrentWebView()?.goBack() }
        findViewById<ImageButton>(R.id.btnGo).setOnClickListener { loadWeb(etUrl.text.toString()) }

        etUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                loadWeb(etUrl.text.toString())
                hideKeyboard()
                true
            } else false
        }

        swipeRefresh.setOnRefreshListener { getCurrentWebView()?.reload() }

        if (savedInstanceState == null) addNewTab("https://www.google.com")
        setupSecurity()
    }

    // --- TAB LOGIC ---
    private fun addNewTab(url: String) {
        val wv = WebView(this)
        setupWebViewSettings(wv)
        registerForContextMenu(wv)
        tabList.add(wv)
        currentTabIndex = tabList.size - 1
        switchTab(currentTabIndex)
        wv.loadUrl(url)
    }

    private fun closeCurrentTab() {
        if (tabList.size <= 1) {
            Toast.makeText(this, "Minimal harus ada 1 tab", Toast.LENGTH_SHORT).show()
            return
        }
        val wvToRemove = tabList[currentTabIndex]
        wvToRemove.stopLoading()
        wvToRemove.loadUrl("about:blank") // Bersihkan memori
        wvToRemove.destroy()

        tabList.removeAt(currentTabIndex)
        currentTabIndex = if (currentTabIndex > 0) currentTabIndex - 1 else 0

        switchTab(currentTabIndex)
    }

    private fun switchTab(index: Int) {
        currentTabIndex = index
        webViewContainer.removeAllViews()
        webViewContainer.addView(tabList[index])
        updateTabSwitcherUI()
    }

    private fun updateTabSwitcherUI() {
        tabContainer.removeAllViews()
        for (i in tabList.indices) {
            val btn = Button(this).apply {
                val title = tabList[i].title ?: "Tab ${i + 1}"
                text = if (title.length > 8) title.substring(0, 6) + ".." else title
                textSize = 10f
                backgroundTintList = ColorStateList.valueOf(
                    if (i == currentTabIndex) Color.parseColor("#BB86FC") else Color.parseColor("#2C2C2C")
                )
                setTextColor(if (i == currentTabIndex) Color.BLACK else Color.WHITE)
                setOnClickListener { switchTab(i) }
            }
            tabContainer.addView(btn)
        }
    }

    private fun getCurrentWebView(): WebView? = if (currentTabIndex != -1) tabList[currentTabIndex] else null

    private fun loadWeb(query: String) {
        val url = if (query.contains(".") && !query.contains(" ")) {
            if (query.startsWith("http")) query else "https://$query"
        } else {
            "https://www.google.com/search?q=$query"
        }
        getCurrentWebView()?.loadUrl(url)
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun setupWebViewSettings(wv: WebView) {
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (view == getCurrentWebView()) etUrl.setText(url)
                swipeRefresh.isRefreshing = false

                val pageTitle = view?.title ?: "No Title"

                url?.let { currUrl ->
                    if (!isIncognito) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            db.browserDao().insertHistory(HistoryEntity(url = currUrl, title = pageTitle))
                        }
                    }
                }
            }
        }
        wv.viewTreeObserver.addOnScrollChangedListener { swipeRefresh.isEnabled = wv.scrollY == 0 }
    }

    // --- POPUP MENU & DIALOGS ---
    private fun showChromeMenu(anchor: View) {
        val layout = layoutInflater.inflate(R.layout.popup_browser_menu, null)
        val popup = PopupWindow(layout, (240 * resources.displayMetrics.density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup.animationStyle = R.style.ChromeMenuAnimation
        popup.elevation = 30f

        // Shortcuts
        layout.findViewById<ImageButton>(R.id.menuAddBookmark).setOnClickListener {
            val wv = getCurrentWebView()
            wv?.let { lifecycleScope.launch(Dispatchers.IO) { db.browserDao().insertBrowserData(BrowserData(
                url = it.url!!,
                title = it.title!!,
                content = "",
                type = "BOOKMARK",
            )) } }
            Toast.makeText(this, "Ditambahkan ke Bookmark", Toast.LENGTH_SHORT).show()
            popup.dismiss()
        }
        layout.findViewById<ImageButton>(R.id.menuForward).setOnClickListener { getCurrentWebView()?.goForward(); popup.dismiss() }
        layout.findViewById<ImageButton>(R.id.menuDuplicate).setOnClickListener { getCurrentWebView()?.url?.let { addNewTab(it) }; popup.dismiss() }

        // Settings List
        layout.findViewById<TextView>(R.id.optBookmarks).setOnClickListener { showBookmarkListDialog(); popup.dismiss() }
        layout.findViewById<TextView>(R.id.optHistory).setOnClickListener { showRiwayatDialog(); popup.dismiss() }
        val txtIncognito = layout.findViewById<TextView>(R.id.optIncognito)
        txtIncognito.text = if (isIncognito) "🌐 Mode Normal" else "🕶️ Mode Penyamaran"
        txtIncognito.setOnClickListener {
            isIncognito = !isIncognito
            animateThemeChange(isIncognito)
            popup.dismiss()
        }
        layout.findViewById<TextView>(R.id.optChangePin).setOnClickListener { showChangePinDialog(); popup.dismiss() }

        popup.showAsDropDown(anchor, 0, 0, Gravity.END)
    }

    private fun showSavePasswordDialog(site: String, user: String, pass: String) {
        AlertDialog.Builder(this)
            .setTitle("Simpan Sandi?")
            .setMessage("Apakah Anda ingin menyimpan sandi untuk situs '$site'?")
            .setPositiveButton("Simpan") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    // Create a BrowserData object for the password
                    val passwordData = BrowserData(
                        url = site,
                        title = user, content = pass,
                        type = "PASSWORD"
                    )
                    // Insert it into the database
                    db.browserDao().insertBrowserData(passwordData)
                }
                Toast.makeText(this, "Sandi disimpan", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Jangan Simpan", null)
            .show()
    }

    private fun showRiwayatDialog() {
        lifecycleScope.launch(Dispatchers.IO) {
            val history = db.browserDao().getAllHistory()
            withContext(Dispatchers.Main) {
                showCustomActionList("📜 Riwayat", history, true)
            }
        }
    }

    private fun showBookmarkListDialog() {
        lifecycleScope.launch(Dispatchers.IO) {
            val bookmarks = db.browserDao().getBookmarks()
            withContext(Dispatchers.Main) {
                showCustomActionList("🔖 Bookmarks", bookmarks, false)
            }
        }
    }

    private fun showCustomActionList(title: String, data: List<Any>, isHistory: Boolean) {
        val titles = data.map { if (it is HistoryEntity) it.title else (it as BrowserData).title }

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, titles)) { _, which ->
                val item = data[which]
                showItemOptions(item, isHistory)
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun showItemOptions(item: Any, isHistory: Boolean) {
        val url = if (item is HistoryEntity) item.url else (item as BrowserData).url
        val options = arrayOf("Buka Halaman", "Salin Link", "Hapus")

        AlertDialog.Builder(this)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> loadWeb(url)
                    1 -> {
                        val cb = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                        cb.setPrimaryClip(ClipData.newPlainText("URL", url))
                        Toast.makeText(this, "Link disalin", Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        lifecycleScope.launch(Dispatchers.IO) {
                            if (isHistory) db.browserDao().deleteHistoryItem((item as HistoryEntity).id)
                            else db.browserDao().deleteBrowserDataItem((item as BrowserData).id)

                            withContext(Dispatchers.Main) {
                                if (isHistory) showRiwayatDialog() else showBookmarkListDialog()
                            }
                        }
                    }
                }
            }.show()
    }

    // --- SECURITY, ANIMATIONS & UTILS ---
    private fun setupSecurity() {
        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                lockOverlay.visibility = View.GONE
            }
        })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Kunci Browser")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("Gunakan PIN")
            .build()

        biometricPrompt.authenticate(promptInfo)

        findViewById<Button>(R.id.btnUnlockPin).setOnClickListener {
            val input = findViewById<EditText>(R.id.etPinEntry).text.toString()
            val savedPin = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_PIN, "1234")
            if (input == savedPin) {
                lockOverlay.visibility = View.GONE
                hideKeyboard()
            } else Toast.makeText(this, "PIN Salah!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun animateThemeChange(incognito: Boolean) {
        // Determine the correct color from color resources
        val color = if (incognito) {
            ContextCompat.getColor(this, R.color. incognito_primary)
        } else {
            ContextCompat.getColor(this, R.color.normal_primary)
        }

        // Set the background of the top bar. This will show through the transparent status bar.
        val topBar = findViewById<LinearLayout>(R.id.topBar)
        topBar.setBackgroundColor(color)

        // Use WindowCompat to control the appearance of status bar icons (light or dark)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !incognito
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etUrl.windowToken, 0)
    }

    private fun showChangePinDialog() { /* Implementasi SharedPreferences di sini */ }

    override fun onCreateContextMenu(menu: ContextMenu, v: View, menuInfo: ContextMenu.ContextMenuInfo?) {
        super.onCreateContextMenu(menu, v, menuInfo)
        val result = (v as WebView).hitTestResult
        result.extra?.let {
            menu.setHeaderTitle("Opsi Link")
            menu.add(0, 1, 0, "Buka di Tab Baru")
            menu.add(0, 2, 0, "Salin Alamat Link")
        }
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        val url = getCurrentWebView()?.hitTestResult?.extra ?: ""
        when(item.itemId) {
            1 -> addNewTab(url)
            2 -> (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("URL", url))
        }
        return super.onContextItemSelected(item)
    }
}
