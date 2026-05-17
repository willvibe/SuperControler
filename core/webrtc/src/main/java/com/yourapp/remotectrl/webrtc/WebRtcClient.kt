package com.yourapp.remotectrl.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.Log
import org.webrtc.*
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class WebRtcClient(
    private val context: Context,
    private val signalingListener: SignalingListener
) {

    interface SignalingListener {
        fun sendSdp(targetId: String, type: String, sdp: String)
        fun sendIceCandidate(targetId: String, sdpMid: String, sdpMLineIndex: Int, candidate: String)
    }

    interface EventListener {
        fun onControlEvent(eventJson: String)
        fun onRemoteVideoTrack(videoTrack: VideoTrack)
        fun onConnectionStateChange(connected: Boolean)
        fun onDataChannelMessage(message: String) {}
    }

    companion object {
        private const val TAG = "WebRtcClient"
        private const val VIDEO_TRACK_ID = "ScreenCapture"
        private const val DATA_CHANNEL_LABEL = "ControlChannel"
    }

    var eventListener: EventListener? = null

    private val eglBase: EglBase = EglBase.create()
    val eglBaseContext: EglBase.Context get() = eglBase.eglBaseContext

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoCapturer: ScreenCapturerAndroid? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var controlDataChannel: DataChannel? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    private var isInitiator = false
    private var _peerId = ""
    private var isDisposed = false

    private var pendingMediaProjectionIntent: Intent? = null
    private var pendingCaptureWidth = 1280
    private var pendingCaptureHeight = 720
    private var pendingCaptureFps = 30

    private var hasRemoteOffer = false
    var isScreenCaptureReady = false
        private set
    private var isLocalDescriptionSet = false

    private val queuedRemoteIceCandidates = CopyOnWriteArrayList<IceCandidate>()
    private val iceQueueLock = ReentrantLock()
    @Volatile private var isRemoteSdpSet = false

    private val videoTrackReported = AtomicBoolean(false)

    var peerId: String
        get() = _peerId
        set(value) {
            if (_peerId.isEmpty()) {
                _peerId = value
                Log.i(TAG, "peerId set to: $value")
                flushQueuedIceCandidates()
            }
        }

    private fun flushQueuedIceCandidates() {
        if (_peerId.isEmpty()) return
        val pc = peerConnection ?: return
        iceQueueLock.withLock {
            val candidates = queuedRemoteIceCandidates.toList()
            queuedRemoteIceCandidates.clear()
            candidates.forEach { candidate ->
                Log.d(TAG, "Flushing queued ICE candidate: ${candidate.sdpMid}")
                try { pc.addIceCandidate(candidate) } catch (_: Exception) {}
            }
        }
    }

    fun initialize() {
        if (isDisposed) return
        Log.i(TAG, "initialize()")

        val initOptions = PeerConnectionFactory.InitializationOptions
            .builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)

        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        Log.i(TAG, "PeerConnectionFactory created")
    }

    fun setupAsControlled(mediaProjectionIntent: Intent, width: Int, height: Int, fps: Int) {
        if (isDisposed) return
        Log.i(TAG, "setupAsControlled() ${width}x${height}@${fps}fps")

        isInitiator = false
        pendingMediaProjectionIntent = mediaProjectionIntent
        pendingCaptureWidth = width
        pendingCaptureHeight = height
        pendingCaptureFps = fps

        createPeerConnection()

        val factory = peerConnectionFactory ?: return
        videoSource = factory.createVideoSource(true)
        localVideoTrack = factory.createVideoTrack(VIDEO_TRACK_ID, videoSource)
        localVideoTrack?.setEnabled(true)
        peerConnection?.addTrack(localVideoTrack)
        Log.i(TAG, "VideoTrack created and added to PeerConnection (before any SDP negotiation)")
    }

    fun setupAsController(targetId: String) {
        if (isDisposed) return
        Log.i(TAG, "setupAsController() targetId=$targetId")

        isInitiator = true
        _peerId = targetId
        createPeerConnection()

        peerConnection?.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
        )
        Log.i(TAG, "Video RECV_ONLY transceiver added")

        createControlDataChannel()
        createOffer()
    }

    fun startScreenCapture(intent: Intent, width: Int, height: Int, fps: Int) {
        if (isDisposed) return
        val factory = peerConnectionFactory ?: return

        Log.i(TAG, "startScreenCapture() ${width}x${height}@${fps}fps")

        videoCapturer = ScreenCapturerAndroid(intent, object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "MediaProjection stopped")
                isScreenCaptureReady = false
            }
        })

        val sth = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        this.surfaceTextureHelper = sth
        videoCapturer!!.initialize(sth, context, videoSource!!.capturerObserver)
        videoCapturer!!.startCapture(width, height, fps)

        isScreenCaptureReady = true
        Log.i(TAG, "Screen capture started and connected to existing VideoSource")

        if (hasRemoteOffer) {
            Log.i(TAG, "Remote offer already received, creating answer now")
            createAnswer()
        }
    }

    private fun createPeerConnection() {
        if (isDisposed) return
        val factory = peerConnectionFactory ?: return

        val iceServers = IceConfig.buildIceServers()
        Log.i(TAG, "Creating PeerConnection with ${iceServers.size} ICE servers")

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceTransportsType = PeerConnection.IceTransportsType.ALL
        }

        peerConnection = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                Log.d(TAG, "onSignalingChange: $state")
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.i(TAG, "onIceConnectionChange: $state")
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED -> {
                        eventListener?.onConnectionStateChange(true)
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED,
                    PeerConnection.IceConnectionState.FAILED,
                    PeerConnection.IceConnectionState.CLOSED -> {
                        eventListener?.onConnectionStateChange(false)
                    }
                    else -> {}
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                Log.d(TAG, "onIceGatheringChange: $state")
            }

            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate == null) return
                val currentPeerId = _peerId
                if (currentPeerId.isNotEmpty()) {
                    Log.d(TAG, "onIceCandidate: ${candidate.sdpMid}")
                    signalingListener.sendIceCandidate(
                        currentPeerId,
                        candidate.sdpMid,
                        candidate.sdpMLineIndex,
                        candidate.sdp
                    )
                } else {
                    Log.w(TAG, "onIceCandidate: peerId is empty, cannot send ICE candidate yet")
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

            override fun onAddStream(stream: MediaStream?) {
                Log.d(TAG, "onAddStream: ${stream?.videoTracks?.size} video tracks")
            }

            override fun onRemoveStream(stream: MediaStream?) {}

            override fun onDataChannel(channel: DataChannel?) {
                Log.i(TAG, "onDataChannel: ${channel?.label()}")
                if (channel?.label() == DATA_CHANNEL_LABEL) {
                    controlDataChannel = channel
                    setupDataChannelObserver(channel)
                }
            }

            override fun onRenegotiationNeeded() {
                Log.d(TAG, "onRenegotiationNeeded")
            }

            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                Log.d(TAG, "onAddTrack: receiver=${receiver}, track=${receiver?.track()}")
                receiver?.track()?.let { track ->
                    Log.i(TAG, "onAddTrack: track kind=${track.kind()}, id=${track.id()}")
                    if (track is VideoTrack && !videoTrackReported.getAndSet(true)) {
                        Log.i(TAG, "Remote VideoTrack received, enabled=${track.enabled()}")
                        track.setEnabled(true)
                        eventListener?.onRemoteVideoTrack(track)
                    }
                }
            }
        })

        Log.i(TAG, "PeerConnection created")
    }

    private fun createControlDataChannel() {
        if (isDisposed) return
        val pc = peerConnection ?: return

        val config = DataChannel.Init().apply {
            ordered = true
        }
        controlDataChannel = pc.createDataChannel(DATA_CHANNEL_LABEL, config)
        setupDataChannelObserver(controlDataChannel!!)
        Log.i(TAG, "Control DataChannel created")
    }

    private fun setupDataChannelObserver(channel: DataChannel) {
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}

            override fun onStateChange() {
                Log.i(TAG, "DataChannel state: ${channel.state()}")
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                try {
                    val data = ByteArray(buffer.data.remaining())
                    buffer.data.get(data)
                    val message = String(data, Charsets.UTF_8)
                    Log.d(TAG, "DataChannel message: ${message.take(100)}")
                    try {
                        val json = org.json.JSONObject(message)
                        val msgType = json.optString("type", "")
                        if (msgType == "SCREEN_INFO" || msgType == "SCREEN_INFO_ACK") {
                            eventListener?.onDataChannelMessage(message)
                        } else {
                            eventListener?.onControlEvent(message)
                        }
                    } catch (_: Exception) {
                        eventListener?.onControlEvent(message)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "DataChannel onMessage error: ${e.message}")
                }
            }
        })
    }

    fun sendControlEvent(eventJson: String) {
        sendDataChannelMessage(eventJson)
    }

    fun sendDataChannelMessage(message: String) {
        if (isDisposed) return
        val channel = controlDataChannel ?: return
        if (channel.state() != DataChannel.State.OPEN) {
            Log.w(TAG, "DataChannel not open, state=${channel.state()}")
            return
        }
        try {
            val data = message.toByteArray(Charsets.UTF_8)
            val buffer = DataChannel.Buffer(ByteBuffer.wrap(data), false)
            val sent = channel.send(buffer)
            if (!sent) {
                Log.w(TAG, "DataChannel send returned false")
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendDataChannelMessage error: ${e.message}")
        }
    }

    private fun createOffer() {
        if (isDisposed) return
        val pc = peerConnection ?: return

        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }

        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp == null || isDisposed) return
                Log.i(TAG, "Offer created, setting local description")
                pc.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(sdp: SessionDescription?) {}
                    override fun onCreateFailure(error: String?) {}
                    override fun onSetSuccess() {
                        Log.i(TAG, "Local description set (offer), sending to peer")
                        isLocalDescriptionSet = true
                        signalingListener.sendSdp(_peerId, sdp.type.canonicalForm(), sdp.description)
                    }
                    override fun onSetFailure(error: String?) {
                        Log.e(TAG, "setLocalDescription(offer) failed: $error")
                    }
                }, sdp)
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "createOffer failed: $error")
            }
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    private fun createAnswer() {
        if (isDisposed) return
        val pc = peerConnection ?: return

        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }

        pc.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp == null || isDisposed) return
                Log.i(TAG, "Answer created, setting local description")
                pc.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(sdp: SessionDescription?) {}
                    override fun onCreateFailure(error: String?) {}
                    override fun onSetSuccess() {
                        Log.i(TAG, "Local description set (answer), sending to peer")
                        isLocalDescriptionSet = true
                        signalingListener.sendSdp(_peerId, sdp.type.canonicalForm(), sdp.description)
                    }
                    override fun onSetFailure(error: String?) {
                        Log.e(TAG, "setLocalDescription(answer) failed: $error")
                    }
                }, sdp)
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "createAnswer failed: $error")
            }
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    fun onRemoteSdp(type: String, sdp: String) {
        if (isDisposed) return
        val pc = peerConnection ?: return

        val sdpType = when (type) {
            "offer" -> SessionDescription.Type.OFFER
            "answer" -> SessionDescription.Type.ANSWER
            else -> {
                Log.w(TAG, "Unknown SDP type: $type")
                return
            }
        }

        val sessionDescription = SessionDescription(sdpType, sdp)
        pc.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {}
            override fun onCreateFailure(error: String?) {}
            override fun onSetSuccess() {
                Log.i(TAG, "Remote SDP set successfully (type=$type)")
                iceQueueLock.withLock {
                    isRemoteSdpSet = true
                    val pending = queuedRemoteIceCandidates.toList()
                    queuedRemoteIceCandidates.clear()
                    pending.forEach { candidate ->
                        try { pc.addIceCandidate(candidate) } catch (_: Exception) {}
                    }
                }
                if (sdpType == SessionDescription.Type.OFFER) {
                    hasRemoteOffer = true
                    if (isScreenCaptureReady) {
                        createAnswer()
                    } else {
                        Log.i(TAG, "Screen capture not ready yet, will create answer when ready")
                    }
                }
            }
            override fun onSetFailure(error: String?) {
                Log.e(TAG, "setRemoteDescription failed: $error")
            }
        }, sessionDescription)
    }

    fun onRemoteIceCandidate(sdpMid: String, sdpMLineIndex: Int, sdp: String) {
        if (isDisposed) return
        val pc = peerConnection ?: return
        val candidate = IceCandidate(sdpMid, sdpMLineIndex, sdp)

        iceQueueLock.withLock {
            if (isRemoteSdpSet) {
                try { pc.addIceCandidate(candidate) } catch (_: Exception) {}
            } else {
                Log.d(TAG, "Queueing ICE candidate (remote SDP not set yet)")
                queuedRemoteIceCandidates.add(candidate)
            }
        }
    }

    fun dispose() {
        if (isDisposed) return
        isDisposed = true
        Log.i(TAG, "dispose()")

        try {
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
        } catch (e: Exception) {
            Log.w(TAG, "stopCapture error: ${e.message}")
        }
        videoCapturer = null

        try {
            surfaceTextureHelper?.dispose()
        } catch (_: Exception) {}
        surfaceTextureHelper = null

        try {
            videoSource?.dispose()
        } catch (_: Exception) {}
        videoSource = null
        localVideoTrack = null

        try {
            controlDataChannel?.close()
            controlDataChannel?.dispose()
        } catch (_: Exception) {}
        controlDataChannel = null

        try {
            peerConnection?.close()
        } catch (_: Exception) {}
        peerConnection = null

        try {
            peerConnectionFactory?.dispose()
        } catch (_: Exception) {}
        peerConnectionFactory = null

        queuedRemoteIceCandidates.clear()

        try {
            eglBase.release()
        } catch (_: Exception) {}

        isScreenCaptureReady = false
        hasRemoteOffer = false
        isLocalDescriptionSet = false
        isRemoteSdpSet = false
        videoTrackReported.set(false)
    }
}
