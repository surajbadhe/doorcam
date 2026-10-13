package org.doorcam.camera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.pedro.common.AudioCodec
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.audio.NoAudioSource
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.library.rtsp.RtspStream
import java.net.URI

class CameraStreamService : Service(), ConnectChecker {
    companion object {
        const val ACTION_START = "org.doorcam.camera.START"
        const val ACTION_STOP = "org.doorcam.camera.STOP"
        const val ACTION_SWITCH_CAMERA = "org.doorcam.camera.SWITCH_CAMERA"
        const val ACTION_PAUSE_FOR_PREVIEW = "org.doorcam.camera.PAUSE_FOR_PREVIEW"
        const val ACTION_STATUS = "org.doorcam.camera.STATUS"
        private const val CHANNEL = "doorcam_stream"
        private const val NOTIFICATION_ID = 47
    }

    private val handler = Handler(Looper.getMainLooper())
    private var stream: RtspStream? = null
    private var talkback: TalkbackReceiver? = null
    private var config: CameraConfig? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var active = false
    private var retryAttempt = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopStreaming()
            ACTION_PAUSE_FOR_PREVIEW -> {
                if (active) stopStreaming("Stopped for camera setup") else {
                    publishStatus("Stopped for camera setup")
                    stopSelf()
                }
            }
            ACTION_SWITCH_CAMERA -> switchCamera()
            ACTION_START -> {
                config = SecureConfigStore(this).load()
                if (config == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                active = true
                createNotificationChannel()
                startForegroundNow(notification("Starting camera stream"))
                acquireLocks()
                connect()
            }
        }
        return START_STICKY
    }

    private fun startForegroundNow(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (config?.audioEnabled == true) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(NOTIFICATION_ID, notification, type)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    private fun connect() {
        if (!active) return
        releaseStream()
        val camera = config ?: return
        try {
            val host = URI(camera.hubUrl).host ?: error("Hub URL has no hostname")
            val endpoint = "rtsp://${camera.cameraId}:${camera.deviceToken}@$host:8554/cam/${camera.cameraId}"
            // VOICE_COMMUNICATION asks Android to use the device's VoIP capture
            // path (including platform AEC/AGC when available). This matters
            // while TalkbackReceiver plays the far end through the loudspeaker.
            val audio = if (camera.audioEnabled) MicrophoneSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                else NoAudioSource()
            val videoSource = Camera2Source(this).apply {
                if (camera.cameraFacing == "FRONT") switchCamera()
            }
            val quality = StreamQuality.fromId(camera.videoQuality)
            stream = RtspStream(this, this, videoSource, audio).apply {
                setVideoCodec(VideoCodec.H264)
                if (camera.audioEnabled) setAudioCodec(AudioCodec.OPUS)
            }
            val ready = stream!!.prepareVideo(quality.width, quality.height, quality.bitrate, quality.fps,
                rotation = 90) &&
                (!camera.audioEnabled || stream!!.prepareAudio(48_000, false, 64_000, true, true))
            if (!ready) {
                publishStatus("Could not prepare ${quality.id} video/audio. Try 720p or 480p.")
                stopStreaming()
                return
            }
            stream!!.startStream(endpoint)
            applySavedZoom(videoSource, camera.cameraZoom)
            publishStatus("Connecting to hub")
            updateNotification("Connecting to hub")
        } catch (error: Exception) {
            publishStatus("Camera start failed: ${error.message}")
            scheduleReconnect()
        }
    }

    private fun applySavedZoom(source: Camera2Source, requestedZoom: Float, attempt: Int = 0) {
        if (!active || attempt >= 20) return
        if (!source.isRunning()) {
            handler.postDelayed({ applySavedZoom(source, requestedZoom, attempt + 1) }, 250)
            return
        }
        val range = source.getZoomRange()
        source.setZoom(requestedZoom.coerceIn(range.lower, range.upper))
    }

    private fun switchCamera() {
        val saved = SecureConfigStore(this).load()
        if (saved == null) {
            publishStatus("Pair this phone with the hub before selecting a camera.")
            stopSelf()
            return
        }
        try {
            val source = stream?.videoSource as? Camera2Source
            val facing = if (source != null) {
                source.switchCamera()
                source.getCameraFacing().name
            } else if (saved.cameraFacing == "FRONT") "BACK" else "FRONT"
            config = saved.copy(cameraFacing = facing)
            SecureConfigStore(this).save(config!!)
            val label = if (facing == "FRONT") "front" else "back"
            publishStatus(if (active) "Switched to $label camera" else "$label camera selected for next start")
            if (!active) stopSelf()
        } catch (error: Exception) {
            publishStatus("Could not switch camera: ${error.message ?: "camera unavailable"}")
        }
    }

    private fun scheduleReconnect() {
        if (!active) return
        val delay = ReconnectPolicy.delayMillis(retryAttempt)
        retryAttempt = ReconnectPolicy.nextAttempt(retryAttempt)
        publishStatus("Reconnecting in ${delay / 1000}s")
        updateNotification("Reconnecting in ${delay / 1000}s")
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ connect() }, delay)
    }

    private fun releaseStream() {
        stream?.let { runCatching { it.release() } }
        stream = null
    }

    private fun releaseTalkback() {
        talkback?.close()
        talkback = null
    }

    private fun stopStreaming(status: String = "Stopped") {
        active = false
        handler.removeCallbacksAndMessages(null)
        releaseStream()
        releaseTalkback()
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        publishStatus(status)
        stopSelf()
    }

    private fun acquireLocks() {
        val power = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DoorCam:CameraStream").also {
            it.setReferenceCounted(false)
            if (!it.isHeld) it.acquire()
        }
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DoorCam:WifiStream").also {
            it.setReferenceCounted(false)
            if (!it.isHeld) it.acquire()
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "DoorCam camera stream", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("DoorCam camera").setContentText(text)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun publishStatus(text: String) {
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra("status", text))
    }

    override fun onConnectionStarted(url: String) = publishStatus("Connecting")
    override fun onConnectionSuccess() {
        retryAttempt = ReconnectPolicy.reset()
        publishStatus("Live on hub")
        updateNotification("Live on hub")
        val camera = config ?: return
        if (talkback == null) {
            talkback = TalkbackReceiver(this, camera.hubUrl, camera.cameraId, camera.deviceToken) { status ->
                if (active) publishStatus(status)
            }
        }
    }
    override fun onConnectionFailed(reason: String) {
        publishStatus("Stream error: $reason")
        releaseStream()
        scheduleReconnect()
    }
    override fun onNewBitrate(bitrate: Long) = Unit
    override fun onDisconnect() {
        if (active) scheduleReconnect()
    }
    override fun onAuthError() {
        publishStatus("Hub rejected this device token")
        releaseStream()
        scheduleReconnect()
    }
    override fun onAuthSuccess() = Unit

    override fun onDestroy() {
        active = false
        handler.removeCallbacksAndMessages(null)
        releaseStream()
        releaseTalkback()
        releaseLocks()
        super.onDestroy()
    }
}

