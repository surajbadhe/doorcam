package org.doorcam.camera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import android.view.TextureView
import androidx.core.content.ContextCompat
import androidx.core.view.setPadding
import com.pedro.common.VideoCodec
import com.pedro.encoder.input.sources.audio.NoAudioSource
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.library.rtsp.RtspStream

class CameraPreviewActivity : ComponentActivity() {
    private lateinit var previewView: TextureView
    private lateinit var status: TextView
    private lateinit var zoomLabel: TextView
    private lateinit var zoomBar: SeekBar
    private lateinit var switchButton: Button
    private lateinit var torchButton: Button
    private lateinit var gridButton: Button
    private val store by lazy { SecureConfigStore(this) }
    private var config: CameraConfig? = null
    private var cameraSource: Camera2Source? = null
    private var previewStream: RtspStream? = null
    private var facing = "BACK"
    private var zoomRatio = 1f
    private var showGrid = true
    private var torchEnabled = false
    private lateinit var grid: FramingGridView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = store.load()
        if (config == null || ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            finish()
            return
        }
        facing = config?.cameraFacing ?: "BACK"
        zoomRatio = config?.cameraZoom ?: 1f
        buildScreen()
        startPreview()
        status.text = "Opening camera preview..."
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16)
            setBackgroundColor(android.graphics.Color.rgb(16, 24, 32))
        }
        root.addView(TextView(this).apply {
            text = "Position your camera"
            textSize = 22f
            setTextColor(android.graphics.Color.WHITE)
        })
        root.addView(TextView(this).apply {
            text = "Turn the phone to choose portrait or landscape. Tap the picture to focus, then adjust zoom."
            setTextColor(android.graphics.Color.LTGRAY)
        })
        val previewFrame = FrameLayout(this)
        previewView = TextureView(this)
        previewFrame.addView(previewView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        grid = FramingGridView(this).apply { visibility = View.VISIBLE }
        previewFrame.addView(grid, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        previewView.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                cameraSource?.tapToFocus(view, event)
                status.text = "Focusing..."
            }
            true
        }
        root.addView(previewFrame, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = 12; bottomMargin = 8 })
        zoomLabel = TextView(this).apply {
            text = "Zoom"
            setTextColor(android.graphics.Color.WHITE)
        }
        root.addView(zoomLabel)
        zoomBar = SeekBar(this).apply { max = 100 }
        root.addView(zoomBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        zoomBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                val source = cameraSource ?: return
                val range = source.getZoomRange()
                val ratio = range.lower + (range.upper - range.lower) * progress / 100f
                runCatching { source.setZoom(ratio) }
                if (fromUser) saveZoom(ratio)
            }
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
        switchButton = actionButton("Switch camera") { switchCamera() }
        torchButton = actionButton("Torch off") { toggleTorch() }
        gridButton = actionButton("Grid on") {
            showGrid = !showGrid
            grid.visibility = if (showGrid) View.VISIBLE else View.GONE
            gridButton.text = if (showGrid) "Grid on" else "Grid off"
        }
        controls.addView(switchButton, equalButtonLayout())
        controls.addView(torchButton, equalButtonLayout())
        controls.addView(gridButton, equalButtonLayout())
        root.addView(controls)
        status = TextView(this).apply {
            text = "Starting preview..."
            setTextColor(android.graphics.Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 8)
        }
        root.addView(status)
        root.addView(Button(this).apply {
            text = "Done"
            setOnClickListener { finish() }
        })
        setContentView(root)
    }

    private fun equalButtonLayout() = LinearLayout.LayoutParams(0,
        ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 6 }

    private fun actionButton(title: String, action: () -> Unit) = Button(this).apply {
        text = title
        setOnClickListener { action() }
    }

    private fun startPreview() {
        if (previewStream != null || isFinishing || isDestroyed) return
        try {
            val selectedConfig = config ?: error("Pair this phone with the hub first")
            val quality = StreamQuality.fromId(selectedConfig.videoQuality)
            val source = Camera2Source(this).apply {
                if (facing == "FRONT") switchCamera()
            }
            val localPreview = RtspStream(this, object : com.pedro.common.ConnectChecker {
                override fun onConnectionStarted(url: String) = Unit
                override fun onConnectionSuccess() = Unit
                override fun onConnectionFailed(reason: String) = Unit
                override fun onNewBitrate(bitrate: Long) = Unit
                override fun onDisconnect() = Unit
                override fun onAuthError() = Unit
                override fun onAuthSuccess() = Unit
            }, source, NoAudioSource()).apply { setVideoCodec(VideoCodec.H264) }
            if (!localPreview.prepareVideo(quality.width, quality.height, quality.bitrate, quality.fps,
                    rotation = 90)) error("Camera could not prepare ${quality.id} preview")
            localPreview.startPreview(previewView, true)
            previewStream = localPreview
            cameraSource = source
            switchButton.isEnabled = true
            zoomLabel.text = "Zoom ${"%.1f".format(zoomRatio)}x"
            applyZoom(source, zoomRatio)
            torchButton.isEnabled = true
            torchButton.text = if (source.isLanternEnabled()) "Torch on" else "Torch off"
            status.text = "Live preview ready. Turn the phone to switch between portrait and landscape."
        } catch (error: Exception) {
            releasePreview()
            status.text = "Could not start camera preview: ${error.message ?: "try the other camera"}"
        }
    }

    private fun applyZoom(source: Camera2Source, requested: Float) {
        previewView.postDelayed({
            if (source.isRunning() && previewStream != null) {
                val range = source.getZoomRange()
                zoomRatio = requested.coerceIn(range.lower, range.upper)
                source.setZoom(zoomRatio)
                zoomLabel.text = "Zoom ${"%.1f".format(zoomRatio)}x"
                val progress = if (range.upper <= range.lower) 0 else
                    (((zoomRatio - range.lower) / (range.upper - range.lower)) * 100).toInt()
                if (!zoomBar.isPressed) zoomBar.progress = progress
            } else if (previewStream != null) applyZoom(source, requested)
        }, 200)
    }

    private fun switchCamera() {
        val source = cameraSource ?: return
        runCatching { source.switchCamera() }.onFailure {
            status.text = "Could not switch camera: ${it.message ?: "camera unavailable"}"
            return
        }
        facing = if (facing == "BACK") "FRONT" else "BACK"
        torchEnabled = false
        torchButton.text = "Torch off"
        status.text = "Switched to ${facing.lowercase()} camera."
        saveSettings()
    }

    private fun toggleTorch() {
        val source = cameraSource ?: return
        torchEnabled = !torchEnabled
        val result = if (torchEnabled) runCatching { source.enableLantern() }
            else runCatching { source.disableLantern() }
        if (result.isFailure) {
            torchEnabled = false
            status.text = "Torch is unavailable on this camera."
        }
        torchButton.text = if (torchEnabled) "Torch on" else "Torch off"
    }

    private fun saveZoom(ratio: Float) {
        zoomRatio = ratio
        saveSettings()
    }

    private fun saveSettings() {
        config?.let { saved ->
            store.save(saved.copy(cameraFacing = facing, cameraZoom = zoomRatio))
        }
    }

    private fun releasePreview() {
        runCatching { previewStream?.release() }
        previewStream = null
        cameraSource = null
    }

    override fun onDestroy() {
        saveSettings()
        releasePreview()
        super.onDestroy()
    }
}

private class FramingGridView(context: android.content.Context) : View(context) {
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        alpha = 150
        strokeWidth = 1.5f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val frameRatio = if (width >= height) 16f / 9f else 9f / 16f
        val frameWidth: Float
        val frameHeight: Float
        val left: Float
        val top: Float
        if (width.toFloat() / height > frameRatio) {
            frameHeight = height.toFloat()
            frameWidth = frameHeight * frameRatio
            left = (width - frameWidth) / 2f
            top = 0f
        } else {
            frameWidth = width.toFloat()
            frameHeight = frameWidth / frameRatio
            left = 0f
            top = (height - frameHeight) / 2f
        }
        canvas.drawLine(left + frameWidth / 3f, top, left + frameWidth / 3f,
            top + frameHeight, gridPaint)
        canvas.drawLine(left + frameWidth * 2f / 3f, top, left + frameWidth * 2f / 3f,
            top + frameHeight, gridPaint)
        canvas.drawLine(left, top + frameHeight / 3f, left + frameWidth,
            top + frameHeight / 3f, gridPaint)
        canvas.drawLine(left, top + frameHeight * 2f / 3f, left + frameWidth,
            top + frameHeight * 2f / 3f, gridPaint)
    }
}
