package com.yourapp.remotectrl.network

import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import org.json.JSONObject
import java.net.*
import java.util.concurrent.TimeUnit

class SignalingClient(private val serverUrl: String) {

    companion object {
        private const val FALLBACK_IP = "74.48.6.172"
        private const val FALLBACK_PORT = "8765"
        private const val TAG = "Signaling"
    }

    data class OnlineDevice(
        val id: String,
        val name: String,
        val publicIp: String,
        val isOnline: Boolean
    )

    var onConnectionStateChange: ((String, String) -> Unit)? = null
    var onDevicesRefresh: ((List<OnlineDevice>) -> Unit)? = null
    var onDeviceUpdate: ((OnlineDevice) -> Unit)? = null
    var onSdpOffer: ((fromId: String, type: String, sdp: String) -> Unit)? = null
    var onSdpAnswer: ((fromId: String, type: String, sdp: String) -> Unit)? = null
    var onIceCandidate: ((fromId: String, sdpMid: String, sdpMLineIndex: Int, candidate: String) -> Unit)? = null
    var onPeerConnected: ((peerId: String, role: String) -> Unit)? = null

    private var ws: WebSocket? = null
    private var myId = ""
    private var peerId = ""
    private var myPublicIp = ""
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var reconnectAttempts = 0

    @Volatile
    private var isDestroyed = false
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var healthCheckJob: Job? = null

    @Volatile
    private var wsGeneration = 0L

    @Volatile
    private var lastPongTime = 0L

    private val okHttpClient = createOkHttpClient()

    private fun createOkHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .pingInterval(45, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .connectTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    try {
                        val addresses = Dns.SYSTEM.lookup(hostname)
                        val ipv4Only = addresses.filter { it is Inet4Address }
                        return if (ipv4Only.isNotEmpty()) {
                            ipv4Only
                        } else {
                            addresses
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "DNS lookup failed for $hostname: ${e.message}")
                        throw e
                    }
                }
            })

        try {
            val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(
                object : javax.net.ssl.X509TrustManager {
                    override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                }
            )
            val sslContext = javax.net.ssl.SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())
            builder.sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as javax.net.ssl.X509TrustManager)
            builder.hostnameVerifier { _, _ -> true }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure TLS: ${e.message}")
        }

        return builder.build()
    }

    sealed class State {
        object Idle : State()
        object Connecting : State()
        object Registered : State()
        object Connected : State()
        data class Error(val msg: String) : State()
    }

    @Volatile
    private var state: State = State.Idle
    private val stateLock = Mutex()

    fun getState(): State = state

    suspend fun connect(deviceId: String) {
        myId = deviceId
        peerId = ""
        isDestroyed = false
        reconnectAttempts = 0
        connectWebSocket()
    }

    private var pendingConnectTargetId: String? = null
    private var pendingConnectPin: String = "123456"

    fun connectToDevice(targetId: String, pin: String = "123456") {
        Log.i(TAG, "connectToDevice() targetId=$targetId, current state=$state")
        this.peerId = targetId
        this.pendingConnectPin = pin

        val currentWs = ws
        if (currentWs != null && (state is State.Registered || state is State.Connected)) {
            sendConnectMessage(currentWs, targetId, pin)
        } else {
            Log.w(TAG, "Not registered yet, queuing connect request for target=$targetId")
            pendingConnectTargetId = targetId
        }
    }

    fun resetPeerState() {
        Log.i(TAG, "resetPeerState() called")
        peerId = ""
        pendingConnectTargetId = null
        scope.launch {
            if (state is State.Connected) {
                setState(State.Registered)
            }
        }
    }

    private fun sendPendingConnectIfAny() {
        val pending = pendingConnectTargetId ?: return
        val pin = pendingConnectPin
        pendingConnectTargetId = null
        val currentWs = ws
        if (currentWs != null && state is State.Registered) {
            sendConnectMessage(currentWs, pending, pin)
        }
    }

    private fun sendConnectMessage(ws: WebSocket, targetId: String, pin: String) {
        val payload = JSONObject().apply {
            put("target_id", targetId)
            put("pin", pin)
        }
        val msg = JSONObject().apply { put("type", "connect"); put("payload", payload) }
        Log.i(TAG, "Sending connect: target_id=$targetId")
        val sent = ws.send(msg.toString())
        if (!sent) {
            Log.e(TAG, "Failed to send connect message!")
        }
    }

    private var useFallbackUrl = false

    private fun getEffectiveServerUrl(): String {
        if (useFallbackUrl) {
            return "wss://$FALLBACK_IP:$FALLBACK_PORT/ws"
        }
        return serverUrl
    }

    private suspend fun connectWebSocket() {
        if (isDestroyed) {
            Log.w(TAG, "Attempted to connect on destroyed client")
            return
        }

        reconnectJob?.cancel()
        reconnectJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        healthCheckJob?.cancel()
        healthCheckJob = null

        val oldWs = ws
        ws = null
        if (oldWs != null) {
            try { oldWs.close(1000, "reconnect") } catch (_: Exception) {}
        }

        val gen = ++wsGeneration
        lastPongTime = System.currentTimeMillis()
        setState(State.Connecting)

        val effectiveUrl = getEffectiveServerUrl()
        Log.i(TAG, "Connecting to $effectiveUrl (gen=$gen)")

        val request = Request.Builder()
            .url(effectiveUrl)
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (gen != wsGeneration) {
                    webSocket.close(1000, "stale")
                    return
                }
                Log.i(TAG, "WebSocket connected! (gen=$gen)")
                useFallbackUrl = false
                this@SignalingClient.ws = webSocket
                onWsOpen(webSocket)
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (gen != wsGeneration) return
                lastPongTime = System.currentTimeMillis()
                onWsMessage(text)
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WS onClosing (gen=$gen): code=$code reason='$reason'")
                if (isDestroyed || gen != wsGeneration) return
                scope.launch {
                    setState(State.Error("连接关闭中: code=$code reason=$reason"))
                }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WS onClosed (gen=$gen): code=$code reason='$reason'")
                if (isDestroyed || gen != wsGeneration) return
                if (code == 1000 && (reason == "disconnect" || reason == "reconnect" || reason == "stale")) return
                scope.launch {
                    setState(State.Error("连接已断开, 极速重连中..."))
                    scheduleReconnect()
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WS onFailure (gen=$gen): ${t.javaClass.simpleName} - ${t.message}")
                if (isDestroyed || gen != wsGeneration) return
                val msg = t.message ?: ""
                val isLocalhostError = msg.contains("127.0.0.1") || msg.contains("localhost") || msg.contains("::1")
                if (isLocalhostError && !useFallbackUrl && !serverUrl.contains(FALLBACK_IP)) {
                    useFallbackUrl = true
                }
                scope.launch {
                    val shortError = if (msg.contains("Connection reset") || msg.contains("broken pipe") || msg.contains("Software caused connection abort")) {
                        "网络中断"
                    } else {
                        t.javaClass.simpleName
                    }
                    setState(State.Error("掉线重连中... ($shortError)"))
                    scheduleReconnect()
                }
            }
        }

        okHttpClient.newWebSocket(request, listener)
    }

    private fun onWsOpen(ws: WebSocket) {
        reconnectAttempts = 0
        startAppHeartbeat(ws)
        startHealthCheck()

        val deviceName = Build.MODEL ?: "Unknown"
        val payload = JSONObject().apply {
            put("device_id", myId)
            put("name", deviceName)
        }
        val registerMsg = JSONObject().apply { put("type", "register"); put("payload", payload) }
        Log.i(TAG, "Sending register: device_id=$myId")
        val sent = ws.send(registerMsg.toString())
        if (!sent) {
            Log.e(TAG, "Failed to send register message!")
        }
    }

    private fun startAppHeartbeat(ws: WebSocket) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && !isDestroyed) {
                delay(20_000)
                try {
                    val ping = JSONObject().apply { put("type", "ping") }
                    val sent = ws.send(ping.toString())
                    if (!sent) {
                        Log.w(TAG, "Heartbeat send returned false, waiting for next cycle")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Heartbeat error: ${e.message}")
                }
            }
        }
    }

    private fun startHealthCheck() {
        healthCheckJob?.cancel()
        healthCheckJob = scope.launch {
            while (isActive && !isDestroyed) {
                delay(15_000)
                val elapsed = System.currentTimeMillis() - lastPongTime
                if (elapsed > 45_000) {
                    Log.w(TAG, "No message from server for ${elapsed}ms, closing connection")
                    val currentWs = ws
                    if (currentWs != null) {
                        try { currentWs.close(1001, "health check timeout") } catch (_: Exception) {}
                    }
                    ws = null
                    setState(State.Error("心跳超时, 极速重连中..."))
                    scheduleReconnect()
                    break
                }
            }
        }
    }

    private fun onWsMessage(text: String) {
        val msg = try { JSONObject(text) } catch (e: Exception) {
            Log.e(TAG, "Invalid JSON: ${text.take(100)}")
            return
        }
        when (msg.optString("type")) {
            "registered" -> {
                myPublicIp = msg.optJSONObject("payload")?.optString("public_ip", "") ?: ""
                Log.i(TAG, "Registered! myId=$myId, public IP: $myPublicIp")
                scope.launch {
                    setState(State.Registered)
                    sendPendingConnectIfAny()
                }
            }
            "punch_info" -> {
                val payload = msg.optJSONObject("payload") ?: return
                val role = payload.optString("role", "")
                val extractedPeerId = payload.optString("peer_id", "")
                    .ifEmpty { payload.optString("from_id", "") }
                    .ifEmpty { payload.optString("device_id", "") }
                    .ifEmpty { payload.optString("source_id", "") }
                    .ifEmpty { payload.optString("target_id", "") }
                    .ifEmpty { payload.optString("remote_id", "") }
                    .ifEmpty { payload.optString("initiator_id", "") }

                if (extractedPeerId.isNotEmpty() && peerId.isEmpty()) {
                    peerId = extractedPeerId
                    Log.i(TAG, "punch_info: set peerId=$peerId from payload")
                }

                Log.i(TAG, "punch_info received: peerId=$peerId, role=$role (WebRTC mode - skipping UDP punch)")
                scope.launch {
                    setState(State.Connected)
                    // 【修复9】强制切换到主线程触发连接成功回调
                    withContext(Dispatchers.Main) {
                        onPeerConnected?.invoke(peerId, role)
                    }
                }
            }
            "relay" -> {
                handleRelayMessage(msg)
            }
            "error" -> {
                val errMsg = msg.optJSONObject("payload")?.optString("message", "Unknown error") ?: "Unknown error"
                Log.e(TAG, "Server error: $errMsg")
                scope.launch { setState(State.Error("服务器错误: $errMsg")) }
            }
            "devices_list" -> {
                val payload = msg.optJSONObject("payload") ?: return
                val devicesArray = payload.optJSONArray("devices") ?: return
                val devices = mutableListOf<OnlineDevice>()
                for (i in 0 until devicesArray.length()) {
                    val dev = devicesArray.optJSONObject(i) ?: continue
                    devices.add(OnlineDevice(
                        id = dev.optString("device_id", ""),
                        name = dev.optString("name", ""),
                        publicIp = dev.optString("public_ip", ""),
                        isOnline = dev.optBoolean("online", false)
                    ))
                }
                Log.i(TAG, "Received devices_list: ${devices.size} devices")
                scope.launch(Dispatchers.Main) {
                    onDevicesRefresh?.invoke(devices)
                }
            }
            "device_online", "device_offline" -> {
                val payload = msg.optJSONObject("payload") ?: return
                val device = OnlineDevice(
                    id = payload.optString("device_id", ""),
                    name = payload.optString("name", ""),
                    publicIp = payload.optString("public_ip", ""),
                    isOnline = msg.optString("type") == "device_online"
                )
                Log.i(TAG, "Device ${if (device.isOnline) "online" else "offline"}: ${device.id}")
                scope.launch(Dispatchers.Main) {
                    onDeviceUpdate?.invoke(device)
                }
            }
            "pong" -> {
                Log.d(TAG, "Server pong received")
            }
            else -> {
                Log.w(TAG, "Unhandled message type: ${msg.optString("type")}")
            }
        }
    }

    private fun handleRelayMessage(msg: JSONObject) {
        val fromId = msg.optString("from", "")

        var payloadStr = ""

        val directString = msg.optString("payload", "")
        if (directString.isNotEmpty() && directString.startsWith("{")) {
            payloadStr = directString
        } else {
            val innerPayload = msg.optJSONObject("payload")
            if (innerPayload != null) {
                payloadStr = innerPayload.optString("payload", "")
            }
        }

        if (payloadStr.isEmpty()) {
            Log.w(TAG, "Relay message has no valid payload. Raw msg: $msg")
            return
        }

        val payloadObj = try {
            JSONObject(payloadStr)
        } catch (e: Exception) {
            Log.w(TAG, "Relay payload is not valid JSON: ${payloadStr.take(100)}")
            return
        }

        val signalType = payloadObj.optString("signal_type", "")
        if (signalType.isEmpty()) {
            Log.w(TAG, "Relay message has no signal_type")
            return
        }

        Log.i(TAG, "Relay signal received: type=$signalType from=$fromId")

        when (signalType) {
            "sdp-offer" -> {
                val sdp = payloadObj.optString("sdp", "")
                if (sdp.isNotEmpty()) onSdpOffer?.invoke(fromId, "offer", sdp)
            }
            "sdp-answer" -> {
                val sdp = payloadObj.optString("sdp", "")
                if (sdp.isNotEmpty()) onSdpAnswer?.invoke(fromId, "answer", sdp)
            }
            "ice-candidate" -> {
                val sdpMid = payloadObj.optString("sdp_mid", "")
                val sdpMLineIndex = payloadObj.optInt("sdp_mline_index", 0)
                val candidate = payloadObj.optString("candidate", "")
                if (candidate.isNotEmpty()) {
                    onIceCandidate?.invoke(fromId, sdpMid, sdpMLineIndex, candidate)
                }
            }
            else -> {
                Log.w(TAG, "Unknown signal_type: $signalType")
            }
        }
    }

    fun sendSdp(targetId: String, type: String, sdp: String) {
        val currentWs = ws
        if (currentWs == null) {
            Log.w(TAG, "sendSdp: ws is null")
            return
        }

        val signalType = if (type == "offer") "sdp-offer" else "sdp-answer"

        val signalPayload = JSONObject().apply {
            put("signal_type", signalType)
            put("sdp", sdp)
        }

        val innerPayload = JSONObject().apply {
            put("to", targetId)
            put("payload", signalPayload.toString())
        }
        val envelope = JSONObject().apply {
            put("type", "relay")
            put("payload", innerPayload)
        }

        Log.i(TAG, "Sending SDP: type=$signalType to=$targetId, sdpLength=${sdp.length}")
        val sent = currentWs.send(envelope.toString())
        if (!sent) {
            Log.e(TAG, "Failed to send SDP!")
        }
    }

    fun sendIceCandidate(targetId: String, sdpMid: String, sdpMLineIndex: Int, candidate: String) {
        val currentWs = ws
        if (currentWs == null) {
            Log.w(TAG, "sendIceCandidate: ws is null")
            return
        }

        val signalPayload = JSONObject().apply {
            put("signal_type", "ice-candidate")
            put("sdp_mid", sdpMid)
            put("sdp_mline_index", sdpMLineIndex)
            put("candidate", candidate)
        }

        val innerPayload = JSONObject().apply {
            put("to", targetId)
            put("payload", signalPayload.toString())
        }
        val envelope = JSONObject().apply {
            put("type", "relay")
            put("payload", innerPayload)
        }

        val sent = currentWs.send(envelope.toString())
        if (!sent) {
            Log.e(TAG, "Failed to send ICE candidate!")
        }
    }

    fun requestDevicesList() {
        val currentWs = ws
        if (currentWs == null) {
            Log.w(TAG, "requestDevicesList: ws is null")
            return
        }
        val request = JSONObject().apply { put("type", "get_devices") }
        currentWs.send(request.toString())
    }

    private fun scheduleReconnect() {
        if (isDestroyed) return
        reconnectJob?.cancel()
        val delayMs = if (reconnectAttempts == 0) 500L else 3000L
        reconnectAttempts++
        Log.i(TAG, "Reconnect in ${delayMs}ms (attempt #$reconnectAttempts)")
        reconnectJob = scope.launch {
            delay(delayMs)
            if (!isDestroyed) {
                connectWebSocket()
            }
        }
    }

    // 【修复9】setState 改为 suspend 函数，内部切主线程回调
    private suspend fun setState(newState: State) {
        stateLock.withLock { state = newState }
        val (status, msg) = when (newState) {
            State.Idle -> ConnectionState.STATUS_IDLE to "未连接"
            State.Connecting -> ConnectionState.STATUS_CONNECTING to "连接中..."
            State.Registered -> ConnectionState.STATUS_REGISTERED to "已注册"
            State.Connected -> ConnectionState.STATUS_CONNECTED to "已连接(WebRTC)"
            is State.Error -> ConnectionState.STATUS_ERROR to newState.msg
        }
        Log.i(TAG, "State -> $newState (peerId=$peerId)")
        // 【修复9】切主线程更新状态，因为外部必然会据此更新 UI
        withContext(Dispatchers.Main) {
            onConnectionStateChange?.invoke(status, msg)
        }
    }

    fun disconnect() {
        Log.i(TAG, "disconnect() called")
        isDestroyed = true
        wsGeneration++
        reconnectJob?.cancel()
        reconnectJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        healthCheckJob?.cancel()
        healthCheckJob = null
        val currentWs = ws
        ws = null
        if (currentWs != null) {
            try { currentWs.close(1000, "disconnect") } catch (_: Exception) {}
        }
        scope.cancel()
    }

    fun resetForReconnect() {
        Log.i(TAG, "resetForReconnect() called")
        wsGeneration++
        reconnectJob?.cancel()
        reconnectJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        healthCheckJob?.cancel()
        healthCheckJob = null
        val currentWs = ws
        ws = null
        if (currentWs != null) {
            try { currentWs.close(1000, "reset") } catch (_: Exception) {}
        }
        peerId = ""
        pendingConnectTargetId = null
        reconnectAttempts = 0
    }

    fun notifyNetworkChanged() {
        if (isDestroyed) return
        Log.i(TAG, "notifyNetworkChanged: Network changed, forcing reconnect...")
        reconnectAttempts = 0
        useFallbackUrl = false
        wsGeneration++
        val currentWs = ws
        ws = null
        if (currentWs != null) {
            try { currentWs.close(1000, "reconnect") } catch (_: Exception) {}
        }
        scope.launch {
            setState(State.Idle)
            delay(500)
            connectWebSocket()
        }
    }
}
