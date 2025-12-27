package com.rafbrow.rafibrowser

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.*
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        db = AppDatabase.getDatabase(this)

        // --- 1. HANDLING NOTCH / KAMERA DEPAN ---
        val topBar = findViewById<LinearLayout>(R.id.topBar)
        ViewCompat.setOnApplyWindowInsetsListener(topBar) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            v.setPadding(0, Math.max(bars.top, cutout.top), 0, 0)
            insets
        }

        // --- 2. INITIALIZE VIEW ---
        etUrl = findViewById(R.id.etUrl)
        webViewContainer = findViewById(R.id.webViewContainer)
        tabContainer = findViewById(R.id.tabContainer)
        lockOverlay = findViewById(R.id.lockOverlay)
        swipeRefresh = findViewById(R.id.swipeRefresh)

        // --- 3. LISTENERS ---
        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener { showChromeMenu(it) }
        findViewById<ImageButton>(R.id.btnUndo).setOnClickListener { getCurrentWebView()?.goBack() }
        findViewById<ImageButton>(R.id.btnGo).setOnClickListener { loadWeb(etUrl.text.toString()) }

        findViewById<ImageButton>(R.id.btnNewTabIcon).setOnClickListener { addNewTab("https://www.google.com") }
        findViewById<ImageButton>(R.id.btnCloseTabIcon).setOnClickListener { closeCurrentTab() }

        // Fix Keyboard Enter
        etUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                loadWeb(etUrl.text.toString())
                hideKeyboard()
                true
            } else false
        }

        // Swipe to Refresh Logic
        swipeRefresh.setColorSchemeColors(Color.parseColor("#BB86FC"))
        swipeRefresh.setOnRefreshListener { getCurrentWebView()?.reload() }

        if (savedInstanceState == null) addNewTab("https://www.google.com")

        setupSecurity()
    }

    // --- SISTEM TAB & WEBVIEW ---
    private fun addNewTab(url: String) {
        val wv = WebView(this)
        setupWebViewSettings(wv)
        registerForContextMenu(wv) // Hold options
        tabList.add(wv)
        currentTabIndex = tabList.size - 1
        switchTab(currentTabIndex)
        wv.loadUrl(url)
    }

    private fun closeCurrentTab() {
        if (tabList.size <= 1) {
            Toast.makeText(this, "Minimal 1 Tab terbuka", Toast.LENGTH_SHORT).show()
            return
        }
        val wvToRemove = tabList[currentTabIndex]
        webViewContainer.removeView(wvToRemove)
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

        // Update URL bar
        etUrl.setText(tabList[index].url)

        // Fix SwipeRefresh conflict with scroll
        tabList[index].viewTreeObserver.addOnScrollChangedListener {
            swipeRefresh.isEnabled = tabList[index].scrollY == 0
        }
    }

    private fun updateTabSwitcherUI() {
        tabContainer.removeAllViews()
        for (i in tabList.indices) {
            val tabBtn = Button(this).apply {
                val title = tabList[i].title ?: "Tab ${i + 1}"
                text = if (title.length > 8) title.substring(0, 6) + ".." else title
                textSize = 10spToPx().toFloat()
                isAllCaps = false
                backgroundTintList = ColorStateList.valueOf(
                    if (i == currentTabIndex) Color.parseColor("#BB86FC") else Color.parseColor("#2C2C2C")
                )
                setTextColor(if (i == currentTabIndex) Color.BLACK else Color.WHITE)
                setOnClickListener { switchTab(i) }
            }
            tabContainer.addView(tabBtn)
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

    private fun setupWebViewSettings(wv: WebView) {
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.useWideViewPort = true
        wv.settings.loadWithOverviewMode = true
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (view == getCurrentWebView()) etUrl.setText(url)
                swipeRefresh.isRefreshing = false

                url?.let { currUrl ->
                    val pageTitle = view?.title ?: "No Title"
                    if (!isIncognito) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            db.browserDao().insertHistory(HistoryEntity(url = currUrl, title = pageTitle))
                        }
                    }
                }
            }
        }
    }

    // --- POPUP MENU CHROME STYLE (POJOK KANAN) ---
    private fun showChromeMenu(anchor: View) {
        val layout = layoutInflater.inflate(R.layout.popup_browser_menu, null)
        val popup = PopupWindow(layout, dpToPx(250), ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup.animationStyle = R.style.ChromeMenuAnimation
        popup.elevation = 30f

        // SHORTCUTS (ICON ROW)
        layout.findViewById<ImageButton>(R.id.menuForward).setOnClickListener { getCurrentWebView()?.goForward(); popup.dismiss() }
        layout.findViewById<ImageButton>(R.id.menuAddBookmark).setOnClickListener {
            val wv = getCurrentWebView()
            wv?.let {
                lifecycleScope.launch(Dispatchers.IO) {
                    db.browserDao().insertBrowserData(BrowserData(url = it.url!!, title = it.title!!, type = "BOOKMARK"))
                }
                Toast.makeText(this, "Bintang ditambahkan!", Toast.LENGTH_SHORT).show()
            }
            popup.dismiss()
        }
        layout.findViewById<ImageButton>(R.id.menuDuplicate).setOnClickListener {
            getCurrentWebView()?.url?.let { addNewTab(it) }
            popup.dismiss()
        }

        // LIST SETTINGS
        layout.findViewById<TextView>(R.id.optBookmarks).setOnClickListener { showBookmarkListDialog(); popup.dismiss() }
        layout.findViewById<TextView>(R.id.optHistory).setOnClickListener { showRiwayatDialog(); popup.dismiss() }
        layout.findViewById<TextView>(R.id.optIncognito).apply {
            text = if (isIncognito) "🌐 Mode Normal" else "🕶️ Mode Penyamaran"
            setOnClickListener {
                isIncognito = !isIncognito
                animateThemeChange(isIncognito)
                popup.dismiss()
            }
        }
        layout.findViewById<TextView>(R.id.optChangePin).setOnClickListener { showChangePinDialog(); popup.dismiss() }

        popup.showAsDropDown(anchor, 0, 0, Gravity.END)
    }

    // --- LOGIKA HAPUS DENGAN AUTO-REFRESH ---
    private fun showRiwayatDialog() {
        lifecycleScope.launch(Dispatchers.IO) {
            val history = db.browserDao().getAllHistory()
            withContext(Dispatchers.Main) {
                showActionListDialog("📜 Riwayat", history, true)
            }
        }
    }

    private fun showBookmarkListDialog() {
        lifecycleScope.launch(Dispatchers.IO) {
            val bookmarks = db.browserDao().getBookmarks()
            withContext(Dispatchers.Main) {
                showActionListDialog("🔖 Bookmarks", bookmarks, false)
            }
        }
    }

    private fun showActionListDialog(title: String, data: List<Any>, isHistory: Boolean) {
        val titles = data.map { if (it is HistoryEntity) it.title else (it as BrowserData).title }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, titles)) { _, which ->
                showItemOptions(data[which], isHistory)
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun showItemOptions(item: Any, isHistory: Boolean) {
        val url = if (item is HistoryEntity) item.url else (item as BrowserData).url
        val options = arrayOf("Buka", "Salin Link", "Lihat URL Lengkap", "Hapus")

        AlertDialog.Builder(this)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> loadWeb(url)
                    1 -> (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("URL", url))
                    2 -> AlertDialog.Builder(this).setMessage(url).show()
                    3 -> lifecycleScope.launch(Dispatchers.IO) {
                        if (isHistory) db.browserDao().deleteHistoryItem((item as HistoryEntity).id)
                        else db.browserDao().deleteBrowserDataItem((item as BrowserData).id)
                        // AUTO REFRESH DIALOG
                        withContext(Dispatchers.Main) {
                            if (isHistory) showRiwayatDialog() else showBookmarkListDialog()
                        }
                    }
                }
            }.show()
    }

    // --- KEAMANAN & ANIMASI ---
    private fun setupSecurity() {
        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                lockOverlay.visibility = View.GONE
            }
        })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Kunci Browser")
            .setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG)
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
        val colorFrom = if (incognito) Color.parseColor("#1E1E1E") else Color.parseColor("#4A148C")
        val colorTo = if (incognito) Color.parseColor("#4A148C") else Color.parseColor("#1E1E1E")

        ValueAnimator.ofObject(ArgbEvaluator(), colorFrom, colorTo).apply {
            duration = 500
            addUpdateListener { animator ->
                val c = animator.animatedValue as Int
                findViewById<LinearLayout>(R.id.topBar).setBackgroundColor(c)
                window.statusBarColor = c // Fix Warning via logic
            }
            start()
        }
        // Smooth transition effect for webView
        webViewContainer.animate().alpha(0.3f).setDuration(200).withEndAction {
            webViewContainer.animate().alpha(1.0f).setDuration(200).start()
        }.start()
    }

    // --- UTILS ---
    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
    private fun spToPx(): Int = (10 * resources.displayMetrics.scaledDensity).toInt()
    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etUrl.windowToken, 0)
    }

    // --- CONTEXT MENU (HOLD OPTIONS) ---
    override fun onCreateContextMenu(menu: ContextMenu?, v: View?, menuInfo: ContextMenu.ContextMenuInfo?) {
        super.onCreateContextMenu(menu, v, menuInfo)
        val result = (v as WebView).hitTestResult
        val url = result.extra ?: return
        if (result.type == WebView.HitTestResult.SRC_ANCHOR_TYPE || result.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
            menu?.setHeaderTitle("Opsi Link")
            menu?.add(0, 1, 0, "Buka di Tab Baru")
            menu?.add(0, 2, 0, "Salin Alamat Link")
            menu?.add(0, 3, 0, "Cari di Google")
        }
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        val url = (getCurrentWebView()?.hitTestResult)?.extra ?: ""
        when (item.itemId) {
            1 -> addNewTab(url)
            2 -> (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("URL", url))
            3 -> loadWeb("https://www.google.com/search?q=$url")
        }
        return super.onContextItemSelected(item)
    }
}