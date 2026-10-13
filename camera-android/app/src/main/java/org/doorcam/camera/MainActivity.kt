package org.doorcam.camera

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.setPadding
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val qrScanner = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.getStringExtra(QrScannerActivity.EXTRA_HUB_URL)?.let { hubUrl ->
                hub.setText(hubUrl)
                code.setText(result.data?.getStringExtra(QrScannerActivity.EXTRA_PAIR_CODE).orEmpty())
                status.text = "Pairing QR scanned. Pairing with hub..."
                pairCamera()
            }
        }
    }
    private lateinit var hub: EditText
    private lateinit var code: EditText
    private lateinit var name: EditText
    private lateinit var audio: CheckBox
    private lateinit var quality: Spinner
    private lateinit var status: TextView
    private lateinit var pairButton: Button
    private var pendingPreview = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra("status") ?: "Waiting"
            status.text = message
            if (pendingPreview && message == "Stopped for camera setup") {
                pendingPreview = false
                startActivity(Intent(this@MainActivity, CameraPreviewActivity::class.java))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        SecureConfigStore(this).load()?.let {
            hub.setText(it.hubUrl)
            status.text = "Paired camera ${it.cameraId}"
            audio.isChecked = it.audioEnabled
            quality.setSelection(StreamQuality.entries.indexOf(StreamQuality.fromId(it.videoQuality)).coerceAtLeast(0))
        }
        ContextCompat.registerReceiver(this, receiver, IntentFilter(CameraStreamService.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun buildScreen() {
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24) }
        fun field(hintText: String) = EditText(this).apply {
            hint = hintText
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        content.addView(TextView(this).apply { text = "DoorCam Camera"; textSize = 24f })
        content.addView(TextView(this).apply {
            text = "Create a pairing code in the hub viewer, then enter it below."
        })
        hub = field("Hub HTTPS URL (https://PC-IP:8443)").also { it.setText("https://") }
        code = field("One-time pairing code")
        name = field("Camera name")
        content.addView(hub); content.addView(code); content.addView(name)
        content.addView(Button(this).apply {
            text = "Scan pairing QR"
            setOnClickListener { qrScanner.launch(Intent(this@MainActivity, QrScannerActivity::class.java)) }
        })
        audio = CheckBox(this).apply { text = "Send microphone audio"; isChecked = true }
        content.addView(audio)
        content.addView(TextView(this).apply { text = "Video quality (applies when the stream starts)" })
        quality = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                StreamQuality.entries.map { it.label })
        }
        content.addView(quality)
        pairButton = Button(this).apply {
            text = "Pair with hub"
            setOnClickListener { pairCamera() }
        }
        content.addView(pairButton)
        status = TextView(this).apply { text = "Not paired"; textSize = 16f }
        content.addView(status)
        content.addView(Button(this).apply {
            text = "Preview and adjust framing"
            setOnClickListener { openCameraPreview() }
        })
        content.addView(Button(this).apply {
            text = "Start camera"
            setOnClickListener { requestStreamPermissions() }
        })
        content.addView(Button(this).apply {
            text = "Stop camera"
            setOnClickListener { stopStream() }
        })
        content.addView(Button(this).apply {
            text = "Open visitor doorbell screen"
            setOnClickListener { startActivity(Intent(this@MainActivity, DoorbellActivity::class.java)) }
        })
        content.addView(Button(this).apply {
            text = "Switch front/back camera"
            setOnClickListener {
                startService(Intent(this@MainActivity, CameraStreamService::class.java)
                    .setAction(CameraStreamService.ACTION_SWITCH_CAMERA))
            }
        })
        content.addView(Button(this).apply {
            text = "Forget this camera"
            setOnClickListener { forgetCamera() }
        })
        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun pairCamera() {
        val hubUrl = hub.text.toString()
        val pairCode = code.text.toString()
        val cameraName = name.text.toString().ifBlank { "DoorCam" }
        if (hubUrl.isBlank() || pairCode.isBlank()) {
            status.text = "Enter the hub URL and one-time pairing code."
            return
        }
        pairButton.isEnabled = false
        status.text = "Pairing..."
        executor.execute {
            try {
                val config = PairingClient.exchange(hubUrl, pairCode, cameraName)
                    .copy(audioEnabled = audio.isChecked,
                        videoQuality = StreamQuality.entries[quality.selectedItemPosition].id)
                SecureConfigStore(this).save(config)
                runOnUiThread {
                    status.text = "Paired camera ${config.cameraId}"
                    code.setText("")
                    pairButton.isEnabled = true
                }
            } catch (error: Exception) {
                runOnUiThread {
                    status.text = "Pairing failed: ${error.message ?: "check hub URL and certificate"}"
                    pairButton.isEnabled = true
                }
            }
        }
    }

    private fun requestStreamPermissions() {
        val required = mutableListOf(Manifest.permission.CAMERA)
        if (audio.isChecked) required += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33) required += Manifest.permission.POST_NOTIFICATIONS
        val essential = required.filter { it != Manifest.permission.POST_NOTIFICATIONS }
        val missing = essential.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(required.toTypedArray(), 100)
            return
        }
        SecureConfigStore(this).load()?.let { saved ->
            SecureConfigStore(this).save(saved.copy(audioEnabled = audio.isChecked,
                videoQuality = StreamQuality.entries[quality.selectedItemPosition].id))
            ContextCompat.startForegroundService(this,
                Intent(this, CameraStreamService::class.java).setAction(CameraStreamService.ACTION_START))
            status.text = "Starting camera stream..."
        } ?: run { status.text = "Pair this phone with the hub first." }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>,
                                            grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED) openCameraPreview()
            else status.text = "Grant camera permission to preview and adjust the framing."
            return
        }
        if (requestCode != 100) return
        val cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        val audioGranted = !audio.isChecked || ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (cameraGranted && audioGranted) requestStreamPermissions()
        else status.text = "Grant camera and selected microphone permissions to stream."
    }

    private fun openCameraPreview() {
        if (SecureConfigStore(this).load() == null) {
            status.text = "Pair this phone with the hub before setting up its camera."
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 101)
            return
        }
        pendingPreview = true
        status.text = "Pausing the stream to open camera setup..."
        startService(Intent(this, CameraStreamService::class.java)
            .setAction(CameraStreamService.ACTION_PAUSE_FOR_PREVIEW))
    }

    private fun stopStream() {
        startService(Intent(this, CameraStreamService::class.java).setAction(CameraStreamService.ACTION_STOP))
    }

    private fun forgetCamera() {
        stopStream()
        SecureConfigStore(this).clear()
        code.setText("")
        status.text = "Camera credentials removed from this phone. Revoke the device in the hub too."
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        executor.shutdownNow()
        super.onDestroy()
    }
}

