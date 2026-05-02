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
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.rafbrow.rafibrowser.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import androidx.biometric.BiometricManager

class MainActivity : AppCompatActivity() {

    private lateinit var db: AppDatabase
    private lateinit var etUrl: EditText
    private lateinit var webViewContainer: FrameLayout
    private lateinit var tabContainer: LinearLayout
    private lateinit var lockOverlay: LinearLayout
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var btnNativePlayer: ImageButton

    private val tabList = mutableListOf<WebView>()
    private var currentTabIndex = -1
    private var isIncognito = false
    private var lastClickedLinkText = ""
    private var detectedVideoUrl = ""
    private var detectedVideoTitle = ""

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var fullscreenContainer: FrameLayout? = null

    private val PREFS_NAME = "RafiBrowserPrefs"
    private val KEY_PIN = "app_pin"

    private val adBlockList = mutableListOf<String>()

    // --- INTERFACE JAVASCRIPT ---
    inner class WebAppInterface {
        @JavascriptInterface
        fun processLogin(site: String, user: String, pass: String) {
            runOnUiThread {
                if (!isIncognito && user.isNotEmpty() && pass.isNotEmpty()) {
                    showSavePasswordDialog(site.replace("https://", "").split("/")[0], user, pass)
                }
            }
        }

        @JavascriptInterface
        fun storeLinkText(text: String) {
            lastClickedLinkText = text
        }

        @JavascriptInterface
        fun onVideoDetected(url: String, title: String) {
            runOnUiThread {
                detectedVideoUrl = url
                detectedVideoTitle = title
                btnNativePlayer.visibility = View.VISIBLE
            }
        }

        // Sinkronisasi UI saat Fake Fullscreen aktif/mati
        @JavascriptInterface
        fun setFakeFullscreenUI(enabled: Boolean) {
            runOnUiThread {
                val topBar = findViewById<LinearLayout>(R.id.topBarContainer)
                val bottomBar = findViewById<LinearLayout>(R.id.bottomBar)

                if (enabled) {
                    topBar?.visibility = View.GONE
                    tabContainer.visibility = View.GONE
                    bottomBar?.visibility = View.GONE
                    toggleImmersiveMode(true)
                } else {
                    topBar?.visibility = View.VISIBLE
                    tabContainer.visibility = View.VISIBLE
                    bottomBar?.visibility = View.VISIBLE
                    toggleImmersiveMode(false)
                }
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
        btnNativePlayer = findViewById(R.id.btnNativePlayer)

        btnNativePlayer.setOnClickListener {
            val intent = Intent(this@MainActivity, PlayerActivity::class.java).apply {
                putExtra("videoUrl", detectedVideoUrl)
                putExtra("videoTitle", detectedVideoTitle)
                putExtra("userAgent", getCurrentWebView()?.settings?.userAgentString)
            }
            startActivity(intent)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wv = getCurrentWebView()
                wv?.evaluateJavascript("(function(){ return document.body.classList.contains('fake-fullscreen-mode'); })();") { isFake ->
                    if (isFake == "true") {
                        wv.evaluateJavascript("window.forceFullscreenVideo();", null)
                    } else if (wv?.canGoBack() == true) {
                        wv.goBack()
                    } else if (tabList.size > 1) {
                        closeCurrentTab()
                    } else {
                        finish()
                    }
                }
            }
        })

        findViewById<ImageButton>(R.id.btnNewTabIcon).setOnClickListener { addNewTab("https://www.google.com") }
        findViewById<ImageButton>(R.id.btnCloseTabIcon).setOnClickListener { closeCurrentTab() }
        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener { showMenuBottomSheet() }
        findViewById<ImageButton>(R.id.btnUndo).setOnClickListener { getCurrentWebView()?.goBack() }
        findViewById<ImageButton>(R.id.btnGo).setOnClickListener { loadWeb(etUrl.text.toString()) }

        etUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                loadWeb(etUrl.text.toString()); hideKeyboard(); true
            } else false
        }

        swipeRefresh.setOnRefreshListener { getCurrentWebView()?.reload() }
        if (savedInstanceState == null) addNewTab("https://www.google.com")
        setupSecurity()
        loadBlockedUrls()
    }

    override fun onResume() {
        super.onResume()
        loadBlockedUrls()
    }

    private fun loadBlockedUrls() {
        lifecycleScope.launch(Dispatchers.IO) {
            val list = db.browserDao().getAllBlockedUrls()
            withContext(Dispatchers.Main) {
                adBlockList.clear()
                adBlockList.addAll(list.map { it.pattern })
            }
        }
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
        swipeRefresh.isEnabled = selectedWv.scrollY == 0
    }

    private fun updateTabSwitcherUI() {
        tabContainer.removeAllViews()
        for (i in tabList.indices) {
            val isActive = i == currentTabIndex
            val btn = Button(this).apply {
                val title = tabList[i].title ?: "Tab ${i + 1}"
                text = if (title.length > 12) title.substring(0, 10) + "…" else title
                textSize = 12f
                isAllCaps = false
                setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8))
                backgroundTintList = ColorStateList.valueOf(
                    if (isActive) Color.parseColor("#2c2c2c") else Color.parseColor("#131313")
                )
                setTextColor(
                    if (isActive) Color.parseColor("#c6c6c7") else Color.parseColor("#ababab")
                )
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.marginEnd = dpToPx(8)
                layoutParams = lp
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

        // Pengecekan Blokir sebelum navigasi utama
        for (pattern in adBlockList) {
            if (url.contains(pattern, ignoreCase = true)) {
                Toast.makeText(this, "URL ini diblokir!", Toast.LENGTH_SHORT).show()
                return
            }
        }
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
        }
        wv.addJavascriptInterface(WebAppInterface(), "AndroidInterface")

        wv.viewTreeObserver.addOnScrollChangedListener {
            if (wv == getCurrentWebView()) swipeRefresh.isEnabled = wv.scrollY == 0
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress == 100) View.GONE else View.VISIBLE
            }
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) { callback?.onCustomViewHidden(); return }
                customView = view
                customViewCallback = callback
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
                toggleImmersiveMode(true)

                fullscreenContainer = FrameLayout(this@MainActivity).apply { setBackgroundColor(Color.BLACK) }
                fullscreenContainer?.addView(customView)
                (window.decorView as FrameLayout).addView(fullscreenContainer, FrameLayout.LayoutParams(-1, -1))
                findViewById<RelativeLayout>(R.id.mainRootLayout).visibility = View.GONE
            }
            override fun onHideCustomView() {
                exitFullscreenMode()
            }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                btnNativePlayer.visibility = View.GONE
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url.toString()
                for (pattern in adBlockList) {
                    if (url.contains(pattern, ignoreCase = true)) {
                        Toast.makeText(this@MainActivity, "Diblokir oleh sistem", Toast.LENGTH_SHORT).show()
                        return true
                    }
                }
                return false
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url.toString()
                
                // Pengecekan Blokir
                for (pattern in adBlockList) {
                    if (url.contains(pattern, ignoreCase = true)) {
                        return WebResourceResponse("text/plain", "UTF-8", null)
                    }
                }

                if (url.contains(".m3u8") || url.contains(".mpd") || url.contains(".mp4")) {
                    runOnUiThread {
                        detectedVideoUrl = url
                        detectedVideoTitle = view?.title ?: "Video Terdeteksi"
                        btnNativePlayer.visibility = View.VISIBLE
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (view == getCurrentWebView()) etUrl.setText(url)
                swipeRefresh.isRefreshing = false
                injectVideoSniffer(view as WebView)
                if (!isIncognito && url != null) {
                    val pageTitle = view?.title ?: "No Title"
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

    private fun toggleImmersiveMode(enable: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (enable) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun exitFullscreenMode() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        toggleImmersiveMode(false)
        (window.decorView as FrameLayout).removeView(fullscreenContainer)
        fullscreenContainer = null
        customViewCallback?.onCustomViewHidden()
        customView = null
        findViewById<RelativeLayout>(R.id.mainRootLayout).visibility = View.VISIBLE

        // Kembalikan UI jika sebelumnya masuk lewat fake fullscreen
        findViewById<LinearLayout>(R.id.topBarContainer)?.visibility = View.VISIBLE
        tabContainer.visibility = View.VISIBLE
        findViewById<LinearLayout>(R.id.bottomBar)?.visibility = View.VISIBLE
    }

    private fun injectVideoSniffer(wv: WebView) {
        val jsCode = """
            (function() {
                function sniffVideo() {
                    var vids = document.getElementsByTagName('video');
                    for(var i=0; i<vids.length; i++) {
                        var v = vids[i];
                        if (!v.myUrlDetected) {
                            var src = v.src;
                            if (!src) {
                                var source = v.querySelector('source');
                                if (source) src = source.src;
                            }
                            if (src && !src.startsWith('blob:')) {
                                v.myUrlDetected = true;
                                AndroidInterface.onVideoDetected(src, document.title);
                            }
                        }
                    }
                }
                setInterval(sniffVideo, 2000);
            })();
        """.trimIndent()
        wv.evaluateJavascript(jsCode, null)
    }

    private fun showMenuBottomSheet() {
        val sheet = MenuBottomSheet()
        sheet.listener = object : MenuBottomSheet.MenuListener {
            override fun onForwardClicked() {
                if (getCurrentWebView()?.canGoForward() == true) getCurrentWebView()?.goForward()
            }
            override fun onFullscreenClicked() {
                getCurrentWebView()?.evaluateJavascript("window.forceFullscreenVideo();", null)
            }
            override fun onAddBookmarkClicked() {
                val wv = getCurrentWebView()
                if (wv?.url != null) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        db.browserDao().insertBrowserData(
                            BrowserData(url = wv.url!!, title = wv.title ?: "", content = "", type = "BOOKMARK")
                        )
                    }
                    Toast.makeText(this@MainActivity, "Tersimpan!", Toast.LENGTH_SHORT).show()
                }
            }
        }
        sheet.show(supportFragmentManager, "MenuBottomSheet")
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
        val promptInfo = BiometricPrompt.PromptInfo.Builder().setTitle("Kunci").setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG).setNegativeButtonText("PIN").build()
        biometricPrompt.authenticate(promptInfo)
        findViewById<Button>(R.id.btnUnlockPin).setOnClickListener {
            val input = findViewById<EditText>(R.id.etPinEntry).text.toString()
            if (input == getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_PIN, "1234")) { lockOverlay.visibility = View.GONE; hideKeyboard() } else Toast.makeText(this, "Salah!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun animateThemeChange(incognito: Boolean) {
        val colorFrom = if (incognito) ContextCompat.getColor(this, R.color.surface_container) else ContextCompat.getColor(this, R.color.incognito_primary)
        val colorTo = if (incognito) ContextCompat.getColor(this, R.color.incognito_primary) else ContextCompat.getColor(this, R.color.surface_container)
        val anim = ValueAnimator.ofObject(ArgbEvaluator(), colorFrom, colorTo)
        anim.duration = 500
        anim.addUpdateListener { 
            findViewById<LinearLayout>(R.id.topBar).setBackgroundColor(it.animatedValue as Int)
            findViewById<LinearLayout>(R.id.bottomBar).setBackgroundColor(it.animatedValue as Int)
        }
        anim.start()
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
    private fun hideKeyboard() { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(etUrl.windowToken, 0) }

    override fun onCreateContextMenu(menu: ContextMenu, v: View, menuInfo: ContextMenu.ContextMenuInfo?) {
        super.onCreateContextMenu(menu, v, menuInfo)
        val res = (v as WebView).hitTestResult
        if (res.type == WebView.HitTestResult.SRC_ANCHOR_TYPE || res.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
            menu.setHeaderTitle("Opsi Link")
            menu.add(0, 1, 0, "Buka di Tab Baru")
            menu.add(0, 2, 0, "Salin Alamat Link")
            menu.add(0, 3, 0, "Salin Teks Link")
        }
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        val url = getCurrentWebView()?.hitTestResult?.extra ?: ""
        when (item.itemId) {
            1 -> if (url.isNotEmpty()) addNewTab(url)
            2 -> if (url.isNotEmpty()) (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("URL", url))
            3 -> {
                if (lastClickedLinkText.isNotEmpty()) {
                    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Text", lastClickedLinkText))
                    Toast.makeText(this, "Teks disalin", Toast.LENGTH_SHORT).show()
                }
            }
        }
        return true
    }
}