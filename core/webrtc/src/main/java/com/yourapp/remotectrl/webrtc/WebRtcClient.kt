package com.yourapp.remotectrl.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
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

        // 【修复1】确保 Native 库只初始化一次
        @Volatile
        private var isFactoryInitialized = false

        // 【修复2】全局共享 EGL 上下文，防止 SurfaceViewRenderer 拿到死锁的上下文
        @Volatile
        private var sharedEglBase: EglBase? = null

        fun getSharedEglBase(context: Context): EglBase {
            if (!isFactoryInitialized) {
                val initOptions = PeerConnectionFactory.InitializationOptions
                    .builder(context.applicationContext)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
                PeerConnectionFactory.initialize(initOptions)
                isFactoryInitialized = true
            }
            if (sharedEglBase == null) {
                sharedEglBase = EglBase.create()
            }
            return sharedEglBase!!
        }
    }

    var eventListener: EventListener? = null

    // 【修复3】移除立即初始化的 val，改为可空的安全类型
    private var eglBase: EglBase? = null
    val eglBaseContext: EglBase.Context? get() = eglBase?.eglBaseContext

    // 【修复10】主线程 Handler，用于将 WebRTC 内部线程回调切到主线程
    private val mainHandler = Handler(Looper.getMainLooper())

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

    // 【修复3】本地 ICE 候选者队列，防止 peerId 未设置时丢失
    private val queuedLocalIceCandidates = CopyOnWriteArrayList<IceCandidate>()

    private val videoTrackReported = AtomicBoolean(false)

    var peerId: String
        get() = _peerId
        set(value) {
            if (_peerId.isEmpty()) {
                _peerId = value
                Log.i(TAG, "peerId set to: $value")
                flushQueuedIceCandidates()
                // 【修复3】立刻发送积压的本地 ICE
                queuedLocalIceCandidates.forEach { candidate ->
                    signalingListener.sendIceCandidate(value, candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
                }
                queuedLocalIceCandidates.clear()
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

        // 【修复4】严格保证顺序：先 init Native 库，再获取 EglBase
        eglBase = getSharedEglBase(context)

        val encoderFactory = DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)

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

        Log.i(TAG, "VideoTrack ready, waiting for remote offer to bind")
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
        if (peerConnectionFactory == null) return

        Log.i(TAG, "startScreenCapture() ${width}x${height}@${fps}fps")

        videoCapturer = ScreenCapturerAndroid(intent, object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "MediaProjection stopped")
                isScreenCaptureReady = false
            }
        })

        val eglCtx = eglBase?.eglBaseContext ?: return
        val sth = SurfaceTextureHelper.create("CaptureThread", eglCtx)
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
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            candidateNetworkPolicy = PeerConnection.CandidateNetworkPolicy.ALL
        }

        peerConnection = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                Log.d(TAG, "onSignalingChange: $state")
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.i(TAG, "onIceConnectionChange: $state")
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED -> {
                        // 【修复10】切到主线程回调
                        mainHandler.post { eventListener?.onConnectionStateChange(true) }
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED,
                    PeerConnection.IceConnectionState.FAILED,
                    PeerConnection.IceConnectionState.CLOSED -> {
                        mainHandler.post { eventListener?.onConnectionStateChange(false) }
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
                    // 【修复3】peerId 为空时加入队列而不是丢弃
                    Log.w(TAG, "onIceCandidate: peerId is empty, queuing local candidate")
                    queuedLocalIceCandidates.add(candidate)
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
                        // 【修复10】将轨道交付抛给主线程，确保 UI 安全绑定
                        mainHandler.post { eventListener?.onRemoteVideoTrack(track) }
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

        // 【修复11】安全判空处理，移除暴力解包 !!
        controlDataChannel?.let { channel ->
            setupDataChannelObserver(channel)
            Log.i(TAG, "Control DataChannel created")
        } ?: Log.e(TAG, "Failed to create Control DataChannel!")
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

    fun disconnectPeer() {
        if (isDisposed) return
        Log.i(TAG, "disconnectPeer() - sending disconnect message to peer")
        try {
            val disconnectMsg = JSONObject().apply {
                put("type", "DISCONNECT")
            }.toString()
            sendDataChannelMessage(disconnectMsg)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send disconnect message: ${e.message}")
        }
        try {
            peerConnection?.close()
        } catch (_: Exception) {}
    }

    private fun createOffer() {
        if (isDisposed) return
        val pc = peerConnection ?: return

        val constraints = MediaConstraints()

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

        val constraints = MediaConstraints()

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

                    val track = localVideoTrack
                    if (track != null && !isInitiator) {
                        for (transceiver in pc.transceivers) {
                            if (transceiver.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO) {
                                transceiver.sender.setTrack(track, true)
                                transceiver.direction = RtpTransceiver.RtpTransceiverDirection.SEND_ONLY
                                Log.i(TAG, "Successfully bound local screen track to remote offer transceiver!")
                                break
                            }
                        }
                    }

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

        try {
            localVideoTrack?.dispose()
        } catch (_: Exception) {}
        localVideoTrack = null

        try {
            controlDataChannel?.close()
            controlDataChannel?.dispose()
        } catch (_: Exception) {}
        controlDataChannel = null

        try {
            peerConnection?.close()
        } catch (_: Exception) {}
        try {
            peerConnection?.dispose()
        } catch (_: Exception) {}
        peerConnection = null

        try {
            peerConnectionFactory?.dispose()
        } catch (_: Exception) {}
        peerConnectionFactory = null

        queuedRemoteIceCandidates.clear()
        queuedLocalIceCandidates.clear()

        // ❌ 【修复2】必须删除这段代码！绝对不能释放全局共享的 EGL Context，否则 UI 渲染器必崩
        // try {
        //     eglBase?.release()
        // } catch (_: Exception) {}

        isScreenCaptureReady = false
        hasRemoteOffer = false
        isLocalDescriptionSet = false
        isRemoteSdpSet = false
        videoTrackReported.set(false)
    }
}
