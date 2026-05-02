package com.rafbrow.rafibrowser

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

class PlayerActivity : AppCompatActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var tvGestureFeedback: TextView
    private lateinit var tvSpeedFeedback: TextView
    private lateinit var btnLoadSubtitle: ImageButton

    private var videoUrl: String? = null
    private var videoTitle: String? = null
    private var userAgent: String? = null

    private val pickSubtitle = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { applySubtitle(it) }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContentView(R.layout.activity_player)

        videoUrl = intent.getStringExtra("videoUrl")
        videoTitle = intent.getStringExtra("videoTitle")
        userAgent = intent.getStringExtra("userAgent")

        playerView = findViewById(R.id.playerView)
        tvGestureFeedback = findViewById(R.id.tvGestureFeedback)
        tvSpeedFeedback = findViewById(R.id.tvSpeedFeedback)
        btnLoadSubtitle = findViewById(R.id.btnLoadSubtitle)

        btnLoadSubtitle.setOnClickListener {
            pickSubtitle.launch("*/*")
        }

        initializePlayer()
        setupGestureControls()
    }

    @OptIn(UnstableApi::class)
    private fun initializePlayer() {
        val dataSourceFactory = DefaultHttpDataSource.Factory()
        userAgent?.let { dataSourceFactory.setUserAgent(it) }

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory))
            .build()

        playerView.player = player

        videoUrl?.let { url ->
            val mediaItemBuilder = MediaItem.Builder().setUri(url)
            
            // Explicitly set MIME type for adaptive streams
            when {
                url.contains(".m3u8") -> mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
                url.contains(".mpd") -> mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_MPD)
                url.contains(".mp4") -> mediaItemBuilder.setMimeType(MimeTypes.VIDEO_MP4)
            }
            
            val mediaItem = mediaItemBuilder.build()
            player.setMediaItem(mediaItem)
            player.prepare()
            player.play()
        }
        
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Toast.makeText(this@PlayerActivity, "Error: ${error.message}", Toast.LENGTH_LONG).show()
            }
        })
    }

    @OptIn(UnstableApi::class)
    private fun applySubtitle(uri: Uri) {
        videoUrl?.let { url ->
            val subtitleConfig = MediaItem.SubtitleConfiguration.Builder(uri)
                .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                .setLanguage("id")
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()

            val mediaItem = MediaItem.Builder()
                .setUri(url)
                .setSubtitleConfigurations(listOf(subtitleConfig))
                .build()

            val currentPosition = player.currentPosition
            player.setMediaItem(mediaItem)
            player.seekTo(currentPosition)
            player.prepare()
            player.play()
            Toast.makeText(this, "Subtitle diterapkan", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestureControls() {
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val screenWidth = playerView.width
                if (e.x < screenWidth / 2) {
                    player.seekTo((player.currentPosition - 10000).coerceAtLeast(0))
                    showFeedback("⏪ -10s")
                } else {
                    player.seekTo((player.currentPosition + 10000).coerceAtMost(player.duration))
                    showFeedback("⏩ +10s")
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                player.playbackParameters = PlaybackParameters(2.0f)
                tvSpeedFeedback.visibility = View.VISIBLE
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (playerView.isControllerFullyVisible) {
                    playerView.hideController()
                } else {
                    playerView.showController()
                }
                return true
            }
        })

        playerView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                if (player.playbackParameters.speed == 2.0f) {
                    player.playbackParameters = PlaybackParameters(1.0f)
                    tvSpeedFeedback.visibility = View.GONE
                }
            }
            true
        }
    }

    private fun showFeedback(text: String) {
        tvGestureFeedback.text = text
        tvGestureFeedback.visibility = View.VISIBLE
        tvGestureFeedback.alpha = 1f
        tvGestureFeedback.animate().alpha(0f).setDuration(500).withEndAction {
            tvGestureFeedback.visibility = View.GONE
        }.start()
    }

    override fun onPause() {
        super.onPause()
        player.pause()
    }

    override fun onResume() {
        super.onResume()
        player.play()
    }

    override fun onDestroy() {
        super.onDestroy()
        player.release()
    }
}
