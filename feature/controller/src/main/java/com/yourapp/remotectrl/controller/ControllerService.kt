package com.yourapp.remotectrl.controller

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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
import com.yourapp.remotectrl.webrtc.WebRtcClient
import kotlinx.coroutines.*

class ControllerService : Service() {

    companion object {
        const val TAG = "ControllerService"
        const val NOTIFICATION_ID = 3001
        const val CHANNEL_ID = "controller_channel"

        @Volatile
        var userStopped = false

        fun start(context: Context) {
            Log.i(TAG, "start() called")
            userStopped = false
            val intent = Intent(context, ControllerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            userStopped = true
            context.stopService(Intent(context, ControllerService::class.java))
        }

        private var instance: ControllerService? = null
        fun getInstance(): ControllerService? = instance
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var signalingClient: SignalingClient? = null
    private var myDeviceId: String = ""

    var webRtcClient: WebRtcClient? = null
        internal set

    private var wakeLock: PowerManager.WakeLock? = null
    private var watchdogJob: Job? = null
    private var notificationUpdateHandler: Handler? = null
    private var notificationUpdateRunnable: Runnable? = null

    private var pendingTargetId: String? = null
    private var pendingPin: String = "123456"
    private var isInitialized = false
    private var isConnecting = false
    private var reconnectFailCount = 0

    private val onlineDevices = mutableMapOf<String, SignalingClient.OnlineDevice>()
    var onDevicesUpdate: ((Map<String, SignalingClient.OnlineDevice>) -> Unit)? = null

    private var activityStateCallback: ((String, String) -> Unit)? = null
    private var activityVideoTrackCallback: ((org.webrtc.VideoTrack) -> Unit)? = null
    private var activityWebRtcConnectedCallback: ((Boolean) -> Unit)? = null
    var activityScreenInfoCallback: ((Int, Int) -> Unit)? = null

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                Log.i(TAG, "Network available: $network")
                serviceScope.launch {
                    delay(1000)
                    signalingClient?.notifyNetworkChanged()
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
        Log.i(TAG, "=== onCreate() START ===")

        acquireWakeLock()
        Log.i(TAG, "WakeLock acquired")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification("正在启动..."),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("正在启动..."))
        }
        Log.i(TAG, "startForeground() called")

        myDeviceId = DeviceIdManager.getDeviceId(this)
        Log.i(TAG, "My Device ID: $myDeviceId")

        startServerConnection()
        startWatchdog()
        startNotificationUpdater()
        registerNetworkReceiver()

        isInitialized = true
        Log.i(TAG, "=== onCreate() END ===")
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SuperControler::ControllerWakeLock").apply {
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

    private fun startServerConnection() {
        val serverUrl = ServerConfig.getServerUrl(this)
        Log.i(TAG, "Server URL: $serverUrl")

        signalingClient = SignalingClient(serverUrl)
        setupSignalingCallbacks()

        isConnecting = true
        serviceScope.launch {
            try {
                signalingClient?.connect(myDeviceId)
                Log.i(TAG, "connect() launched")
            } catch (e: Exception) {
                Log.e(TAG, "connect() failed: ${e.message}")
                isConnecting = false
            }
        }
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
        val state = ConnectionState.getStatusForOwner("controller")
        Log.w(TAG, "Watchdog: current state = $state")
        if (state == ConnectionState.STATUS_IDLE || state == ConnectionState.STATUS_ERROR) {
            reconnectFailCount++
            Log.w(TAG, "Watchdog: Connection lost (failCount=$reconnectFailCount)")
            if (reconnectFailCount >= 3) {
                Log.w(TAG, "Watchdog: Too many failures, recreating SignalingClient")
                reconnectFailCount = 0
                updateNotification("正在重连...")
                try {
                    signalingClient?.disconnect()
                } catch (e: Exception) {
                    Log.w(TAG, "Watchdog: disconnect error: ${e.message}")
                }
                delay(2000)
                signalingClient = SignalingClient(ServerConfig.getServerUrl(this@ControllerService))
                setupSignalingCallbacks()
                try {
                    signalingClient?.connect(myDeviceId)
                    Log.i(TAG, "Watchdog: reconnect initiated")
                } catch (e: Exception) {
                    Log.e(TAG, "Watchdog: reconnect failed: ${e.message}")
                    isConnecting = false
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
                val state = ConnectionState.getStatusForOwner("controller")
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
        val state = ConnectionState.getStatusForOwner("controller")
        return when (state) {
            ConnectionState.STATUS_CONNECTING -> "连接中..."
            ConnectionState.STATUS_REGISTERED -> "已注册，等待控制"
            ConnectionState.STATUS_CONNECTED -> "已连接(WebRTC)"
            ConnectionState.STATUS_ERROR -> "连接错误"
            else -> "启动中..."
        }
    }

    private fun getStatusMessage(seconds: Int): String {
        val state = ConnectionState.getStatusForOwner("controller")
        return when (state) {
            ConnectionState.STATUS_CONNECTING -> "连接中... ${seconds}s"
            ConnectionState.STATUS_REGISTERED -> "已注册，等待控制"
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

    fun setActivityCallbacks(
        onStateChange: ((String, String) -> Unit)?,
        onVideoTrack: ((org.webrtc.VideoTrack) -> Unit)?,
        onWebRtcConnected: ((Boolean) -> Unit)?
    ) {
        activityStateCallback = onStateChange
        activityVideoTrackCallback = onVideoTrack
        activityWebRtcConnectedCallback = onWebRtcConnected

        val currentStatus = ConnectionState.getStatusForOwner("controller")
        val currentMessage = ConnectionState.getMessageForOwner("controller")
        if (onStateChange != null) {
            Log.i(TAG, "Sending current state to Activity: $currentStatus - $currentMessage")
            try {
                onStateChange(currentStatus, currentMessage)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send current state: ${e.message}")
            }
        }
    }

    private fun setupSignalingCallbacks() {
        Log.i(TAG, "setupSignalingCallbacks()")
        signalingClient?.onConnectionStateChange = { status, message ->
            Log.i(TAG, "onConnectionStateChange: status=$status, message=$message")
            ConnectionState.update(status, message, "controller")
            if (status == ConnectionState.STATUS_REGISTERED) {
                isConnecting = false
                Log.i(TAG, "Registered, requesting devices list...")
                requestDevicesListWithRetry()
                val pending = pendingTargetId
                if (pending != null) {
                    Log.i(TAG, "Auto-connecting to pending target: $pending")
                    doConnectToDevice()
                }
            }
            updateNotification(getInitialMessage())
            try {
                activityStateCallback?.invoke(status, message)
            } catch (e: Exception) {
                Log.e(TAG, "activityStateCallback error: ${e.message}")
            }
        }

        signalingClient?.onPeerConnected = { peerId, role ->
            Log.i(TAG, "onPeerConnected: peerId=$peerId, role=$role")
            if (role != "controller") {
                Log.w(TAG, "Unexpected role='$role' for controller side, expected 'controller'. Proceeding anyway.")
            }
            initWebRtcAsController(peerId)
        }

        signalingClient?.onSdpOffer = { fromId, type, sdp ->
            Log.w(TAG, "Controller received SDP offer from $fromId (unexpected - controller should be offerer)")
        }

        signalingClient?.onSdpAnswer = { fromId, type, sdp ->
            Log.i(TAG, "onSdpAnswer: fromId=$fromId, sdpLength=${sdp.length}")
            val client = webRtcClient
            if (client != null) {
                if (client.peerId.isEmpty()) {
                    client.peerId = fromId
                }
                client.onRemoteSdp(type, sdp)
            } else {
                Log.w(TAG, "onSdpAnswer: webRtcClient is null, dropping answer")
            }
        }

        signalingClient?.onIceCandidate = { fromId, sdpMid, sdpMLineIndex, candidate ->
            Log.d(TAG, "onIceCandidate: fromId=$fromId, sdpMid=$sdpMid")
            webRtcClient?.onRemoteIceCandidate(sdpMid, sdpMLineIndex, candidate)
        }

        signalingClient?.onDevicesOnline = { devices ->
            Log.i(TAG, "onDevicesOnline: received ${devices.size} devices")
            for (device in devices) {
                if (device.isOnline) {
                    onlineDevices[device.id] = device
                } else {
                    onlineDevices.remove(device.id)
                }
            }
            val devicesCopy = onlineDevices.toMap()
            Handler(Looper.getMainLooper()).post {
                try {
                    onDevicesUpdate?.invoke(devicesCopy)
                } catch (e: Exception) {
                    Log.e(TAG, "onDevicesUpdate error: ${e.message}")
                }
            }
            try {
                sendDevicesBroadcast(devicesCopy)
            } catch (e: Exception) {
                Log.e(TAG, "sendDevicesBroadcast error: ${e.message}")
            }
        }
    }

    @Volatile
    private var isInitializingWebRtc = false

    private fun initWebRtcAsController(peerId: String) {
        Log.i(TAG, "initWebRtcAsController: peerId=$peerId")

        if (isInitializingWebRtc) {
            Log.w(TAG, "initWebRtcAsController: already initializing, skipping")
            return
        }
        isInitializingWebRtc = true

        serviceScope.launch {
            try {
                val oldClient = webRtcClient
                if (oldClient != null) {
                    Log.i(TAG, "Disposing old WebRtcClient on IO thread...")
                    try {
                        oldClient.dispose()
                    } catch (e: Exception) {
                        Log.e(TAG, "dispose old client error: ${e.message}")
                    }
                    webRtcClient = null
                }

                val client = WebRtcClient(this@ControllerService, object : WebRtcClient.SignalingListener {
                    override fun sendSdp(targetId: String, type: String, sdp: String) {
                        signalingClient?.sendSdp(targetId, type, sdp)
                    }
                    override fun sendIceCandidate(targetId: String, sdpMid: String, sdpMLineIndex: Int, candidate: String) {
                        signalingClient?.sendIceCandidate(targetId, sdpMid, sdpMLineIndex, candidate)
                    }
                })

                client.eventListener = object : WebRtcClient.EventListener {
                    override fun onControlEvent(eventJson: String) {
                        Log.w(TAG, "Controller received control event (unexpected)")
                    }

                    override fun onRemoteVideoTrack(videoTrack: org.webrtc.VideoTrack) {
                        Log.i(TAG, "Remote VideoTrack received from controlled side")
                        try {
                            activityVideoTrackCallback?.invoke(videoTrack)
                        } catch (e: Exception) {
                            Log.e(TAG, "activityVideoTrackCallback error: ${e.message}")
                        }
                    }

                    override fun onConnectionStateChange(connected: Boolean) {
                        Log.i(TAG, "WebRTC connection state: connected=$connected")
                        try {
                            activityWebRtcConnectedCallback?.invoke(connected)
                        } catch (e: Exception) {
                            Log.e(TAG, "activityWebRtcConnectedCallback error: ${e.message}")
                        }
                    }

                    override fun onDataChannelMessage(message: String) {
                        try {
                            val json = org.json.JSONObject(message)
                            val msgType = json.optString("type", "")
                            if (msgType == "SCREEN_INFO") {
                                val width = json.optInt("width", 1080)
                                val height = json.optInt("height", 2400)
                                Log.i(TAG, "Received SCREEN_INFO: ${width}x${height}")
                                try {
                                    activityScreenInfoCallback?.invoke(width, height)
                                } catch (e: Exception) {
                                    Log.e(TAG, "activityScreenInfoCallback error: ${e.message}")
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "onDataChannelMessage parse error: ${e.message}")
                        }
                    }
                }

                Log.i(TAG, "Initializing WebRTC on IO thread...")
                client.initialize()
                Log.i(TAG, "WebRTC initialized, setting up as controller for peerId=$peerId")
                client.setupAsController(peerId)
                webRtcClient = client

                Log.i(TAG, "WebRTC controller initialized successfully, offer sent for peerId=$peerId")
            } catch (e: Exception) {
                Log.e(TAG, "initWebRtcAsController FAILED: ${e.javaClass.simpleName} - ${e.message}", e)
                webRtcClient = null
                withContext(Dispatchers.Main) {
                    ConnectionState.update(ConnectionState.STATUS_ERROR, "WebRTC初始化失败: ${e.message}", "controller")
                }
            } finally {
                isInitializingWebRtc = false
            }
        }
    }

    private fun requestDevicesListWithRetry() {
        serviceScope.launch {
            repeat(3) { i ->
                delay(i * 2000L)
                Log.i(TAG, "Requesting devices list (attempt ${i + 1}/3)")
                signalingClient?.requestDevicesList()
            }
        }
    }

    fun connectToDevice(targetId: String, pin: String = "123456") {
        Log.i(TAG, "connectToDevice() targetId=$targetId")
        pendingTargetId = targetId
        pendingPin = pin

        val myState = ConnectionState.getStatusForOwner("controller")
        if (myState == ConnectionState.STATUS_REGISTERED) {
            doConnectToDevice()
        } else if (myState == ConnectionState.STATUS_CONNECTING ||
                   myState == ConnectionState.STATUS_IDLE) {
            Log.w(TAG, "Not registered yet (state=$myState), queuing connection request")
        } else if (myState == ConnectionState.STATUS_CONNECTED) {
            Log.w(TAG, "Already connected, disconnecting old and reconnecting to new target")
            disconnectWebRtcAndReset()
            doConnectToDevice()
        } else {
            Log.w(TAG, "Unexpected state $myState, attempting connect anyway")
            doConnectToDevice()
        }
    }

    fun disconnectWebRtcAndReset() {
        Log.i(TAG, "disconnectWebRtcAndReset() called")
        webRtcClient?.dispose()
        webRtcClient = null
        pendingTargetId = null
        isConnecting = false
        signalingClient?.resetPeerState()
        ConnectionState.update(ConnectionState.STATUS_REGISTERED, "已注册，等待控制", "controller")
        setActivityCallbacks(null, null, null)
        Log.i(TAG, "WebRTC reset done, signaling connection kept alive")
    }

    private fun doConnectToDevice() {
        val targetId = pendingTargetId ?: return
        val pin = pendingPin

        isConnecting = true
        updateNotification("正在连接 $targetId ...")

        try {
            signalingClient?.connectToDevice(targetId, pin)
            Log.i(TAG, "connectToDevice() sent to server")
        } catch (e: Exception) {
            Log.e(TAG, "connectToDevice() failed: ${e.message}")
            isConnecting = false
            ConnectionState.update(ConnectionState.STATUS_ERROR, "连接失败: ${e.message}", "controller")
        }
    }

    fun getSignalingClient(): SignalingClient? = signalingClient

    fun getOnlineDevices(): Map<String, SignalingClient.OnlineDevice> = onlineDevices.toMap()

    fun refreshDevicesList() {
        Log.i(TAG, "refreshDevicesList()")
        signalingClient?.requestDevicesList()
    }

    private fun sendDevicesBroadcast(devices: Map<String, SignalingClient.OnlineDevice>) {
        val intent = Intent("com.yourapp.supercontroler.DEVICES_UPDATE").apply {
            val jsonArray = org.json.JSONArray()
            for ((_, device) in devices) {
                val obj = org.json.JSONObject().apply {
                    put("id", device.id)
                    put("name", device.name)
                    put("publicIp", device.publicIp)
                    put("isOnline", device.isOnline)
                }
                jsonArray.put(obj)
            }
            putExtra("devices_json", jsonArray.toString())
        }
        sendBroadcast(intent)
    }

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
            .setContentTitle("SuperControler(主控)")
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
        return START_STICKY
    }

    override fun onBind(intent: Intent?) = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.w(TAG, "onTaskRemoved() - restarting service...")
        if (!userStopped) {
            val restartIntent = Intent(applicationContext, ControllerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(restartIntent)
            } else {
                applicationContext.startService(restartIntent)
            }
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (!userStopped) start(applicationContext)
            }, 3000)
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "=== onDestroy() ===")
        val wasUserStopped = userStopped
        userStopped = true
        instance = null
        watchdogJob?.cancel()
        notificationUpdateRunnable?.let { notificationUpdateHandler?.removeCallbacks(it) }
        unregisterNetworkReceiver()
        releaseWakeLock()
        webRtcClient?.dispose()
        webRtcClient = null
        try { signalingClient?.disconnect() } catch (_: Exception) {}
        serviceScope.cancel()
        ConnectionState.reset("controller")

        if (!wasUserStopped) {
            Log.w(TAG, "onDestroy() - service killed by system, restarting in 5s")
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (!userStopped) start(applicationContext)
            }, 5000)
        }

        super.onDestroy()
    }
}
