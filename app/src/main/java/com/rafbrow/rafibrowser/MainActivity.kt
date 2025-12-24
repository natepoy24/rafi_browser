package com.rafbrow.rafibrowser

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.*
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var btnSubtitle: Button

    // Launcher untuk memilih file subtitle dari penyimpanan HP
    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                injectSubtitle(uri)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.myWebView)
        btnSubtitle = findViewById(R.id.btnSubtitle)

        setupWebView()

        // Ganti URL ini dengan target situsmu (Mendukung Streamtape, HLS, Blob, dll)
        webView.loadUrl("https://streamtape.com/v/TargetVideoKamu")

        btnSubtitle.setOnClickListener {
            openFilePicker()
        }
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            // User Agent penting agar situs mendeteksi kita sebagai Mobile Chrome (bukan bot/desktop)
            userAgentString = "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }

        webView.webChromeClient = WebChromeClient() // Diperlukan untuk support video full logic

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Suntikkan logika player kita setiap kali halaman selesai loading
                injectCustomPlayerLogic()
            }

            // Paksa link tetap buka di dalam aplikasi (bukan melempar ke Chrome luar)
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }
        }
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*" // Memilih semua file, nanti diproses teksnya
        }
        filePickerLauncher.launch(intent)
    }

    // Fungsi membaca file .srt lalu kirim ke JavaScript
    private fun injectSubtitle(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val reader = BufferedReader(InputStreamReader(inputStream))
            val sb = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                // Escape backslash dan new line agar tidak merusak string JS
                sb.append(line).append("\\n")
            }
            reader.close()

            // Escape tanda kutip satu (') agar string JS valid
            val srtContent = sb.toString().replace("'", "\\'")

            // Kirim teks subtitle ke fungsi JavaScript di WebView
            webView.evaluateJavascript("loadSubtitleContent('$srtContent');", null)
            Toast.makeText(this, "Subtitle berhasil dimuat!", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Toast.makeText(this, "Gagal memuat file: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun injectCustomPlayerLogic() {
        // Logika JavaScript Murni (Minified logic for cleanliness)
        val jsCode = """
            (function() {
                // Cegah injeksi berulang
                if (window.isMyPlayerInjected) return;
                window.isMyPlayerInjected = true;
                
                console.log("Injecting Ultimate Player Script (Plyr + HLS + Blob Support)...");

                var currentSubtitleData = []; 
                var lastTapTime = 0; 
                var holdTimer = null;

                // Fungsi utama inisialisasi player
                function initPlayer() {
                    var videos = document.getElementsByTagName('video');
                    if (videos.length === 0) return;
                    
                    // Loop semua video karena kadang ada video iklan kecil/preview
                    for(var i=0; i<videos.length; i++) {
                        setupOverlay(videos[i]);
                    }
                }

                function setupOverlay(video) {
                    // Cek properti khusus untuk menghindari double overlay
                    if (video.hasMyOverlay) return; 

                    // Filter: Jangan pasang di video sangat kecil (biasanya iklan banner video)
                    if (video.offsetWidth < 100 || video.offsetHeight < 100) return;

                    video.hasMyOverlay = true;
                    console.log("Video Valid Ditemukan. Memasang Overlay...");

                    var overlay = document.createElement('div');
                    overlay.className = 'my-custom-overlay';
                    
                    // Style Overlay: Transparan, Fullscreen menutupi video, tapi event klik diatur manual
                    overlay.style.cssText = 'position:absolute; top:0; left:0; width:100%; height:100%; z-index:99999; display:flex; flex-direction:column; justify-content:center; align-items:center; overflow:hidden; -webkit-tap-highlight-color: transparent;';
                    
                    // --- UI ELEMENTS ---
                    
                    // 1. Indikator SPEED (Tengah)
                    var speedText = document.createElement('div');
                    speedText.innerText = '⚡ 2X SPEED';
                    speedText.style.cssText = 'color:white; font-size:24px; font-weight:bold; background:rgba(0,0,0,0.7); padding:15px 30px; border-radius:30px; display:none; margin-bottom: 20px; pointer-events: none; user-select: none;';
                    overlay.appendChild(speedText);

                    // 2. Indikator SKIP (Feedback Visual)
                    var skipText = document.createElement('div');
                    skipText.style.cssText = 'color:white; font-size:35px; font-weight:bold; text-shadow: 0px 0px 8px black; opacity: 0; transition: opacity 0.3s; pointer-events: none; user-select: none;';
                    overlay.appendChild(skipText);

                    // 3. Container Subtitle (Bawah)
                    var subBox = document.createElement('div');
                    subBox.style.cssText = 'position:absolute; bottom:15%; width:90%; color:#FFD700; font-size:18px; font-weight: 500; text-shadow: 2px 2px 3px #000; text-align:center; background:rgba(0,0,0,0.5); padding:8px; border-radius:8px; pointer-events: none; opacity: 0.9;';
                    overlay.appendChild(subBox);

                    // --- LOGIKA GESTURE ---
                    
                    // A. TOUCH START (Mendeteksi Hold)
                    overlay.addEventListener('touchstart', function(e) {
                       // Timer untuk membedakan Tap vs Hold
                       holdTimer = setTimeout(function() {
                           video.playbackRate = 2.0;
                           speedText.style.display = 'block';
                       }, 250); // Delay 250ms sebelum masuk mode cepat
                    }, {passive: false});

                    // B. TOUCH END (Mendeteksi Release atau Tap)
                    overlay.addEventListener('touchend', function(e) {
                       clearTimeout(holdTimer);
                       video.playbackRate = 1.0;
                       speedText.style.display = 'none';

                       var currentTime = new Date().getTime();
                       var tapLength = currentTime - lastTapTime;
                       
                       // Jika selisih waktu antar tap < 300ms, itu DOUBLE TAP
                       if (tapLength < 300 && tapLength > 0) {
                           e.preventDefault(); 
                           e.stopPropagation(); // PENTING: Mencegah Plyr nge-pause video karena double tap kita

                           var touchX = e.changedTouches[0].clientX;
                           var screenWidth = window.innerWidth;

                           if (touchX < screenWidth * 0.35) {
                               // Zona KIRI: Rewind
                               video.currentTime = Math.max(0, video.currentTime - 10);
                               showFeedback("⏪ -10s");
                           } else if (touchX > screenWidth * 0.65) {
                               // Zona KANAN: Forward
                               // Gunakan 999999 sebagai fallback jika duration NaN (biasa terjadi di blob/m3u8 live)
                               var duration = isNaN(video.duration) ? 999999 : video.duration;
                               video.currentTime = Math.min(duration, video.currentTime + 10);
                               showFeedback("⏩ +10s");
                           } else {
                               // Zona TENGAH: Play/Pause (Single tap logic usually, but here on double tap middle)
                               // Opsional: Double tap tengah bisa buat Play/Pause atau Zoom
                               if (video.paused) video.play(); else video.pause();
                           }
                       } 
                       // Jika Single Tap (bisa ditambahkan logika UI controls muncul/hilang disini jika mau)
                       
                       lastTapTime = currentTime;
                    }, {passive: false});

                    // Helper Animasi Feedback
                    function showFeedback(text) {
                        skipText.innerText = text;
                        skipText.style.opacity = '1';
                        setTimeout(() => { skipText.style.opacity = '0'; }, 600);
                    }

                    // --- PEMASANGAN KE DOM ---
                    if (video.parentElement) {
                        // Pastikan parent relative agar overlay menempel pas di video
                        var parentStyle = window.getComputedStyle(video.parentElement);
                        if (parentStyle.position === 'static') {
                            video.parentElement.style.position = 'relative';
                        }
                        video.parentElement.appendChild(overlay);
                    }

                    // --- SINKRONISASI SUBTITLE ---
                    video.addEventListener('timeupdate', function() {
                        if (currentSubtitleData.length > 0) {
                            var now = video.currentTime;
                            // Cari subtitle yang pas dengan waktu sekarang
                            var activeSub = currentSubtitleData.find(s => now >= s.start && now <= s.end);
                            subBox.innerText = activeSub ? activeSub.text : '';
                            subBox.style.display = activeSub ? 'block' : 'none'; // Sembunyikan box jika tidak ada teks
                        }
                    });
                }

                // --- FUNGSI PARSING SRT (Dipanggil dari Kotlin) ---
                window.loadSubtitleContent = function(srtText) {
                    currentSubtitleData = [];
                    // Regex standard SRT parser
                    var pattern = /(\d+)\n(\d{2}:\d{2}:\d{2},\d{3}) --> (\d{2}:\d{2}:\d{2},\d{3})\n([\s\S]*?)(?=\n\n|\n$|$)/g;
                    var match;
                    
                    function timeToSeconds(t) {
                        var parts = t.split(':');
                        var sec = parts[2].split(',');
                        return parseInt(parts[0]) * 3600 + parseInt(parts[1]) * 60 + parseInt(sec[0]) + parseInt(sec[1]) / 1000;
                    }
                    
                    while ((match = pattern.exec(srtText)) !== null) {
                        currentSubtitleData.push({
                            start: timeToSeconds(match[2]),
                            end: timeToSeconds(match[3]),
                            text: match[4].replace(/\\n/g, '\n').replace(/<[^>]*>/g, '') // Bersihkan tag HTML
                        });
                    }
                    console.log("Subtitle Parsed: " + currentSubtitleData.length + " lines");
                    alert("Subtitle berhasil dimuat! (" + currentSubtitleData.length + " baris)");
                };

                // Cek DOM secara berkala untuk menangani video yang di-load via AJAX/SPA
                setInterval(initPlayer, 2000);
            })();
        """.trimIndent()

        webView.evaluateJavascript(jsCode, null)
    }
}