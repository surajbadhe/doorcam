package org.doorcam.camera

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import java.util.concurrent.Executors

class DoorbellActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var ringButton: Button
    private lateinit var status: TextView
    private var lastRingAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(28, 40, 28, 40)
            setBackgroundColor(0xFF101820.toInt())
        }
        content.addView(TextView(this).apply {
            text = "Welcome"
            textSize = 32f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(TextView(this).apply {
            text = "Press the button below to let the homeowner know you are here."
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(0xFFD5E0E7.toInt())
            setPadding(8, 24, 8, 32)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        ringButton = Button(this).apply {
            text = "RING DOORBELL"
            textSize = 22f
            isAllCaps = false
            setOnClickListener { ring() }
        }
        val buttonHeight = (112 * resources.displayMetrics.density).toInt()
        content.addView(ringButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, buttonHeight,
        ))
        status = TextView(this).apply {
            text = "Ready"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(8, 28, 8, 8)
        }
        content.addView(status, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        setContentView(content)
        if (SecureConfigStore(this).load() == null) {
            ringButton.isEnabled = false
            status.text = "This phone needs to be paired with the DoorCam hub first."
        }
    }

    private fun ring() {
        val config = SecureConfigStore(this).load()
        if (config == null) {
            status.text = "This phone is not paired with a hub."
            ringButton.isEnabled = false
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastRingAt < 20_000) {
            status.text = "The bell was just rung. Please wait before ringing again."
            return
        }
        lastRingAt = now
        ringButton.isEnabled = false
        status.text = "Ringing…"
        executor.execute {
            val result = runCatching { DoorbellClient.ring(config) }
            runOnUiThread {
                if (result.isSuccess) {
                    status.text = "The homeowner has been notified. Please wait here."
                } else {
                    status.text = "Could not ring: ${result.exceptionOrNull()?.message ?: "check the hub connection"}"
                    lastRingAt = 0L
                }
                ringButton.isEnabled = true
            }
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
