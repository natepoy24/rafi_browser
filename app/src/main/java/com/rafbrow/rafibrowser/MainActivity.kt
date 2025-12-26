package com.rafbrow.rafibrowser

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.util.Patterns
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.*
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.rafbrow.rafibrowser.data.*
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var webViewContainer: FrameLayout
    private lateinit var tabContainer: LinearLayout
    private lateinit var etUrl: EditText
    private lateinit var lockOverlay: LinearLayout
    private lateinit var btnSubtitle: Button
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var toggleIncognito: ToggleButton
    private lateinit var db: AppDatabase
    private lateinit var btnSettings: ImageButton
    private lateinit var btnNewTabIcon: ImageButton
    private lateinit var btnCloseTabIcon: ImageButton

    private val tabList = mutableListOf<WebView>()
    private var currentTabIndex = -1
    private val MY_PIN = "1234"

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) result.data?.data?.let { injectSubtitle(it) }
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun processLogin(url: String, user: String, pass: String) {
            runOnUiThread {
                if (!toggleIncognito.isChecked && user.isNotEmpty() && pass.isNotEmpty()) {
                    showSavePasswordDialog(url, user, pass)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        db = AppDatabase.getDatabase(this)

        // Binding UI
        webViewContainer = findViewById(R.id.webViewContainer)
        tabContainer = findViewById(R.id.tabContainer)
        etUrl = findViewById(R.id.etUrl)
        lockOverlay = findViewById(R.id.lockOverlay)
        btnSubtitle = findViewById(R.id.btnSubtitle)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)
        toggleIncognito = findViewById(R.id.toggleIncognito)
        btnSettings = findViewById(R.id.btnSettings)
        btnNewTabIcon = findViewById(R.id.btnNewTabIcon)
        btnCloseTabIcon = findViewById(R.id.btnCloseTabIcon)

        val topBar = findViewById<LinearLayout>(R.id.topBar)

        ViewCompat.setOnApplyWindowInsetsListener(topBar) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val displayCutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            
            // Memberikan padding atas sesuai tinggi Status Bar + Notch kamera
            val topPadding = if (systemBars.top > displayCutout.top) systemBars.top else displayCutout.top
            
            v.setPadding(v.paddingLeft, topPadding, v.paddingRight, v.paddingBottom)
            insets
        }

        setupSecurity()
        setupChromeStyleUI()

        if (savedInstanceState == null) addNewTab("https://www.google.com")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wv = getCurrentWebView()
                if (customView != null) exitFullscreen()
                else if (wv?.canGoBack() == true) wv.goBack()
                else if (tabList.size > 1) closeCurrentTab()
                else finish()
            }
        })
    }

    private fun setupChromeStyleUI() {
        // Klik Ikon Plus (+) untuk Tab Baru
        btnNewTabIcon.setOnClickListener { addNewTab("https://www.google.com") }

        // Klik Ikon Silang (x) untuk Hapus Tab
        btnCloseTabIcon.setOnClickListener { if (tabList.size > 1) closeCurrentTab() }

        // Tombol Setting (Pusat Kendali)
        btnSettings.setOnClickListener { showSettingsMenu() }
        
        // Bookmark situs ini
        findViewById<ImageButton>(R.id.btnBookmarkToggle).setOnClickListener {
            val wv = getCurrentWebView()
            wv?.url?.let { url -> 
                saveToDb(url, wv.title ?: "No Title", "BOOKMARK")
                Toast.makeText(this, "Ditambahkan ke Bookmark", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // --- MENU SETELAN (SETTINGS CONTAINER) ---
    private fun showSettingsMenu() {
        val options = arrayOf(
            "🔖 Daftar Bookmark",
            "🔑 Pengelola Sandi",
            "📥 Riwayat Unduhan",
            "📜 Riwayat Penjelajahan",
            "🕶️ Mode Penyamaran: ${if (toggleIncognito.isChecked) "AKTIF" else "MATI"}"
        )

        AlertDialog.Builder(this)
            .setTitle("Setelan Browser")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showBookmarkList()
                    1 -> showPasswordManager()
                    2 -> showDownloadHistory()
                    3 -> showHistoryList()
                    4 -> toggleIncognito.performClick() // Aktifkan incognito dari menu
                }
            }
            .show()
    }

    // --- FITUR DAFTAR BOOKMARK (BARU) ---
    private fun showBookmarkList() {
        lifecycleScope.launch(Dispatchers.IO) {
            val bookmarks = db.browserDao().getBookmarks()
            withContext(Dispatchers.Main) {
                if (bookmarks.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Belum ada bookmark", Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                
                val list = bookmarks.map { "${it.title}\n${it.url}" }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Daftar Bookmark")
                    .setAdapter(ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1, list)) { _, which ->
                        getCurrentWebView()?.loadUrl(bookmarks[which].url)
                    }
                    .setNegativeButton("Tutup", null)
                    .show()
            }
        }
    }

    // --- FITUR RIWAYAT (BARU) ---
    private fun showHistoryList() {
        lifecycleScope.launch(Dispatchers.IO) {
            val history = db.browserDao().getAllHistory()
            withContext(Dispatchers.Main) {
                val list = history.map { "${it.title}\n${it.url}" }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Riwayat")
                    .setAdapter(ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1, list)) { _, which ->
                        getCurrentWebView()?.loadUrl(history[which].url)
                    }
                    .setPositiveButton("Hapus Semua") { _, _ ->
                        lifecycleScope.launch(Dispatchers.IO) { db.browserDao().clearHistory() }
                    }
                    .show()
            }
        }
    }

    private fun setupDownloadListener(wv: WebView) {
        wv.setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)
            
            AlertDialog.Builder(this)
                .setTitle("Unduh File")
                .setMessage("Apakah Anda ingin mengunduh $fileName?")
                .setPositiveButton("Unduh") { _, _ ->
                    startDownload(url, userAgent, contentDisposition, mimetype, fileName)
                }
                .setNegativeButton("Batal", null)
                .show()
        }
    }

    private fun startDownload(url: String, userAgent: String, contentDisposition: String, mimetype: String, fileName: String) {
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setMimeType(mimetype)
            addRequestHeader("User-Agent", userAgent)
            setDescription("Mengunduh file...")
            setTitle(fileName)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        }

        val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        dm.enqueue(request)

        // Simpan ke Database
        lifecycleScope.launch(Dispatchers.IO) { 
            db.browserDao().insertDownload(DownloadData(fileName = fileName, url = url)) 
        }
        Toast.makeText(this, "Unduhan dimulai...", Toast.LENGTH_SHORT).show()
    }

    private fun showDownloadHistory() {
        lifecycleScope.launch(Dispatchers.IO) {
            val list = db.browserDao().getAllDownloads()
            withContext(Dispatchers.Main) {
                val display = list.map { "${it.fileName}\n${it.url}" }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Riwayat Unduhan")
                    .setAdapter(ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1, display)) { _, which ->
                        // Opsional: Buka folder download atau link kembali
                        loadWeb(list[which].url)
                    }
                    .setNegativeButton("Tutup", null)
                    .show()
            }
        }
    }

    // --- FITUR AUTOFILL ---
    private fun triggerAutofill(wv: WebView, url: String) {
        val domain = Uri.parse(url).host ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val data = db.browserDao().getPasswordForSite(domain)
            data?.let {
                withContext(Dispatchers.Main) {
                    val js = """
                        (function() {
                            var inputs = document.getElementsByTagName('input');
                            for (var i = 0; i < inputs.length; i++) {
                                if (inputs[i].type === 'password') {
                                    inputs[i].value = '${it.password}';
                                    if (i > 0) inputs[i-1].value = '${it.username}';
                                }
                            }
                        })();
                    """.trimIndent()
                    wv.evaluateJavascript(js, null)
                    Toast.makeText(applicationContext, "Autofill berhasil", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showSavePasswordDialog(url: String, user: String, pass: String) {
        AlertDialog.Builder(this)
            .setTitle("Simpan Sandi?")
            .setMessage("Simpan sandi untuk $user di $url?")
            .setPositiveButton("Simpan") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    db.browserDao().insertPassword(PasswordData(site = url, username = user, password = pass))
                }
            }.setNegativeButton("Tidak", null).show()
    }

    // --- KEAMANAN ---
    private fun setupSecurity() {
        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                lockOverlay.visibility = View.GONE
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // PIN fallback disabled for now
            }
        })
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Rafi Browser")
            .setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    // --- TAB & WEBVIEW LOGIC ---
    private fun addNewTab(url: String) {
        val wv = WebView(this)
        setupWebViewSettings(wv)
        tabList.add(wv)
        currentTabIndex = tabList.size - 1
        updateTabSwitcherUI()
        switchTab(currentTabIndex)
        wv.loadUrl(url)
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun setupWebViewSettings(wv: WebView) {
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(wv.settings, true)
            }
            userAgentString = "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }
        
        wv.addJavascriptInterface(WebAppInterface(), "AndroidInterface")

        setupDownloadListener(wv)

        wv.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                customView = view
                customViewCallback = callback
                webViewContainer.visibility = View.GONE
                findViewById<View>(R.id.topBar).visibility = View.GONE
                (btnSubtitle.parent as? ViewGroup)?.removeView(btnSubtitle)
                fullscreenContainer.addView(view)
                val params = FrameLayout.LayoutParams(dpToPx(38), dpToPx(38))
                params.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
                params.setMargins(12, 12, 12, 12)
                fullscreenContainer.addView(btnSubtitle, params)
                fullscreenContainer.visibility = View.VISIBLE
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                WindowCompat.setDecorFitsSystemWindows(window, false)
                WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
                wv.evaluateJavascript("window.updateOverlayDisplay(true);", null)
            }
            override fun onHideCustomView() { exitFullscreen() }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (currentTabIndex != -1 && tabList[currentTabIndex] == view) etUrl.setText(url)
                
                url?.let { currUrl ->
                    val pageTitle = view?.title ?: "No Title"
                    
                    lifecycleScope.launch(Dispatchers.IO) {
                        if (!toggleIncognito.isChecked) {
                            db.browserDao().insertHistory(HistoryEntity(url = currUrl, title = pageTitle))
                        }
                    }
                    triggerAutofill(view as WebView, currUrl)
                }
                injectCustomPlayerLogic(view as WebView)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url.toString()
                return u.contains("tsyndicate") || u.contains("ad_")
            }
        }
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
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    if (i == currentTabIndex) Color.parseColor("#BB86FC") else Color.parseColor("#2C2C2C")
                )
                setTextColor(if (i == currentTabIndex) Color.BLACK else Color.WHITE)
                setOnClickListener { switchTab(i) }
            }
            tabContainer.addView(btn)
        }
    }

    private fun closeCurrentTab() {
        if (tabList.size <= 1) return
        tabList[currentTabIndex].destroy()
        tabList.removeAt(currentTabIndex)
        currentTabIndex = if (currentTabIndex > 0) currentTabIndex - 1 else 0
        switchTab(currentTabIndex)
    }

    private fun saveToDb(url: String, title: String, type: String) {
        lifecycleScope.launch(Dispatchers.IO) { 
            val data = BrowserData(url = url, title = title, type = type)
            db.browserDao().insertBrowserData(data)
        }
    }

    private fun showPasswordManager() {
        lifecycleScope.launch(Dispatchers.IO) {
            val list = db.browserDao().getAllPasswords()
            withContext(Dispatchers.Main) {
                val display = list.map { "${it.site}\nUser: ${it.username}" }
                AlertDialog.Builder(this@MainActivity).setTitle("Sandi Tersimpan")
                    .setAdapter(ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1, display)) { _, which ->
                        val p = list[which]
                        AlertDialog.Builder(this@MainActivity).setTitle("Detail")
                            .setMessage("User: ${p.username}\nPass: ${p.password}")
                            .setPositiveButton("Salin Pass") { _, _ ->
                                (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                                    .setPrimaryClip(android.content.ClipData.newPlainText("pass", p.password))
                            }.show()
                    }.show()
            }
        }
    }

    private fun showHistoryAndBookmarks() {
        lifecycleScope.launch(Dispatchers.IO) {
            val history = db.browserDao().getAllHistory()
            val bookmarks = db.browserDao().getBookmarks()
            withContext(Dispatchers.Main) {
                val options = arrayOf("📜 History", "⭐ Bookmarks")
                AlertDialog.Builder(this@MainActivity).setItems(options) { _, which ->
                    if (which == 0) {
                        val list = history.map { "${it.title}\n${it.url}" }
                        AlertDialog.Builder(this@MainActivity).setAdapter(ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1, list)) { _, i ->
                            getCurrentWebView()?.loadUrl(history[i].url)
                        }.show()
                    } else {
                        val list = bookmarks.map { "${it.title}\n${it.url}" }
                        AlertDialog.Builder(this@MainActivity).setAdapter(ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1, list)) { _, i ->
                            getCurrentWebView()?.loadUrl(bookmarks[i].url)
                        }.show()
                    }
                }.show()
            }
        }
    }

    private fun getCurrentWebView(): WebView? = if (currentTabIndex != -1) tabList[currentTabIndex] else null

    private fun loadWeb(input: String) {
        var url = input.trim()
        if (Patterns.WEB_URL.matcher(url).matches()) { if (!url.startsWith("http")) url = "https://$url" }
        else { url = "https://www.google.com/search?q=$url" }
        getCurrentWebView()?.loadUrl(url)
    }

    private fun exitFullscreen() {
        if (customView == null) return
        getCurrentWebView()?.evaluateJavascript("window.updateOverlayDisplay(false);", null)
        fullscreenContainer.removeAllViews()
        fullscreenContainer.visibility = View.GONE
        webViewContainer.visibility = View.VISIBLE
        findViewById<View>(R.id.topBar).visibility = View.VISIBLE

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        customView = null
    }

    private fun dpToPx(dp: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics).toInt()

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
        filePickerLauncher.launch(intent)
    }

    private fun injectSubtitle(uri: Uri) {
        try {
            val content = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }?.replace("'", "\\'") ?: ""
            getCurrentWebView()?.evaluateJavascript("loadSubtitleContent('$content');", null)
        } catch (e: Exception) { }
    }

    private fun injectCustomPlayerLogic(wv: WebView) {
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
                    speed.innerText = '2x';
                    speed.style.cssText = 'color:white; font-size:12px; font-weight:bold; background:rgba(0,0,0,0.5); padding:2px 10px; border-radius:10px; margin-top: 10px; display:none; pointer-events: none;';
                    overlay.appendChild(speed);

                    var skip = document.createElement('div');
                    skip.style.cssText = 'color:white; font-size:25px; font-weight:bold; margin-top: auto; margin-bottom: auto; opacity: 0; transition: opacity 0.3s; pointer-events: none;';
                    overlay.appendChild(skip);

                    var subBox = document.createElement('div');
                    subBox.style.cssText = 'position:absolute; bottom:12%; width:90%; color:#FFFFFF; font-size:18px; text-shadow: 2px 2px 3px #000; text-align:center; padding:6px; border-radius:8px; pointer-events: none; display: none; background:rgba(0,0,0,0.4);';
                    overlay.appendChild(subBox);

                    overlay.addEventListener('touchstart', function(e) {
                       holdTimer = setTimeout(function() { video.playbackRate = 2.0; speed.style.display = 'block'; }, 250);
                    });

                    overlay.addEventListener('touchend', function(e) {
                       clearTimeout(holdTimer); video.playbackRate = 1.0; speed.style.display = 'none';
                       var now = new Date().getTime(); var diff = now - lastTapTime;
                       var x = e.changedTouches[0].clientX; var w = window.innerWidth;
                       if (diff < 300 && diff > 0) {
                           e.preventDefault();
                           if (x < w * 0.35) { video.currentTime -= 10; skip.innerText = "⏪ -10s"; skip.style.opacity = 1; }
                           else if (x > w * 0.65) { video.currentTime += 10; skip.innerText = "⏩ +10s"; skip.style.opacity = 1; }
                           setTimeout(() => skip.style.opacity = 0, 500);
                       } else {
                           if (x > w * 0.35 && x < w * 0.65) { if (video.paused) video.play(); else video.pause(); }
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
                    var pattern = /(\d+)\n(\d{2}:\d{2}:\d{2},\d{3}) --> (\d{2}:\d{2}:\d{2},\d{3})\n([\s\S]*?)(?=\n\n|\n$|$)/g;
                    var match;
                    function t2s(t) { var p = t.split(':'); var s = p[2].split(','); return parseInt(p[0])*3600 + parseInt(p[1])*60 + parseInt(s[0]) + parseInt(s[1])/1000; }
                    while ((match = pattern.exec(srt)) !== null) {
                        currentSubtitleData.push({ start: t2s(match[2]), end: t2s(match[3]), text: match[4].replace(/\\n/g, '\n').replace(/<[^>]*>/g, '') });
                    }
                };
                
                listenForLogin();
                setInterval(initPlayer, 2000);
            })();
        """.trimIndent()
        wv.evaluateJavascript(jsCode, null)
    }
}
