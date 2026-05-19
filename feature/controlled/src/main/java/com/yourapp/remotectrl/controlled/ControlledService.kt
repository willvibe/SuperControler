package com.yourapp.remotectrl.controlled

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.yourapp.remotectrl.network.ConnectionState
import com.yourapp.remotectrl.network.ServerConfig
import com.yourapp.remotectrl.network.SignalingClient
import com.yourapp.remotectrl.root.DeviceIdManager
import com.yourapp.remotectrl.root.InputInjector
import com.yourapp.remotectrl.root.RootManager
import com.yourapp.remotectrl.root.ScreenCaptureManager
import com.yourapp.remotectrl.webrtc.IceConfig
import com.yourapp.remotectrl.webrtc.WebRtcClient
import kotlinx.coroutines.*
import org.json.JSONObject

class ControlledService : Service() {

    companion object {
        const val TAG = "ControlledService"
        const val NOTIFICATION_ID = 2001
        const val CHANNEL_ID = "sync_channel"
        const val WATCHDOG_NOTIFICATION_ID = 2002

        @Volatile
        var userStopped = false

        @Volatile
        private var instance: ControlledService? = null

        fun getInstance(): ControlledService? = instance

        fun isRunning(): Boolean = instance != null

        fun start(context: Context) {
            Log.i(TAG, "start() called, current instance=${instance != null}")
            userStopped = false
            val intent = Intent(context, ControlledService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            userStopped = true
            context.stopService(Intent(context, ControlledService::class.java))
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var captureManager: ScreenCaptureManager
    @Volatile
    private var injector: InputInjector? = null
    private lateinit var signalingClient: SignalingClient
    private var webRtcClient: WebRtcClient? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDpi = 0
    private var encWidth = 0
    private var encHeight = 0
    private var videoCaptureStarted = false

    @Volatile
    private var projectionRequestInProgress = false

    private var wakeLock: PowerManager.WakeLock? = null
    private var watchdogJob: Job? = null
    private var notificationUpdateHandler: Handler? = null
    private var notificationUpdateRunnable: Runnable? = null

    private var pendingProjectionResultCode: Int? = null
    private var pendingProjectionData: Intent? = null
    private var isInitialized = false

    private var pendingSdpType: String? = null
    private var pendingSdpContent: String? = null
    private var pendingSdpFromId: String? = null

    private data class PendingIceCandidate(val sdpMid: String, val sdpMLineIndex: Int, val candidate: String)
    private val pendingIceCandidates = mutableListOf<PendingIceCandidate>()

    private var pendingMediaProjectionIntent: Intent? = null
    private var pendingEncW = 0
    private var pendingEncH = 0

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var networkJob: Job? = null

    private var reconnectFailCount = 0

    private var pendingScrollDx = 0
    private var pendingScrollDy = 0
    private var scrollStartX = 0
    private var scrollStartY = 0
    private var isBatchingScroll = false

    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                Log.i(TAG, "Network available: $network")
                networkJob?.cancel()
                networkJob = serviceScope.launch {
                    delay(2000)
                    Log.i(TAG, "Network stabilized: $network, reconnecting...")
                    signalingClient.notifyNetworkChanged()
                    checkAndRecover()
                }
            }

            override fun onLost(network: android.net.Network) {
                Log.w(TAG, "Network lost: $network")
            }
        }
        cm.registerDefaultNetworkCallback(networkCallback!!)
        Log.i(TAG, "NetworkCallback registered")
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(it)
        }
        networkCallback = null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "=== onCreate() START, instance set ===")

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification("正在启动..."),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForeground(NOTIFICATION_ID, buildNotification("正在启动..."))
            }
            Log.i(TAG, "startForeground() called with SPECIAL_USE")
        } catch (e: Exception) {
            Log.e(TAG, "startForeground() failed: ${e.javaClass.simpleName} - ${e.message}")
        }

        acquireWakeLock()
        Log.i(TAG, "WakeLock acquired")

        captureManager = ScreenCaptureManager(this)

        if (RootManager.isRootAvailable()) {
            injector = InputInjector(this)
            Log.i(TAG, "InputInjector created (root available)")
        } else {
            Log.w(TAG, "Root not available, will request root when needed")
            RootManager.onRootGranted = {
                Log.i(TAG, "Root granted callback received, creating InputInjector")
                if (injector == null) {
                    injector = InputInjector(this)
                    Log.i(TAG, "InputInjector created lazily after root granted")
                }
            }
        }
        Log.i(TAG, "Managers initialized")

        val (w, h, dpi) = captureManager.getDisplayInfo()
        screenWidth = w
        screenHeight = h
        screenDpi = dpi
        Log.i(TAG, "Display info: ${w}x${h} @ ${dpi}dpi")

        val serverUrl = ServerConfig.getServerUrl(this)
        Log.i(TAG, "Server URL: $serverUrl")

        signalingClient = SignalingClient(serverUrl)
        setupSignalingCallbacks()
        Log.i(TAG, "SignalingClient created, connecting...")

        val deviceId = DeviceIdManager.getDeviceId(this)
        Log.i(TAG, "Device ID: $deviceId")

        serviceScope.launch { signalingClient.connect(deviceId) }
        Log.i(TAG, "connect() launched")

        startWatchdog()
        startNotificationUpdater()
        Log.i(TAG, "Watchdog and NotificationUpdater started")

        registerNetworkReceiver()
        Log.i(TAG, "Network receiver registered")

        if (RootManager.isRootAvailable()) {
            Log.i(TAG, "Root already available, requesting MediaProjection immediately")
            injector = InputInjector(this)
            requestMediaProjection()
        } else if (RootManager.isRequestInProgress()) {
            Log.i(TAG, "Root request already in progress, waiting for result...")
            RootManager.requestRoot(this) { hasRoot ->
                Log.i(TAG, "Root request result: $hasRoot")
                if (hasRoot && injector == null) {
                    injector = InputInjector(this)
                }
                requestMediaProjection()
            }
        } else {
            Log.i(TAG, "Root not available, requesting root first...")
            RootManager.onRootGranted = {
                Log.i(TAG, "Root granted callback received, creating InputInjector")
                if (injector == null) {
                    injector = InputInjector(this)
                    Log.i(TAG, "InputInjector created lazily after root granted")
                }
            }
            RootManager.requestRoot(this) { hasRoot ->
                Log.i(TAG, "Root request result: $hasRoot")
                requestMediaProjection()
            }
        }

        isInitialized = true
        if (pendingProjectionResultCode != null && pendingProjectionData != null) {
            Log.i(TAG, "Processing pending MediaProjection result")
            setMediaProjectionResult(pendingProjectionResultCode!!, pendingProjectionData!!)
            pendingProjectionResultCode = null
            pendingProjectionData = null
        }

        Log.i(TAG, "=== onCreate() END ===")
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SuperControler::ServiceWakeLock").apply {
            acquire()
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null
        } catch (_: Exception) {}
    }

    private fun registerNetworkReceiver() {
        registerNetworkCallback()
    }

    private fun unregisterNetworkReceiver() {
        unregisterNetworkCallback()
    }

    private fun startWatchdog() {
        watchdogJob = serviceScope.launch {
            while (isActive) {
                delay(30_000)
                checkAndRecover()
            }
        }
    }

    private suspend fun checkAndRecover() {
        Log.w(TAG, "Watchdog check...")
        val state = ConnectionState.getStatusForOwner("controlled")
        Log.w(TAG, "Watchdog: current state = $state, videoCaptureStarted=$videoCaptureStarted, webRtc=${webRtcClient != null}")

        if (!videoCaptureStarted && webRtcClient == null && !projectionRequestInProgress && !RootManager.isRequestInProgress()) {
            Log.e(TAG, "Watchdog: Video capture NOT started, attempting to restart...")
            serviceScope.launch {
                delay(1000)
                requestMediaProjection()
            }
        }

        if (state == ConnectionState.STATUS_IDLE || state == ConnectionState.STATUS_ERROR) {
            reconnectFailCount++
            Log.w(TAG, "Watchdog: Connection lost (failCount=$reconnectFailCount)")
            if (reconnectFailCount >= 3) {
                Log.w(TAG, "Watchdog: Too many failures, recreating SignalingClient")
                reconnectFailCount = 0
                updateNotification("正在重连...")
                try {
                    signalingClient.disconnect()
                } catch (e: Exception) {
                    Log.w(TAG, "Watchdog: disconnect error: ${e.message}")
                }
                delay(2000)
                signalingClient = SignalingClient(ServerConfig.getServerUrl(this@ControlledService))
                setupSignalingCallbacks()
                try {
                    signalingClient.connect(DeviceIdManager.getDeviceId(this@ControlledService))
                    Log.i(TAG, "Watchdog: reconnect initiated")
                } catch (e: Exception) {
                    Log.e(TAG, "Watchdog: reconnect failed: ${e.message}")
                }
            }
        } else if (state == ConnectionState.STATUS_REGISTERED || state == ConnectionState.STATUS_CONNECTED) {
            reconnectFailCount = 0
        }
    }

    private fun startNotificationUpdater() {
        Log.i(TAG, "startNotificationUpdater()")
        updateNotification(getInitialMessage())
        notificationUpdateHandler = Handler(Looper.getMainLooper())
        notificationUpdateRunnable = object : Runnable {
            private var seconds = 0
            override fun run() {
                val state = ConnectionState.getStatusForOwner("controlled")
                if (state == ConnectionState.STATUS_REGISTERED || state == ConnectionState.STATUS_CONNECTED) {
                    updateNotification(getStatusMessage(0))
                    notificationUpdateHandler?.postDelayed(this, 30_000)
                    return
                }
                seconds++
                val msg = getStatusMessage(seconds)
                updateNotification(msg)
                notificationUpdateHandler?.postDelayed(this, 1000)
            }
        }
        notificationUpdateHandler?.post(notificationUpdateRunnable!!)
    }

    private fun getInitialMessage(): String {
        val state = ConnectionState.getStatusForOwner("controlled")
        Log.i(TAG, "getInitialMessage() state = $state")
        return when (state) {
            ConnectionState.STATUS_CONNECTING -> "连接中..."
            ConnectionState.STATUS_REGISTERED -> "已注册，等待连接"
            ConnectionState.STATUS_CONNECTED -> "已连接(WebRTC)"
            ConnectionState.STATUS_ERROR -> "连接错误"
            else -> "启动中..."
        }
    }

    private fun getStatusMessage(seconds: Int): String {
        val state = ConnectionState.getStatusForOwner("controlled")
        return when (state) {
            ConnectionState.STATUS_CONNECTING -> "连接中... ${seconds}s"
            ConnectionState.STATUS_REGISTERED -> "已注册，等待连接"
            ConnectionState.STATUS_CONNECTED -> "已连接(WebRTC)"
            ConnectionState.STATUS_ERROR -> "连接错误"
            else -> "启动中... ${seconds}s"
        }
    }

    private fun updateNotification(message: String) {
        val notification = buildNotification(message)
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun requestMediaProjection() {
        Log.i(TAG, "requestMediaProjection() checking all methods...")

        if (videoCaptureStarted) {
            Log.w(TAG, "Video capture already started, skipping")
            return
        }

        if (projectionRequestInProgress) {
            Log.w(TAG, "MediaProjection request already in progress, skipping")
            return
        }

        projectionRequestInProgress = true

        if (MediaProjectionHelper.hasCachedPermission()) {
            Log.i(TAG, "Using cached MediaProjection permission for WebRTC ScreenCapturer")
            val data = MediaProjectionHelper.getCachedProjectionData()
            if (data != null) {
                projectionRequestInProgress = false
                startWebRtcCapture(data, null)
                return
            }
        }

        if (RootManager.isRootAvailable()) {
            Log.i(TAG, "Root available, granting permissions for auto-click...")
            MediaProjectionHelper.grantAllRootPermissions(this)
            MediaProjectionHelper.bypassHiddenApiRestrictions()
        }

        Log.i(TAG, "Launching MediaProjectionActivity to get Intent for WebRTC")
        val intent = Intent(this, MediaProjectionActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    fun setMediaProjectionResult(code: Int, data: Intent) {
        Log.i(TAG, "setMediaProjectionResult() code=$code")
        projectionRequestInProgress = false
        MediaProjectionHelper.savePermission(code, data)
        startWebRtcCapture(data, pendingSdpFromId)
        processPendingSdp()
    }

    private fun processPendingSdp() {
        val sdpType = pendingSdpType ?: return
        val sdpContent = pendingSdpContent ?: return
        val fromId = pendingSdpFromId
        Log.i(TAG, "processPendingSdp: processing saved SDP from $fromId")
        pendingSdpType = null
        pendingSdpContent = null
        pendingSdpFromId = null

        if (webRtcClient != null) {
            if (fromId != null && webRtcClient?.peerId?.isEmpty() == true) {
                webRtcClient?.peerId = fromId
            }
            webRtcClient?.onRemoteSdp(sdpType, sdpContent)

            if (pendingIceCandidates.isNotEmpty()) {
                Log.i(TAG, "Flushing ${pendingIceCandidates.size} queued remote ICE candidates")
                pendingIceCandidates.forEach {
                    webRtcClient?.onRemoteIceCandidate(it.sdpMid, it.sdpMLineIndex, it.candidate)
                }
                pendingIceCandidates.clear()
            }
        } else {
            Log.w(TAG, "processPendingSdp: webRtcClient still null, dropping SDP")
        }
    }

    private fun startWebRtcCapture(mediaProjectionIntent: Intent, initialPeerId: String? = null) {
        if (videoCaptureStarted) {
            Log.w(TAG, "Video capture already started")
            return
        }
        videoCaptureStarted = true

        val (encW, encH) = calcEncodeSize(screenWidth, screenHeight, 1280)
        encWidth = encW
        encHeight = encH
        Log.i(TAG, "startWebRtcCapture() screen=${screenWidth}x${screenHeight} encode=${encW}x${encH}")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification("屏幕共享中"),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
                Log.i(TAG, "startForeground() upgraded with MEDIA_PROJECTION type")
            } catch (e: Exception) {
                Log.e(TAG, "startForeground() upgrade failed: ${e.javaClass.simpleName} - ${e.message}")
            }
        }

        webRtcClient = WebRtcClient(this, object : WebRtcClient.SignalingListener {
            override fun sendSdp(targetId: String, type: String, sdp: String) {
                signalingClient.sendSdp(targetId, type, sdp)
            }
            override fun sendIceCandidate(targetId: String, sdpMid: String, sdpMLineIndex: Int, candidate: String) {
                signalingClient.sendIceCandidate(targetId, sdpMid, sdpMLineIndex, candidate)
            }
        })

        webRtcClient?.eventListener = object : WebRtcClient.EventListener {
            override fun onControlEvent(eventJson: String) {
                try {
                    handleControlEventJson(eventJson)
                } catch (e: Exception) {
                    Log.e(TAG, "onControlEvent error: ${e.message}")
                }
            }

            override fun onRemoteVideoTrack(videoTrack: org.webrtc.VideoTrack) {
                Log.w(TAG, "Controlled side received remote video track (unexpected)")
            }

            override fun onConnectionStateChange(connected: Boolean) {
                Log.i(TAG, "WebRTC connection state changed: connected=$connected")
                if (connected) {
                    wakeUpScreenIfNeeded()
                    sendScreenInfo()
                } else {
                    Log.i(TAG, "WebRTC disconnected, turning screen off and resetting state")
                    turnScreenOffIfNeeded()
                    webRtcClient?.dispose()
                    webRtcClient = null
                    videoCaptureStarted = false
                    projectionRequestInProgress = false
                    pendingIceCandidates.clear()
                    pendingSdpFromId = null
                    pendingSdpType = null
                    pendingSdpContent = null
                }
            }
        }

        if (!initialPeerId.isNullOrEmpty()) {
            webRtcClient?.peerId = initialPeerId
        }

        webRtcClient?.initialize()
        webRtcClient?.setupAsControlled(mediaProjectionIntent, encW, encH, 30)
        webRtcClient?.startScreenCapture(mediaProjectionIntent, encW, encH, 30)

        Log.i(TAG, "WebRtcClient initialized and screen capture started immediately")
        updateNotification("已连接(WebRTC)")
    }

    private fun startScreenCaptureIfPending() {
        val client = webRtcClient ?: return
        if (client.isScreenCaptureReady) return

        val intent = pendingMediaProjectionIntent ?: return
        Log.i(TAG, "Starting screen capture now (was pending)")
        client.startScreenCapture(intent, pendingEncW, pendingEncH, 30)
    }

    private fun setupSignalingCallbacks() {
        Log.i(TAG, "setupSignalingCallbacks()")
        signalingClient.onConnectionStateChange = { status, message ->
            Log.i(TAG, "onConnectionStateChange: status=$status, message=$message")
            ConnectionState.update(status, message, "controlled")
            updateNotification(getInitialMessage())
        }

        signalingClient.onPeerConnected = { peerId, role ->
            Log.i(TAG, "onPeerConnected: peerId=$peerId, role=$role")
            if (webRtcClient != null) {
                webRtcClient?.peerId = peerId
            } else if (MediaProjectionHelper.hasCachedPermission()) {
                val data = MediaProjectionHelper.getCachedProjectionData()
                if (data != null) {
                    Log.i(TAG, "onPeerConnected: re-initializing WebRtcClient with cached MediaProjection")
                    startWebRtcCapture(data, peerId)
                    processPendingSdp()
                }
            } else {
                Log.i(TAG, "onPeerConnected: saving peerId=$peerId for later, WebRtcClient not ready yet")
                if (pendingSdpFromId == null) {
                    pendingSdpFromId = peerId
                }
                if (!projectionRequestInProgress && !videoCaptureStarted) {
                    requestMediaProjection()
                }
            }
        }

        signalingClient.onSdpOffer = lambda@{ fromId, type, sdp ->
            Log.i(TAG, "onSdpOffer: fromId=$fromId, sdpLength=${sdp.length}")
            if (webRtcClient == null) {
                if (MediaProjectionHelper.hasCachedPermission()) {
                    val data = MediaProjectionHelper.getCachedProjectionData()
                    if (data != null) {
                        startWebRtcCapture(data, fromId)
                    }
                } else {
                    Log.w(TAG, "onSdpOffer: no WebRtcClient and no MediaProjection, saving SDP for later")
                    pendingSdpType = type
                    pendingSdpContent = sdp
                    pendingSdpFromId = fromId
                    if (!projectionRequestInProgress && !videoCaptureStarted) {
                        requestMediaProjection()
                    }
                    return@lambda
                }
            }
            if (webRtcClient != null && webRtcClient?.peerId?.isEmpty() == true) {
                webRtcClient?.peerId = fromId
            }
            startScreenCaptureIfPending()
            webRtcClient?.onRemoteSdp(type, sdp)
        }

        signalingClient.onSdpAnswer = { fromId, type, sdp ->
            Log.i(TAG, "onSdpAnswer: fromId=$fromId, sdpLength=${sdp.length}")
            webRtcClient?.onRemoteSdp(type, sdp)
        }

        signalingClient.onIceCandidate = { fromId, sdpMid, sdpMLineIndex, candidate ->
            Log.d(TAG, "onIceCandidate: fromId=$fromId, sdpMid=$sdpMid")
            if (webRtcClient == null) {
                Log.i(TAG, "WebRtcClient is null, queueing ICE candidate from $fromId")
                pendingIceCandidates.add(PendingIceCandidate(sdpMid, sdpMLineIndex, candidate))
            } else {
                webRtcClient?.onRemoteIceCandidate(sdpMid, sdpMLineIndex, candidate)
            }
        }
    }

    private fun sendScreenInfo() {
        val info = JSONObject().apply {
            put("type", "SCREEN_INFO")
            put("width", screenWidth)
            put("height", screenHeight)
            put("dpi", screenDpi)
            put("encWidth", encWidth)
            put("encHeight", encHeight)
        }
        webRtcClient?.sendDataChannelMessage(info.toString())
        Log.i(TAG, "sendScreenInfo: $info")
    }

    private fun handleControlEventJson(eventJson: String) {
        val event = try {
            JSONObject(eventJson)
        } catch (e: Exception) {
            Log.e(TAG, "Invalid control event JSON: ${e.message}")
            return
        }

        val typeStr = event.optString("type", "")

        if (typeStr == "DISCONNECT") {
            Log.i(TAG, "Received DISCONNECT message from controller, closing WebRTC connection")
            turnScreenOffIfNeeded()
            webRtcClient?.dispose()
            webRtcClient = null
            videoCaptureStarted = false
            projectionRequestInProgress = false
            pendingIceCandidates.clear()
            pendingSdpFromId = null
            pendingSdpContent = null
            pendingSdpType = null
            MediaProjectionHelper.clearCachedPermission()
            ConnectionState.reset("controlled")
            serviceScope.launch { signalingClient.connect(DeviceIdManager.getDeviceId(this@ControlledService)) }
            updateNotification("已注册，等待控制")
            return
        }

        val x = event.optDouble("x", 0.0).toFloat()
        val y = event.optDouble("y", 0.0).toFloat()
        val x2 = event.optDouble("x2", 0.0).toFloat()
        val y2 = event.optDouble("y2", 0.0).toFloat()
        val dx = event.optDouble("dx", 0.0).toFloat()
        val dy = event.optDouble("dy", 0.0).toFloat()
        val durationMs = event.optInt("duration_ms", 0)
        val keyCode = event.optInt("key_code", 0)
        val text = event.optString("text", "")

        Log.d(TAG, "handleControlEvent: type=$typeStr, x=$x, y=$y, keyCode=$keyCode")

        val inj = injector ?: run {
            Log.w(TAG, "InputInjector not ready, retrying init")
            if (RootManager.isRootAvailable()) {
                try {
                    injector = InputInjector(this)
                    injector
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create InputInjector: ${e.message}")
                    null
                }
            } else {
                Log.w(TAG, "Root not available, cannot handle control event")
                null
            }
        }
        if (inj == null) {
            Log.w(TAG, "No InputInjector available, dropping control event type=$typeStr")
            return
        }

        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        val display = displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        val realMetrics = android.util.DisplayMetrics()
        display?.getRealMetrics(realMetrics)

        val currentWidth = realMetrics.widthPixels
        val currentHeight = realMetrics.heightPixels

        val absX = (x * currentWidth).toInt()
        val absY = (y * currentHeight).toInt()

        try {
            when (typeStr) {
                "TAP" -> {
                    Log.i(TAG, "Injecting tap at ($absX, $absY)")
                    inj.tap(absX, absY)
                }
                "SWIPE" -> inj.swipe(
                    absX, absY,
                    (x2 * currentWidth).toInt(),
                    (y2 * currentHeight).toInt(),
                    durationMs
                )
                "LONG_PRESS" -> inj.longPress(absX, absY)
                "SCROLL" -> {
                    val absDx = (dx * currentWidth).toInt()
                    val absDy = (dy * currentHeight).toInt()
                    batchScroll(inj, absX, absY, absDx, absDy)
                }
                "KEY" -> {
                    Log.i(TAG, "Injecting key event: $keyCode")
                    inj.key(keyCode)
                }
                "TEXT" -> inj.inputText(text)
                "BACK" -> {
                    Log.i(TAG, "Injecting BACK key")
                    inj.back()
                }
                "HOME" -> {
                    Log.i(TAG, "Injecting HOME key")
                    inj.home()
                }
                "RECENTS" -> {
                    Log.i(TAG, "Injecting RECENTS key")
                    inj.recents()
                }
                else -> {
                    Log.w(TAG, "Unknown event type: $typeStr")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleControlEvent error: ${e.message}")
        }
    }

    // 【修复2】将批处理逻辑移至协程的后台线程（IO 线程）中执行
    private fun batchScroll(inj: InputInjector, x: Int, y: Int, dx: Int, dy: Int) {
        if (!isBatchingScroll) {
            isBatchingScroll = true
            scrollStartX = x
            scrollStartY = y
            pendingScrollDx = dx
            pendingScrollDy = dy

            serviceScope.launch(Dispatchers.IO) {
                delay(80)
                if (pendingScrollDx != 0 || pendingScrollDy != 0) {
                    val endX = scrollStartX + pendingScrollDx
                    val endY = scrollStartY + pendingScrollDy
                    inj.scroll(scrollStartX, scrollStartY, endX - scrollStartX, endY - scrollStartY)
                }
                isBatchingScroll = false
                pendingScrollDx = 0
                pendingScrollDy = 0
            }
        } else {
            pendingScrollDx += dx
            pendingScrollDy += dy
        }
    }

    private fun wakeUpScreenIfNeeded() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val isScreenOn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            pm.isInteractive
        } else {
            @Suppress("DEPRECATION")
            pm.isScreenOn
        }

        if (!isScreenOn) {
            Log.i(TAG, "Screen is off, attempting to wake up")
            doWakeUpAndUnlock(pm)
        } else {
            Log.i(TAG, "Screen is already on, checking keyguard")
            dismissKeyguardIfNeeded()
        }
    }

    private fun doWakeUpAndUnlock(pm: PowerManager) {
        if (RootManager.isRootAvailable()) {
            try {
                val commands = listOf(
                    "input keyevent KEYCODE_WAKEUP",
                    "input keyevent KEYCODE_POWER",
                    "settings put system screen_brightness 128"
                )
                for (cmd in commands) {
                    // 【修复7】用 submit 替代 exec，避免阻塞主线程
                    com.topjohnwu.superuser.Shell.cmd(cmd).submit()
                }
                Log.i(TAG, "Screen wake-up commands executed via root")

                Handler(Looper.getMainLooper()).postDelayed({
                    dismissKeyguardIfNeeded()
                }, 800)
            } catch (e: Exception) {
                Log.e(TAG, "Root wake-up failed: ${e.message}")
            }
        }

        try {
            val screenWakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "SuperControler::ScreenWakeLock"
            )
            screenWakeLock.acquire(5000L)
            Log.i(TAG, "SCREEN_BRIGHT_WAKE_LOCK acquired for 5s")
            try {
                screenWakeLock.release()
            } catch (_: Exception) {}
        } catch (e: Exception) {
            Log.e(TAG, "WakeLock wake-up failed: ${e.message}")
        }
    }

    private fun dismissKeyguardIfNeeded() {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1 && km.isDeviceLocked) {
            Log.i(TAG, "Device is locked, attempting to dismiss keyguard via root")
            if (RootManager.isRootAvailable()) {
                try {
                    val screenW = screenWidth.coerceAtLeast(1080)
                    val screenH = screenHeight.coerceAtLeast(1920)
                    val startY = (screenH * 0.75).toInt()
                    val endY = (screenH * 0.25).toInt()
                    val centerX = screenW / 2

                    val commands = listOf(
                        "input keyevent KEYCODE_MENU",
                        "input swipe $centerX $startY $centerX $endY 300"
                    )
                    for (cmd in commands) {
                        // 【修复7】用 submit 替代 exec
                        com.topjohnwu.superuser.Shell.cmd(cmd).submit()
                    }
                    Log.i(TAG, "Keyguard dismiss swipe executed via root")
                } catch (e: Exception) {
                    Log.e(TAG, "Root keyguard dismiss failed: ${e.message}")
                }
            }
        } else {
            Log.i(TAG, "Device is not locked or keyguard not detected")
        }
    }

    private fun turnScreenOffIfNeeded() {
        if (RootManager.isRootAvailable()) {
            try {
                // 【修复7】用 submit 替代 exec
                com.topjohnwu.superuser.Shell.cmd("input keyevent KEYCODE_POWER").submit()
                Log.i(TAG, "Screen turned off via root after WebRTC disconnect")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to turn screen off: ${e.message}")
            }
        }
    }

    private fun calcEncodeSize(w: Int, h: Int, maxLong: Int): Pair<Int, Int> {
        val ratio = maxLong.toFloat() / maxOf(w, h)
        if (ratio >= 1f) {
            return Pair(w - w % 2, h - h % 2)
        }
        val targetW = (w * ratio).toInt()
        val targetH = (h * ratio).toInt()
        return Pair(targetW - targetW % 2, targetH - targetH % 2)
    }

    private fun Int.floorTo16() = (this / 16) * 16

    private fun buildNotification(message: String): Notification {
        val channel = NotificationChannel(
            CHANNEL_ID, "远程控制服务", NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, Class.forName("com.yourapp.supercontroler.MainActivity")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SuperControler")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand() action=${intent?.action}")

        if (intent?.action == "MEDIA_PROJECTION_RESULT") {
            val resultCode = intent.getIntExtra(MediaProjectionActivity.EXTRA_RESULT_CODE, 0)
            val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(MediaProjectionActivity.EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(MediaProjectionActivity.EXTRA_RESULT_DATA)
            }

            Log.i(TAG, "onStartCommand() MEDIA_PROJECTION_RESULT: resultCode=$resultCode, data=$data")

            if (resultCode == -1 && data != null) {
                Log.i(TAG, "onStartCommand() MediaProjection result OK, initialized=$isInitialized")
                MediaProjectionHelper.savePermission(resultCode, data)
                if (isInitialized) {
                    setMediaProjectionResult(resultCode, data)
                } else {
                    pendingProjectionResultCode = resultCode
                    pendingProjectionData = data
                    Log.i(TAG, "onStartCommand() Service not initialized yet, queuing result")
                }
            } else if (MediaProjectionHelper.hasCachedPermission()) {
                Log.i(TAG, "onStartCommand() Using cached MediaProjection permission")
                if (isInitialized) {
                    val cachedData = MediaProjectionHelper.getCachedProjectionData()
                    if (cachedData != null) {
                        startWebRtcCapture(cachedData, null)
                    }
                } else {
                    pendingProjectionResultCode = -1
                    pendingProjectionData = MediaProjectionHelper.getCachedProjectionData()
                    Log.i(TAG, "onStartCommand() Service not initialized, queuing cached result")
                }
            } else {
                Log.w(TAG, "onStartCommand() MediaProjection denied or invalid")
            }
        } else if (!isInitialized) {
            Log.i(TAG, "onStartCommand() re-creating service after kill")
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?) = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.w(TAG, "onTaskRemoved() - restarting service...")
        if (!userStopped) {
            val restartIntent = Intent(applicationContext, ControlledService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(restartIntent)
            } else {
                applicationContext.startService(restartIntent)
            }

            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                Log.w(TAG, "onTaskRemoved() - delayed restart")
                start(applicationContext)
            }, 3000)
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "=== onDestroy() ===")
        instance = null
        val wasUserStopped = userStopped
        userStopped = true

        watchdogJob?.cancel()
        notificationUpdateRunnable?.let { notificationUpdateHandler?.removeCallbacks(it) }
        unregisterNetworkReceiver()
        releaseWakeLock()

        webRtcClient?.dispose()
        webRtcClient = null

        injector?.destroy()
        injector = null

        if (::signalingClient.isInitialized) {
            try { signalingClient.disconnect() } catch (_: Exception) {}
        }
        if (::captureManager.isInitialized) {
            try { captureManager.stopCapture() } catch (_: Exception) {}
        }
        serviceScope.cancel()
        ConnectionState.reset("controlled")

        if (!wasUserStopped) {
            Log.w(TAG, "onDestroy() - service killed by system, restarting in 5s")
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (!userStopped) {
                    start(applicationContext)
                }
            }, 5000)
        }

        super.onDestroy()
    }

    private var isStoppingSelf = false

    fun stopSelfSafely() {
        isStoppingSelf = true
        stopSelf()
    }
}
