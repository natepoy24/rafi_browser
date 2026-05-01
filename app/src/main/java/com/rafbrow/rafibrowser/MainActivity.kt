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
    private lateinit var btnNativeSwitch: Button

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

    private val adBlockList = listOf("tsyndicate.com", "diffusedpassionquaking.com", "doubleclick.net", "popads.net", "onclickads.net")

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
                btnNativeSwitch.visibility = View.VISIBLE
            }
        }

        // Sinkronisasi UI saat Fake Fullscreen aktif/mati
        @JavascriptInterface
        fun setFakeFullscreenUI(enabled: Boolean) {
            runOnUiThread {
                val topBar = findViewById<LinearLayout>(R.id.topBar)
                val bottomBar = findViewById<LinearLayout>(R.id.bottomBar)

                if (enabled) {
                    topBar.visibility = View.GONE
                    tabContainer.visibility = View.GONE
                    bottomBar?.visibility = View.GONE
                    toggleImmersiveMode(true)
                } else {
                    topBar.visibility = View.VISIBLE
                    tabContainer.visibility = View.VISIBLE
                    bottomBar?.visibility = View.VISIBLE
                    toggleImmersiveMode(false)
                }
            }
        }
    }

    private val pickSubtitleFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            val content = contentResolver.openInputStream(it)?.bufferedReader()?.use { it.readText() }
            if (content != null) {
                val sanitized = content.replace("`", "\\`").replace("$", "\\$").replace("\n", "\\n").replace("\r", "")
                getCurrentWebView()?.evaluateJavascript("window.loadSubtitleContent(`${sanitized}`);", null)
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
        btnNativeSwitch = findViewById(R.id.btnNativeSwitch)

        btnNativeSwitch.setOnClickListener {
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

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.topBar)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, 0)
            insets
        }

        findViewById<ImageButton>(R.id.btnNewTabIcon).setOnClickListener { addNewTab("https://www.google.com") }
        findViewById<ImageButton>(R.id.btnCloseTabIcon).setOnClickListener { closeCurrentTab() }
        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener { showChromeMenu(it) }
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
                btnNativeSwitch.visibility = View.GONE
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url.toString()
                for (ad in adBlockList) { if (url.contains(ad)) return true }
                return false
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                if (view == getCurrentWebView()) etUrl.setText(url)
                swipeRefresh.isRefreshing = false
                injectCustomPlayerLogic(view as WebView)
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
        findViewById<LinearLayout>(R.id.topBar).visibility = View.VISIBLE
        tabContainer.visibility = View.VISIBLE
        findViewById<LinearLayout>(R.id.bottomBar)?.visibility = View.VISIBLE
    }

    private fun injectCustomPlayerLogic(wv: WebView) {
        val d = "$"
        val jsCode = """
            (function() {
                if (window.isMyPlayerInjected) return;
                window.isMyPlayerInjected = true;
                var currentSubtitleData = []; var lastTapTime = 0; var holdTimer = null;

                document.addEventListener('contextmenu', function(e) {
                    let el = e.target;
                    while (el && el.tagName !== 'A') el = el.parentElement;
                    if (el) AndroidInterface.storeLinkText(el.innerText || el.textContent);
                });

                // PAKSA FULLSCREEN + SEMBUNYIKAN UI ANDROID
                window.forceFullscreenVideo = function() {
                    var v = document.querySelector('video');
                    if (!v) { alert('Video tidak ditemukan.'); return; }
                    
                    if (document.body.classList.contains('fake-fullscreen-mode')) {
                        document.body.classList.remove('fake-fullscreen-mode');
                        v.style.cssText = '';
                        AndroidInterface.setFakeFullscreenUI(false);
                    } else {
                        document.body.classList.add('fake-fullscreen-mode');
                        v.style.cssText = 'position:fixed !important; top:0 !important; left:0 !important; width:100vw !important; height:100vh !important; z-index:2147483646 !important; background:black !important; object-fit:contain !important;';
                        AndroidInterface.setFakeFullscreenUI(true);
                    }
                };

                function initPlayer() {
                    var vids = document.getElementsByTagName('video');
                    for(var i=0; i<vids.length; i++) {
                        var v = vids[i];
                        setupOverlay(v);
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

                function setupOverlay(video) {
                    if (video.hasMyOverlay || video.offsetWidth < 50) return;
                    video.hasMyOverlay = true;
                    var overlay = document.createElement('div');
                    overlay.className = 'my-custom-overlay';
                    overlay.style.cssText = 'position:absolute; top:0; left:0; width:100%; height:100%; z-index:2147483647; display:flex; flex-direction:column; align-items:center; -webkit-tap-highlight-color: transparent; pointer-events: auto; padding-bottom: 80px;';
                    
                    var speedIcon = document.createElement('div');
                    speedIcon.innerText = '2x Speed';
                    speedIcon.style.cssText = 'color:white; font-size:14px; font-weight:bold; background:rgba(0,0,0,0.6); padding:4px 12px; border-radius:20px; margin-top: 20px; display:none; pointer-events: none;';
                    overlay.appendChild(speedIcon);

                    var skipText = document.createElement('div');
                    skipText.style.cssText = 'color:white; font-size:25px; font-weight:bold; margin-top: auto; margin-bottom: auto; opacity: 0; transition: opacity 0.3s; pointer-events: none;';
                    overlay.appendChild(skipText);

                    var subBox = document.createElement('div');
                    subBox.style.cssText = 'position:absolute; bottom:15%; width:90%; color:#FFFFFF; font-size:18px; text-shadow: 2px 2px 3px #000; text-align:center; padding:8px; border-radius:8px; pointer-events: none; display: none; background:rgba(0,0,0,0.5);';
                    overlay.appendChild(subBox);

                    overlay.addEventListener('touchstart', function(e) {
                        holdTimer = setTimeout(function() { video.playbackRate = 2.0; speedIcon.style.display = 'block'; }, 400);
                    });

                    overlay.addEventListener('touchend', function(e) {
                        clearTimeout(holdTimer); video.playbackRate = 1.0; speedIcon.style.display = 'none';
                        var now = Date.now(); var diff = now - lastTapTime;
                        var x = e.changedTouches[0].clientX; var w = overlay.offsetWidth;
                        if (diff < 300 && diff > 0) {
                            if (e.cancelable) e.preventDefault();
                            if (x < w * 0.4) { video.currentTime -= 10; skipText.innerText = "⏪ -10s"; skipText.style.opacity = 1; }
                            else if (x > w * 0.6) { video.currentTime += 10; skipText.innerText = "⏩ +10s"; skipText.style.opacity = 1; }
                            setTimeout(() => skipText.style.opacity = 0, 600);
                        } else {
                            if (x > w * 0.4 && x < w * 0.6) { if (video.paused) video.play(); else video.pause(); }
                        }
                        lastTapTime = now;
                    });

                    if (video.parentElement) {
                        video.parentElement.style.position = 'relative';
                        video.parentElement.appendChild(overlay);
                    }

                    video.addEventListener('timeupdate', function() {
                        if (currentSubtitleData.length > 0) {
                            var t = video.currentTime;
                            var s = currentSubtitleData.find(x => t >= x.start && t <= x.end);
                            if (s) { subBox.innerText = s.text; subBox.style.display = 'block'; } 
                            else { subBox.style.display = 'none'; }
                        }
                    });
                }

                window.loadSubtitleContent = function(srt) {
                    currentSubtitleData = [];
                    var pattern = /(\d+)\n(\d{2}:\d{2}:\d{2},\d{3}) --> (\d{2}:\d{2}:\d{2},\d{3})\n([\s\S]*?)(?=\n\n|\n${d}|${d})/g;
                    var match;
                    function t2s(t) { var p = t.split(':'); var s = p[2].split(','); return parseInt(p[0])*3600 + parseInt(p[1])*60 + parseInt(s[0]) + parseInt(s[1])/1000; }
                    while ((match = pattern.exec(srt)) !== null) {
                        currentSubtitleData.push({ start: t2s(match[2]), end: t2s(match[3]), text: match[4].replace(/\n/g, ' ').replace(/<[^>]*>/g, '') });
                    }
                };
                setInterval(initPlayer, 2000);
            })();
        """.trimIndent()
        wv.evaluateJavascript(jsCode, null)
    }

    private fun showChromeMenu(anchor: View) {
        val layout = layoutInflater.inflate(R.layout.popup_browser_menu, null)
        val popup = PopupWindow(layout, dpToPx(240), ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup.animationStyle = R.style.ChromeMenuAnimation

        layout.findViewById<ImageButton>(R.id.menuForward).setOnClickListener {
            if (getCurrentWebView()?.canGoForward() == true) getCurrentWebView()?.goForward()
            popup.dismiss()
        }
        layout.findViewById<ImageButton>(R.id.menuFullscreen).setOnClickListener {
            getCurrentWebView()?.evaluateJavascript("window.forceFullscreenVideo();", null)
            popup.dismiss()
        }
        layout.findViewById<ImageButton>(R.id.menuAddBookmark).setOnClickListener {
            val wv = getCurrentWebView()
            if (wv?.url != null) {
                lifecycleScope.launch(Dispatchers.IO) { db.browserDao().insertBrowserData(BrowserData(url = wv.url!!, title = wv.title!!, content = "", type = "BOOKMARK")) }
                Toast.makeText(this, "Tersimpan!", Toast.LENGTH_SHORT).show()
            }
            popup.dismiss()
        }
        layout.findViewById<ImageButton>(R.id.menuCC).setOnClickListener { pickSubtitleFile.launch("*/*"); popup.dismiss() }

        layout.findViewById<TextView>(R.id.optBookmarks).setOnClickListener { showBookmarkListDialog(); popup.dismiss() }
        layout.findViewById<TextView>(R.id.optHistory).setOnClickListener { showRiwayatDialog(); popup.dismiss() }
        layout.findViewById<TextView>(R.id.optDownloads).setOnClickListener { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)); popup.dismiss() }

        val txtIncognito = layout.findViewById<TextView>(R.id.optIncognito)
        txtIncognito.text = if (isIncognito) "🌐 Normal" else "🕶️ Penyamaran"
        txtIncognito.setOnClickListener { isIncognito = !isIncognito; animateThemeChange(isIncognito); popup.dismiss() }
        layout.findViewById<TextView>(R.id.optChangePin).setOnClickListener { /* showChangePinDialog() */ }
        popup.showAsDropDown(anchor, 0, 0, Gravity.END)
    }

    private fun showSavePasswordDialog(site: String, user: String, pass: String) {
        AlertDialog.Builder(this).setTitle("Simpan Sandi?").setMessage("Simpan untuk $site?")
            .setPositiveButton("Simpan") { _, _ -> lifecycleScope.launch(Dispatchers.IO) { db.browserDao().insertBrowserData(BrowserData(url = site, title = user, content = pass, type = "PASSWORD")) } }
            .setNegativeButton("Tidak", null).show()
    }

    private fun showRiwayatDialog() { lifecycleScope.launch(Dispatchers.IO) { val data = db.browserDao().getAllHistory(); withContext(Dispatchers.Main) { showCustomActionList("📜 Riwayat", data, true) } } }
    private fun showBookmarkListDialog() { lifecycleScope.launch(Dispatchers.IO) { val data = db.browserDao().getBookmarks(); withContext(Dispatchers.Main) { showCustomActionList("🔖 Bookmarks", data, false) } } }

    private fun showCustomActionList(title: String, data: List<Any>, isHistory: Boolean) {
        val titles = data.map { if (it is HistoryEntity) it.title else (it as BrowserData).title }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(title)
            .setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, titles)) { _, which -> showItemOptions(data[which], isHistory) }
            .setNegativeButton("Tutup", null).show()
    }

    private fun showItemOptions(item: Any, isHistory: Boolean) {
        val url = if (item is HistoryEntity) item.url else (item as BrowserData).url
        val id = if (item is HistoryEntity) item.id else (item as BrowserData).id
        AlertDialog.Builder(this).setItems(arrayOf("Buka", "Salin Link", "Hapus")) { _, w ->
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
        val colorFrom = if (incognito) Color.parseColor("#1E1E1E") else Color.parseColor("#2C2C2C")
        val colorTo = if (incognito) Color.parseColor("#2C2C2C") else Color.parseColor("#1E1E1E")
        val anim = ValueAnimator.ofObject(ArgbEvaluator(), colorFrom, colorTo)
        anim.duration = 500
        anim.addUpdateListener { findViewById<LinearLayout>(R.id.topBar).setBackgroundColor(it.animatedValue as Int) }
        anim.start()
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !incognito
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