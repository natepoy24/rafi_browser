package com.rafbrow.rafibrowser

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.*
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
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
    private lateinit var progressBar: ProgressBar

    private val tabList = mutableListOf<WebView>()
    private var currentTabIndex = -1
    private var isIncognito = false
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var fullscreenContainer: FrameLayout? = null

    private val PREFS_NAME = "RafiBrowserPrefs"
    private val KEY_PIN = "app_pin"

    private val adBlockList = listOf("tsyndicate.com", "diffusedpassionquaking.com", "doubleclick.net", "popads.net", "onclickads.net")

    inner class WebAppInterface {
        @JavascriptInterface
        fun processLogin(site: String, user: String, pass: String) {
            runOnUiThread {
                if (!isIncognito && user.isNotEmpty() && pass.isNotEmpty()) {
                    showSavePasswordDialog(site.replace("https://", "").split("/")[0], user, pass)
                }
            }
        }
    }

    private val pickSubtitleFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            val content = contentResolver.openInputStream(it)?.bufferedReader()?.use { it.readText() }
            if (content != null) {
                val sanitized = content.replace("`", "\\`").replace("$", "\\$").replace("\n", "\\n").replace("\r", "")
                getCurrentWebView()?.evaluateJavascript("window.loadExternalSubtitle(`${sanitized}`);", null)
                Toast.makeText(this, "Subtitle Dimuat!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        db = AppDatabase.getDatabase(this)

        etUrl = findViewById(R.id.etUrl)
        webViewContainer = findViewById(R.id.webViewContainer)
        tabContainer = findViewById(R.id.tabContainer)
        lockOverlay = findViewById(R.id.lockOverlay)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        progressBar = findViewById(R.id.progressBar)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wv = getCurrentWebView()
                if (wv?.canGoBack() == true) wv.goBack() else if (tabList.size > 1) closeCurrentTab() else finish()
            }
        })

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.topBar)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            v.setPadding(0, Math.max(bars.top, cutout.top), 0, 0)
            insets
        }

        findViewById<ImageButton>(R.id.btnNewTabIcon).setOnClickListener { addNewTab("https://www.google.com") }
        findViewById<ImageButton>(R.id.btnCloseTabIcon).setOnClickListener { closeCurrentTab() }
        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener { showChromeMenu(it) }
        findViewById<ImageButton>(R.id.btnUndo).setOnClickListener { getCurrentWebView()?.goBack() }
        findViewById<ImageButton>(R.id.btnGo).setOnClickListener { loadWeb(etUrl.text.toString()) }

        etUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { loadWeb(etUrl.text.toString()); hideKeyboard(); true } else false
        }

        swipeRefresh.setOnRefreshListener { getCurrentWebView()?.reload() }
        if (savedInstanceState == null) addNewTab("https://www.google.com")
        setupSecurity()
    }

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
        if (tabList.size <= 1) return
        val wv = tabList[currentTabIndex]
        wv.stopLoading()
        webViewContainer.removeView(wv)
        wv.destroy()
        tabList.removeAt(currentTabIndex)
        currentTabIndex = if (currentTabIndex > 0) currentTabIndex - 1 else 0
        switchTab(currentTabIndex)
    }

    private fun switchTab(index: Int) {
        if (index !in tabList.indices) return
        currentTabIndex = index
        val selectedWv = tabList[index]
        (selectedWv.parent as? ViewGroup)?.removeView(selectedWv)
        webViewContainer.removeAllViews()
        webViewContainer.addView(selectedWv, FrameLayout.LayoutParams(-1, -1))
        updateTabSwitcherUI()
        etUrl.setText(selectedWv.url)
        selectedWv.viewTreeObserver.addOnScrollChangedListener { swipeRefresh.isEnabled = selectedWv.scrollY == 0 }
    }

    private fun updateTabSwitcherUI() {
        tabContainer.removeAllViews()
        for (i in tabList.indices) {
            val btn = Button(this).apply {
                val title = tabList[i].title ?: "Tab ${i + 1}"
                text = if (title.length > 8) title.substring(0, 6) + ".." else title
                textSize = 10f
                isAllCaps = false
                backgroundTintList = ColorStateList.valueOf(if (i == currentTabIndex) Color.parseColor("#BB86FC") else Color.parseColor("#2C2C2C"))
                setTextColor(if (i == currentTabIndex) Color.BLACK else Color.WHITE)
                setOnClickListener { switchTab(i) }
            }
            tabContainer.addView(btn)
        }
    }

    private fun getCurrentWebView(): WebView? = if (currentTabIndex != -1 && currentTabIndex < tabList.size) tabList[currentTabIndex] else null

    private fun loadWeb(query: String) {
        val url = if (query.contains(".") && !query.contains(" ")) {
            if (query.startsWith("http")) query else "https://$query"
        } else "https://www.google.com/search?q=$query"
        getCurrentWebView()?.loadUrl(url)
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun setupWebViewSettings(wv: WebView) {
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
        }
        wv.addJavascriptInterface(WebAppInterface(), "Android")

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress == 100) View.GONE else View.VISIBLE
            }
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) { callback?.onCustomViewHidden(); return }
                customView = view
                customViewCallback = callback
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                fullscreenContainer = FrameLayout(this@MainActivity).apply { setBackgroundColor(Color.BLACK) }
                fullscreenContainer?.addView(customView)
                (window.decorView as FrameLayout).addView(fullscreenContainer, FrameLayout.LayoutParams(-1, -1))
                findViewById<RelativeLayout>(R.id.mainRootLayout).visibility = View.GONE
            }
            override fun onHideCustomView() {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                (window.decorView as FrameLayout).removeView(fullscreenContainer)
                fullscreenContainer = null
                customViewCallback?.onCustomViewHidden()
                customView = null
                findViewById<RelativeLayout>(R.id.mainRootLayout).visibility = View.VISIBLE
            }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url.toString()
                for (ad in adBlockList) { if (url.contains(ad)) return true }
                return false
            }
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                webViewContainer.animate().alpha(0.5f).setDuration(200).start()
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                if (view == getCurrentWebView()) etUrl.setText(url)
                swipeRefresh.isRefreshing = false
                webViewContainer.animate().alpha(1.0f).setDuration(300).start()
                val pageTitle = view?.title ?: "No Title"
                injectCustomPlayerLogic(view as WebView)
                if (!isIncognito && url != null) {
                    lifecycleScope.launch(Dispatchers.IO) { db.browserDao().insertHistory(HistoryEntity(url = url, title = pageTitle)) }
                }
            }
        }

        wv.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimetype)
                addRequestHeader("User-Agent", userAgent)
                setTitle(fileName)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            Toast.makeText(this, "Mengunduh...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun injectCustomPlayerLogic(wv: WebView) {
        val s1 = "$1"
        val s2 = "$2"
        val js = """
            (function() {
                var vs = document.getElementsByTagName('video');
                if (vs.length === 0) return;
                for (var i = 0; i < vs.length; i++) {
                    var v = vs[i];
                    v.style.filter = 'contrast(1.05)';
                    v.setAttribute('webkit-playsinline', 'true');
                    v.setAttribute('playsinline', 'true');
                    var lt = 0;
                    var pt;
                    v.addEventListener('touchstart', function(e) {
                        var n = Date.now();
                        if ((n - lt < 300) && (n - lt > 0)) {
                            if (e.cancelable) e.preventDefault();
                            var r = v.getBoundingClientRect();
                            var touchX = e.touches[0].clientX - r.left;
                            if (touchX > r.width / 2) { v.currentTime += 10; } 
                            else { v.currentTime -= 10; }
                        }
                        lt = n;
                        pt = setTimeout(function() { v.playbackRate = 2.0; }, 500);
                    }, { passive: false });
                    v.addEventListener('touchend', function() { clearTimeout(pt); v.playbackRate = 1.0; });
                    v.addEventListener('touchcancel', function() { clearTimeout(pt); v.playbackRate = 1.0; });
                }
                window.loadExternalSubtitle = function(srt) {
                    var vtt = "WEBVTT\n\n" + srt.replace(/(\d+:\d+:\d+),(\d+)/g, "$s1.$s2");
                    var b = new Blob([vtt], { type: 'text/vtt' });
                    var u = URL.createObjectURL(b);
                    for (var j = 0; j < vs.length; j++) {
                        var v = vs[j];
                        var old = v.querySelectorAll('track');
                        for (var k = 0; k < old.length; k++) { old[k].remove(); }
                        var t = document.createElement('track');
                        t.kind = 'subtitles';
                        t.label = 'Rafi Sub';
                        t.srclang = 'id';
                        t.src = u;
                        t.default = true;
                        v.appendChild(t);
                        if (v.textTracks && v.textTracks.length > 0) { v.textTracks[0].mode = 'showing'; }
                    }
                };
                document.querySelectorAll('div[class*="overlay"], div[class*="popup"]').forEach(function(el) {
                    if (el.offsetWidth > window.innerWidth * 0.4) el.remove();
                });
            })();
        """.trimIndent()
        wv.evaluateJavascript(js, null)
    }

    private fun showChromeMenu(anchor: View) {
        val layout = layoutInflater.inflate(R.layout.popup_browser_menu, null)
        val popup = PopupWindow(layout, dpToPx(240), ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup.animationStyle = R.style.ChromeMenuAnimation
        popup.elevation = 30f

        layout.findViewById<ImageButton>(R.id.menuForward).setOnClickListener { getCurrentWebView()?.goForward(); popup.dismiss() }
        layout.findViewById<ImageButton>(R.id.menuAddBookmark).setOnClickListener {
            val wv = getCurrentWebView()
            wv?.let { lifecycleScope.launch(Dispatchers.IO) { db.browserDao().insertBrowserData(BrowserData(url = it.url!!, title = it.title!!, content = "", type = "BOOKMARK")) } }
            popup.dismiss()
        }
        layout.findViewById<ImageButton>(R.id.menuDuplicate).setOnClickListener { getCurrentWebView()?.url?.let { addNewTab(it) }; popup.dismiss() }
        layout.findViewById<ImageButton>(R.id.menuDownload).setOnClickListener { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)); popup.dismiss() }
        layout.findViewById<ImageButton>(R.id.menuCC).setOnClickListener { pickSubtitleFile.launch("*/*"); popup.dismiss() }

        layout.findViewById<TextView>(R.id.optBookmarks).setOnClickListener { showBookmarkListDialog(); popup.dismiss() }
        layout.findViewById<TextView>(R.id.optHistory).setOnClickListener { showRiwayatDialog(); popup.dismiss() }
        val txtIncognito = layout.findViewById<TextView>(R.id.optIncognito)
        txtIncognito.text = if (isIncognito) "🌐 Mode Normal" else "🕶️ Mode Penyamaran"
        txtIncognito.setOnClickListener { isIncognito = !isIncognito; animateThemeChange(isIncognito); popup.dismiss() }
        layout.findViewById<TextView>(R.id.optChangePin).setOnClickListener { showChangePinDialog(); popup.dismiss() }
        popup.showAsDropDown(anchor, 0, 0, Gravity.END)
    }

    private fun showRiwayatDialog() {
        lifecycleScope.launch(Dispatchers.IO) {
            val history = db.browserDao().getAllHistory()
            withContext(Dispatchers.Main) { showCustomActionList("📜 Riwayat", history, true) }
        }
    }

    private fun showBookmarkListDialog() {
        lifecycleScope.launch(Dispatchers.IO) {
            val bookmarks = db.browserDao().getBookmarks()
            withContext(Dispatchers.Main) { showCustomActionList("🔖 Bookmarks", bookmarks, false) }
        }
    }

    private fun showCustomActionList(title: String, data: List<Any>, isHistory: Boolean) {
        val titles = data.map { if (it is HistoryEntity) it.title else (it as BrowserData).title }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(title)
            .setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, titles)) { _, which -> showItemOptions(data[which], isHistory) }
            .setNegativeButton("Tutup", null).show()
    }

    private fun showItemOptions(item: Any, isHistory: Boolean) {
        val url = if (item is HistoryEntity) item.url else (item as BrowserData).url
        val id = if (item is HistoryEntity) item.id else (item as BrowserData).id
        AlertDialog.Builder(this).setItems(arrayOf("Buka", "Salin", "Hapus")) { _, w ->
            when (w) {
                0 -> loadWeb(url)
                1 -> (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("URL", url))
                2 -> lifecycleScope.launch(Dispatchers.IO) {
                    if (isHistory) db.browserDao().deleteHistoryItem(id) else db.browserDao().deleteBrowserDataItem(id)
                    withContext(Dispatchers.Main) { if (isHistory) showRiwayatDialog() else showBookmarkListDialog() }
                }
            }
        }.show()
    }

    private fun showSavePasswordDialog(site: String, user: String, pass: String) {
        AlertDialog.Builder(this).setTitle("Simpan Sandi?").setMessage("Simpan untuk $site?")
            .setPositiveButton("Simpan") { _, _ -> lifecycleScope.launch(Dispatchers.IO) { db.browserDao().insertBrowserData(BrowserData(url = site, title = user, content = pass, type = "PASSWORD")) } }
            .setNegativeButton("Tidak", null).show()
    }

    private fun setupSecurity() {
        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { lockOverlay.visibility = View.GONE }
        })
        val promptInfo = BiometricPrompt.PromptInfo.Builder().setTitle("Kunci").setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG).setNegativeButtonText("Gunakan PIN").build()
        biometricPrompt.authenticate(promptInfo)
        findViewById<Button>(R.id.btnUnlockPin).setOnClickListener {
            val input = findViewById<EditText>(R.id.etPinEntry).text.toString()
            val saved = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_PIN, "1234")
            if (input == saved) { lockOverlay.visibility = View.GONE; hideKeyboard() } else Toast.makeText(this, "Salah!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showChangePinDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_change_pin, null)
        val etOld = view.findViewById<EditText>(R.id.etOldPin)
        val etNew = view.findViewById<EditText>(R.id.etNewPin)
        AlertDialog.Builder(this).setTitle("Ganti PIN").setView(view).setPositiveButton("Simpan") { _, _ ->
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            if (etOld.text.toString() == prefs.getString(KEY_PIN, "1234")) {
                prefs.edit().putString(KEY_PIN, etNew.text.toString()).apply()
                Toast.makeText(this, "Berhasil!", Toast.LENGTH_SHORT).show()
            }
        }.setNegativeButton("Batal", null).show()
    }

    private fun animateThemeChange(incognito: Boolean) {
        val color = if (incognito) Color.parseColor("#2C2C2C") else Color.parseColor("#1E1E1E")
        findViewById<LinearLayout>(R.id.topBar).setBackgroundColor(color)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !incognito
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
    private fun hideKeyboard() { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(etUrl.windowToken, 0) }
}