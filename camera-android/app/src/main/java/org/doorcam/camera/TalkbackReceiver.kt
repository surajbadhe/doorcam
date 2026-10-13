package org.doorcam.camera

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Base64
import org.webrtc.CandidatePairChangeEvent
import org.webrtc.MediaStreamTrack
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Receives the viewer's push-to-talk audio over an authenticated WHEP session. */
class TalkbackReceiver(
    context: Context,
    private val hubUrl: String,
    private val cameraId: String,
    private val deviceToken: String,
    private val onStatus: (String) -> Unit = {},
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val worker = ScheduledThreadPoolExecutor(1)
    private val factory: PeerConnectionFactory
    private var peer: PeerConnection? = null
    private var sessionUrl: String? = null
    private var retrySeconds = 2L
    private var oldAudioMode = AudioManager.MODE_NORMAL
    private var oldSpeakerphone = false
    private var oldCommunicationDevice: AudioDeviceInfo? = null
    private var audioRouted = false

    init {
        worker.setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions()
        )
        factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
        worker.execute { connectIfNeeded() }
    }

    private fun endpoint(): String {
        val uri = URI(hubUrl)
        return URI(uri.scheme, null, uri.host, uri.port, "/talk/$cameraId/whep", null, null).toString()
    }

    private fun connectIfNeeded() {
        if (closed.get() || peer != null) return
        try {
            val configuration = PeerConnection.RTCConfiguration(emptyList())
            val observer = object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                    if (state == PeerConnection.IceConnectionState.CONNECTED ||
                        state == PeerConnection.IceConnectionState.COMPLETED) {
                        retrySeconds = 2
                        routeAudioToSpeaker()
                        onStatus("Talkback audio connected")
                    } else if (state == PeerConnection.IceConnectionState.FAILED ||
                        state == PeerConnection.IceConnectionState.DISCONNECTED) {
                        worker.execute { closePeer(); scheduleRetry() }
                    }
                }
                override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
                override fun onIceCandidate(candidate: org.webrtc.IceCandidate) = Unit
                override fun onIceCandidatesRemoved(candidates: Array<out org.webrtc.IceCandidate>) = Unit
                override fun onAddStream(stream: org.webrtc.MediaStream) = Unit
                override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit
                override fun onDataChannel(channel: org.webrtc.DataChannel) = Unit
                override fun onRenegotiationNeeded() = Unit
                override fun onAddTrack(receiver: org.webrtc.RtpReceiver, streams: Array<out org.webrtc.MediaStream>) {
                    (receiver.track() as? org.webrtc.AudioTrack)?.setEnabled(true)
                }
                override fun onTrack(transceiver: RtpTransceiver) {
                    (transceiver.receiver.track() as? org.webrtc.AudioTrack)?.setEnabled(true)
                }
                override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                    if (state == PeerConnection.PeerConnectionState.FAILED ||
                        state == PeerConnection.PeerConnectionState.DISCONNECTED) {
                        worker.execute { closePeer(); scheduleRetry() }
                    }
                }
                override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) = Unit
            }
            val current = factory.createPeerConnection(configuration, observer)
                ?: error("Could not create WebRTC connection")
            peer = current
            val transceiver = current.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
            ) ?: error("Could not request camera audio")
            transceiver.setDirection(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
            val offer = awaitSdp { current.createOffer(it, MediaConstraints()) }
            awaitSdp { current.setLocalDescription(it, offer) }
            waitForIce(current)
            if (closed.get() || peer !== current) return
            val response = exchange("POST", endpoint(), current.localDescription?.description ?: offer.description)
            if (response.code == 404) {
                closePeer()
                onStatus("Camera is live; waiting for a viewer to start a call")
                scheduleRetry()
                return
            }
            if (response.code !in 200..299) error("Talkback setup returned HTTP ${response.code}: ${response.body.take(160)}")
            sessionUrl = response.location?.let { URL(URL(endpoint()), it).toString() }
            val answer = SessionDescription(SessionDescription.Type.ANSWER, response.body)
            awaitSdp { current.setRemoteDescription(it, answer) }
            onStatus("Waiting for viewer talk audio")
        } catch (error: Exception) {
            closePeer()
            if (!closed.get()) {
                onStatus("Talkback waiting: ${error.message ?: "viewer not connected"}")
                scheduleRetry()
            }
        }
    }

    private fun awaitSdp(start: (SdpObserver) -> Unit): SessionDescription {
        val latch = CountDownLatch(1)
        var result: SessionDescription? = null
        var failure: String? = null
        start(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) { result = description; latch.countDown() }
            override fun onSetSuccess() { latch.countDown() }
            override fun onCreateFailure(error: String) { failure = error; latch.countDown() }
            override fun onSetFailure(error: String) { failure = error; latch.countDown() }
        })
        if (!latch.await(12, TimeUnit.SECONDS)) error("WebRTC negotiation timed out")
        failure?.let { error(it) }
        return result ?: SessionDescription(SessionDescription.Type.OFFER, "")
    }

    private fun waitForIce(current: PeerConnection) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!closed.get() && peer === current &&
            current.iceGatheringState() != PeerConnection.IceGatheringState.COMPLETE &&
            System.nanoTime() < deadline) Thread.sleep(100)
    }

    private data class Exchange(val code: Int, val body: String, val location: String?)

    private fun exchange(method: String, target: String, sdp: String? = null): Exchange {
        val connection = URL(target).openConnection() as HttpURLConnection
        try {
            val basic = Base64.encodeToString("$cameraId:$deviceToken".toByteArray(), Base64.NO_WRAP)
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Authorization", "Basic $basic")
            if (sdp != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/sdp")
                connection.outputStream.use { it.write(sdp.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return Exchange(code, body, connection.getHeaderField("Location"))
        } finally {
            connection.disconnect()
        }
    }

    private fun routeAudioToSpeaker() {
        if (audioRouted) return
        val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        oldAudioMode = audio.mode
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            oldCommunicationDevice = audio.communicationDevice
            val speaker = audio.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            }
            if (speaker != null) audio.setCommunicationDevice(speaker)
        } else {
            @Suppress("DEPRECATION")
            run { oldSpeakerphone = audio.isSpeakerphoneOn }
            @Suppress("DEPRECATION")
            run { audio.isSpeakerphoneOn = true }
        }
        audioRouted = true
    }

    private fun restoreAudioRoute() {
        if (!audioRouted) return
        val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val previous = oldCommunicationDevice
            if (previous != null && previous in audio.availableCommunicationDevices) {
                audio.setCommunicationDevice(previous)
            } else {
                audio.clearCommunicationDevice()
            }
            oldCommunicationDevice = null
        } else {
            @Suppress("DEPRECATION")
            run { audio.isSpeakerphoneOn = oldSpeakerphone }
        }
        audio.mode = oldAudioMode
        audioRouted = false
    }

    private fun closePeer() {
        val oldPeer = peer
        peer = null
        val oldSession = sessionUrl
        sessionUrl = null
        runCatching { oldPeer?.close() }
        runCatching { oldPeer?.dispose() }
        runCatching { if (oldSession != null) exchange("DELETE", oldSession) }
        runCatching { restoreAudioRoute() }
    }

    private fun scheduleRetry() {
        if (!closed.get()) {
            val delay = retrySeconds
            retrySeconds = (retrySeconds * 2).coerceAtMost(5)
            worker.schedule({ connectIfNeeded() }, delay, TimeUnit.SECONDS)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        worker.execute { closePeer(); factory.dispose() }
        worker.shutdown()
    }
}
