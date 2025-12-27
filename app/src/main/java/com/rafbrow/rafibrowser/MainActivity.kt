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

    private val adBlockList = listOf("tsyndicate.com", "diffusedpassionquaking.com", "doubleclick.net", "popads.net")

    // --- INTERFACE UNTUK LOGIN ---
    inner class WebAppInterface {
        @JavascriptInterface
        fun processLogin(site: String, user: String, pass: String) {
            runOnUiThread {
                if (!isIncognito && user.isNotEmpty() && pass.isNotEmpty()) {
                    showSavePasswordDialog(site, user, pass)
                }
            }
        }
    }

    // --- PICKER SUBTITLE ---
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
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                loadWeb(etUrl.text.toString()); hideKeyboard(); true
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

    // --- SETTINGS WEBVIEW & INJECT PLAYER ---
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
                // Tampilkan Overlay di mode Fullscreen
                wv.evaluateJavascript("if(window.updateOverlayDisplay) window.updateOverlayDisplay(true);", null)
            }
            override fun onHideCustomView() {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                (window.decorView as FrameLayout).removeView(fullscreenContainer)
                fullscreenContainer = null
                customViewCallback?.onCustomViewHidden()
                customView = null
                findViewById<RelativeLayout>(R.id.mainRootLayout).visibility = View.VISIBLE
                wv.evaluateJavascript("if(window.updateOverlayDisplay) window.updateOverlayDisplay(false);", null)
            }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url.toString()
                for (ad in adBlockList) { if (url.contains(ad)) return true }
                return false
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                if (view == getCurrentWebView()) etUrl.setText(url)
                swipeRefresh.isRefreshing = false
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
        val d = "$" // Untuk menghindari konflik String Template Kotlin
        val jsCode = """
            (function() {
                if (window.isMyPlayerInjected) return;
                window.isMyPlayerInjected = true;
                var currentSubtitleData = []; var lastTapTime = 0; var holdTimer = null;

                function listenForLogin() {
                    var forms = document.getElementsByTagName('form');
                    for (var i = 0; i < forms.length; i++) {
                        forms[i].addEventListener('submit', function() {
                            var user = ""; var pass = "";
                            var inputs = this.getElementsByTagName('input');
                            for (var j = 0; j < inputs.length; j++) {
                                if (inputs[j].type === 'password') {
                                    pass = inputs[j].value;
                                    if (j > 0) user = inputs[j-1].value;
                                }
                            }
                            if (pass !== "") AndroidInterface.processLogin(window.location.hostname, user, pass);
                        });
                    }
                }

                window.updateOverlayDisplay = function(show) {
                    var overlays = document.querySelectorAll('.my-custom-overlay');
                    overlays.forEach(function(ov) { ov.style.display = show ? 'flex' : 'none'; });
                };

                function initPlayer() {
                    var vids = document.getElementsByTagName('video');
                    for(var i=0; i<vids.length; i++) setupOverlay(vids[i]);
                }

                function setupOverlay(video) {
                    if (video.hasMyOverlay || video.offsetWidth < 100) return;
                    video.hasMyOverlay = true;
                    var overlay = document.createElement('div');
                    overlay.className = 'my-custom-overlay';
                    overlay.style.cssText = 'position:absolute; top:0; left:0; width:100%; height:100%; z-index:2147483647; display:none; flex-direction:column; align-items:center; -webkit-tap-highlight-color: transparent;';
                    
                    var speed = document.createElement('div');
                    speed.innerText = '2x Speed';
                    speed.style.cssText = 'color:white; font-size:14px; font-weight:bold; background:rgba(0,0,0,0.6); padding:4px 12px; border-radius:20px; margin-top: 20px; display:none; pointer-events: none;';
                    overlay.appendChild(speed);

                    var skip = document.createElement('div');
                    skip.style.cssText = 'color:white; font-size:25px; font-weight:bold; margin-top: auto; margin-bottom: auto; opacity: 0; transition: opacity 0.3s; pointer-events: none;';
                    overlay.appendChild(skip);

                    var subBox = document.createElement('div');
                    subBox.style.cssText = 'position:absolute; bottom:15%; width:90%; color:#FFFFFF; font-size:18px; text-shadow: 2px 2px 3px #000; text-align:center; padding:8px; border-radius:8px; pointer-events: none; display: none; background:rgba(0,0,0,0.5);';
                    overlay.appendChild(subBox);

                    overlay.addEventListener('touchstart', function(e) {
                        holdTimer = setTimeout(function() { video.playbackRate = 2.0; speed.style.display = 'block'; }, 400);
                    });

                    overlay.addEventListener('touchend', function(e) {
                        clearTimeout(holdTimer); video.playbackRate = 1.0; speed.style.display = 'none';
                        var now = Date.now(); var diff = now - lastTapTime;
                        var x = e.changedTouches[0].clientX; var w = window.innerWidth;
                        if (diff < 300 && diff > 0) {
                            if (e.cancelable) e.preventDefault();
                            if (x < w * 0.4) { video.currentTime -= 10; skip.innerText = "⏪ -10s"; skip.style.opacity = 1; }
                            else if (x > w * 0.6) { video.currentTime += 10; skip.innerText = "⏩ +10s"; skip.style.opacity = 1; }
                            setTimeout(() => skip.style.opacity = 0, 600);
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
                
                listenForLogin();
                setInterval(initPlayer, 2000);
            })();
        """.trimIndent()
        wv.evaluateJavascript(jsCode, null)
    }

    private fun showChromeMenu(anchor: View) {
        val layout = layoutInflater.inflate(R.layout.popup_browser_menu, null)
        val popup = PopupWindow(layout, dpToPx(240), ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup.animationStyle = R.style.ChromeMenuAnimation

        layout.findViewById<ImageButton>(R.id.menuAddBookmark).setOnClickListener {
            val wv = getCurrentWebView()
            if (wv?.url != null) {
                lifecycleScope.launch(Dispatchers.IO) { db.browserDao().insertBrowserData(BrowserData(url = wv.url!!, title = wv.title!!, content = "", type = "BOOKMARK")) }
                Toast.makeText(this, "Tersimpan!", Toast.LENGTH_SHORT).show()
            }
            popup.dismiss()
        }
        layout.findViewById<ImageButton>(R.id.menuDownload).setOnClickListener { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)); popup.dismiss() }
        layout.findViewById<ImageButton>(R.id.menuCC).setOnClickListener { pickSubtitleFile.launch("*/*"); popup.dismiss() }
        layout.findViewById<TextView>(R.id.optBookmarks).setOnClickListener { showBookmarkListDialog(); popup.dismiss() }
        layout.findViewById<TextView>(R.id.optHistory).setOnClickListener { showRiwayatDialog(); popup.dismiss() }
        val txtIncognito = layout.findViewById<TextView>(R.id.optIncognito)
        txtIncognito.text = if (isIncognito) "🌐 Normal" else "🕶️ Penyamaran"
        txtIncognito.setOnClickListener { isIncognito = !isIncognito; animateThemeChange(isIncognito); popup.dismiss() }
        layout.findViewById<TextView>(R.id.optChangePin).setOnClickListener { showChangePinDialog(); popup.dismiss() }
        popup.showAsDropDown(anchor, 0, 0, Gravity.END)
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
        val biometricPrompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { lockOverlay.visibility = View.GONE }
        })
        val promptInfo = BiometricPrompt.PromptInfo.Builder().setTitle("Kunci").setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG).setNegativeButtonText("PIN").build()
        biometricPrompt.authenticate(promptInfo)
        findViewById<Button>(R.id.btnUnlockPin).setOnClickListener {
            val input = findViewById<EditText>(R.id.etPinEntry).text.toString()
            if (input == getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_PIN, "1234")) { lockOverlay.visibility = View.GONE; hideKeyboard() } else Toast.makeText(this, "Salah!", Toast.LENGTH_SHORT).show()
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
    private fun showRiwayatDialog() { lifecycleScope.launch(Dispatchers.IO) { val data = db.browserDao().getAllHistory(); withContext(Dispatchers.Main) { showCustomActionList("📜 Riwayat", data, true) } } }
    private fun showBookmarkListDialog() { lifecycleScope.launch(Dispatchers.IO) { val data = db.browserDao().getBookmarks(); withContext(Dispatchers.Main) { showCustomActionList("🔖 Bookmarks", data, false) } } }

    override fun onCreateContextMenu(menu: ContextMenu, v: View, m: ContextMenu.ContextMenuInfo?) {
        val res = (v as WebView).hitTestResult
        if (res.extra != null) { menu.setHeaderTitle("Opsi Link"); menu.add(0, 1, 0, "Tab Baru"); menu.add(0, 2, 0, "Salin Link") }
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        val url = getCurrentWebView()?.hitTestResult?.extra ?: ""
        if (item.itemId == 1 && url.isNotEmpty()) addNewTab(url) else if (item.itemId == 2 && url.isNotEmpty()) (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("URL", url))
        return true
    }
}