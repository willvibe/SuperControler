package com.yourapp.remotectrl.controller

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.*
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.yourapp.remotectrl.network.ConnectionState
import com.yourapp.remotectrl.webrtc.WebRtcClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

class ControllerActivity : AppCompatActivity() {

    private lateinit var surfaceViewRenderer: SurfaceViewRenderer
    private var signalingClient: com.yourapp.remotectrl.network.SignalingClient? = null
    private lateinit var statusText: TextView

    private var remoteWidth = 1080
    private var remoteHeight = 2400
    private var viewWidth = 0
    private var viewHeight = 0

    private var pointerDownX = 0f
    private var pointerDownY = 0f
    private var pointerDownTime = 0L
    private var isScrolling = false

    private var isConnected = false
    private var targetId: String = ""
    private var pin: String = "123456"

    private var currentVideoTrack: VideoTrack? = null
    private var pendingVideoTrack: VideoTrack? = null
    private var surfaceInitialized = false
    @Volatile private var isSurfaceReady = false

    private var controlBarView: View? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val webRtcClient: WebRtcClient?
        get() = ControllerService.getInstance()?.webRtcClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        }

        targetId = intent.getStringExtra("device_id") ?: run {
            Log.e(TAG, "Missing device_id extra")
            Toast.makeText(this, "错误: 未指定设备ID", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        pin = intent.getStringExtra("pin") ?: "123456"

        val layout = FrameLayout(this)

        surfaceViewRenderer = SurfaceViewRenderer(this)
        layout.addView(surfaceViewRenderer, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        statusText = TextView(this).apply {
            text = "正在连接..."
            textSize = 18f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(180, 0, 0, 0))
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }
        layout.addView(statusText, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))

        controlBarView = buildDraggableControlBar()
        layout.addView(controlBarView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            dp(48),
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply {
            bottomMargin = dp(16)
        })

        setContentView(layout)

        initSurfaceViewRendererEarly()

        surfaceViewRenderer.setOnTouchListener { v, event ->
            viewWidth = v.width
            viewHeight = v.height
            handleTouchEvent(event)
            true
        }

        connectToDevice()
    }

    private fun connectToDevice() {
        Log.i(TAG, "connectToDevice() targetId=$targetId")
        updateStatus("正在连接服务器...")

        // 【修复5】重新连接前，必须彻底释放旧的渲染器
        if (surfaceInitialized) {
            try {
                surfaceViewRenderer.release()
            } catch (e: Exception) {
                Log.w(TAG, "Surface release error: ${e.message}")
            }
        }

        isConnected = false
        surfaceInitialized = false
        isSurfaceReady = false
        surfaceInitAttempts = 0
        currentVideoTrack?.removeSink(surfaceViewRenderer)
        currentVideoTrack = null
        pendingVideoTrack?.removeSink(surfaceViewRenderer)
        pendingVideoTrack = null

        val service = ControllerService.getInstance()
        if (service != null) {
            signalingClient = service.getSignalingClient()
            setupActivityCallbacks(service)
            service.connectToDevice(targetId, pin)
        } else {
            Log.e(TAG, "ControllerService not running, starting it...")
            updateStatus("正在启动服务...")
            ControllerService.start(this)
            lifecycleScope.launch {
                delay(3000)
                val newService = ControllerService.getInstance()
                if (newService != null) {
                    signalingClient = newService.getSignalingClient()
                    setupActivityCallbacks(newService)
                    newService.connectToDevice(targetId, pin)
                } else {
                    updateStatus("服务启动失败")
                    Toast.makeText(this@ControllerActivity, "服务启动失败", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun setupActivityCallbacks(service: ControllerService) {
        service.setActivityCallbacks(
            onStateChange = { status, message ->
                runOnUiThread {
                    Log.i(TAG, "Connection state: $status - $message")
                    when (status) {
                        ConnectionState.STATUS_CONNECTING -> updateStatus("连接中...")
                        ConnectionState.STATUS_REGISTERED -> updateStatus("已注册，等待被控端...")
                        ConnectionState.STATUS_CONNECTED -> {
                            updateStatus("")
                        }
                        ConnectionState.STATUS_ERROR -> updateStatus("连接错误: $message")
                        ConnectionState.STATUS_IDLE -> updateStatus("未连接")
                    }
                }
            },
            onVideoTrack = { videoTrack ->
                runOnUiThread {
                    Log.i(TAG, "Received remote VideoTrack, attaching to SurfaceViewRenderer")
                    currentVideoTrack?.removeSink(surfaceViewRenderer)
                    currentVideoTrack = videoTrack

                    if (isSurfaceReady) {
                        videoTrack.addSink(surfaceViewRenderer)
                        Log.i(TAG, "VideoTrack attached to SurfaceViewRenderer (surface ready)")
                    } else {
                        pendingVideoTrack = videoTrack
                        Log.i(TAG, "Surface was not ready. Forcing surface initialization wake-up!")
                        surfaceInitAttempts = 0
                        tryInitSurface()
                    }

                    isConnected = true
                    updateStatus("")
                }
            },
            onWebRtcConnected = { connected ->
                runOnUiThread {
                    if (connected) {
                        Log.i(TAG, "WebRTC connected")
                        isConnected = true
                    } else {
                        Log.w(TAG, "WebRTC disconnected")
                        isConnected = false
                        updateStatus("WebRTC 连接断开")
                    }
                }
            }
        )

        service.activityScreenInfoCallback = { width, height ->
            runOnUiThread {
                Log.i(TAG, "Received screen info: ${width}x${height}")
                remoteWidth = width
                remoteHeight = height
            }
        }
    }

    private var surfaceInitAttempts = 0

    private fun initSurfaceViewRendererEarly() {
        surfaceInitialized = false
        isSurfaceReady = false
        surfaceInitAttempts = 0
        tryInitSurface()
    }

    private fun tryInitSurface() {
        if (surfaceInitialized) return
        if (surfaceInitAttempts > 50) {
            Log.e(TAG, "Surface init failed after 50 attempts, giving up")
            return
        }
        surfaceInitAttempts++
        // 【修复6】处理安全的可空 EglBaseContext
        val eglCtx = webRtcClient?.eglBaseContext
        if (eglCtx != null) {
            doInitSurface(eglCtx)
            return
        }
        surfaceViewRenderer.postDelayed({ tryInitSurface() }, 300)
    }

    private fun doInitSurface(eglCtx: org.webrtc.EglBase.Context) {
        try {
            surfaceViewRenderer.init(eglCtx, object : RendererCommon.RendererEvents {
                override fun onFirstFrameRendered() {
                    Log.i(TAG, "SurfaceViewRenderer first frame rendered")
                    runOnUiThread { surfaceViewRenderer.setBackgroundColor(android.graphics.Color.TRANSPARENT) }
                }
                override fun onFrameResolutionChanged(w: Int, h: Int, rotation: Int) {
                    Log.i(TAG, "Frame resolution changed: ${w}x${h} rotation=$rotation")
                    runOnUiThread {
                        val actualW = if (rotation % 180 == 0) w else h
                        val actualH = if (rotation % 180 == 0) h else w
                        remoteWidth = actualW
                        remoteHeight = actualH
                    }
                }
            })
            surfaceViewRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            surfaceViewRenderer.setEnableHardwareScaler(true)
            surfaceViewRenderer.setZOrderMediaOverlay(true)
            surfaceInitialized = true
            isSurfaceReady = true
            Log.i(TAG, "SurfaceViewRenderer initialized (surfaceReady=true)")

            val track = pendingVideoTrack
            if (track != null) {
                Log.i(TAG, "Pending VideoTrack found during init, attaching now")
                track.addSink(surfaceViewRenderer)
                pendingVideoTrack = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "SurfaceViewRenderer init failed: ${e.message}")
        }
    }

    private fun updateStatus(message: String) {
        runOnUiThread {
            if (message.isEmpty()) {
                statusText.visibility = View.GONE
            } else {
                statusText.visibility = View.VISIBLE
                statusText.text = message
            }
        }
    }

    private fun handleTouchEvent(event: MotionEvent) {
        if (viewWidth <= 0 || viewHeight <= 0) return

        val transform = calcLetterboxTransform() ?: return
        val (displayW, displayH, offsetX, offsetY) = transform
        if (displayW <= 0f || displayH <= 0f) return

        val relX = ((event.x - offsetX) / displayW).coerceIn(0f, 1f)
        val relY = ((event.y - offsetY) / displayH).coerceIn(0f, 1f)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerDownX = relX
                pointerDownY = relY
                pointerDownTime = SystemClock.elapsedRealtime()
                isScrolling = false
            }
            MotionEvent.ACTION_MOVE -> {
                val distX = kotlin.math.abs(relX - pointerDownX)
                val distY = kotlin.math.abs(relY - pointerDownY)
                if (!isScrolling && (distX > 0.01f || distY > 0.01f)) {
                    isScrolling = true
                }
                if (isScrolling) {
                    sendControlEventJson(newScrollEventJson(relX, relY, relX - pointerDownX, relY - pointerDownY))
                    pointerDownX = relX
                    pointerDownY = relY
                }
            }
            MotionEvent.ACTION_UP -> {
                val duration = SystemClock.elapsedRealtime() - pointerDownTime
                val distX = kotlin.math.abs(relX - pointerDownX)
                val distY = kotlin.math.abs(relY - pointerDownY)

                if (!isScrolling && distX < 0.008f && distY < 0.008f) {
                    if (duration > 500) {
                        sendControlEventJson(newLongPressEventJson(pointerDownX, pointerDownY))
                    } else {
                        sendControlEventJson(newTapEventJson(pointerDownX, pointerDownY))
                    }
                }
                isScrolling = false
            }
        }
    }

    private data class LetterboxTransform(val displayW: Float, val displayH: Float, val offsetX: Float, val offsetY: Float)

    private fun calcLetterboxTransform(): LetterboxTransform? {
        if (viewWidth <= 0 || viewHeight <= 0 || remoteWidth <= 0 || remoteHeight <= 0) return null

        val remoteAspect = remoteWidth.toFloat() / remoteHeight.toFloat()
        val viewAspect = viewWidth.toFloat() / viewHeight.toFloat()

        return if (viewAspect > remoteAspect) {
            val displayH = viewHeight.toFloat()
            val displayW = displayH * remoteAspect
            val offsetX = (viewWidth - displayW) / 2f
            LetterboxTransform(displayW, displayH, offsetX, 0f)
        } else {
            val displayW = viewWidth.toFloat()
            val displayH = displayW / remoteAspect
            val offsetY = (viewHeight - displayH) / 2f
            LetterboxTransform(displayW, displayH, 0f, offsetY)
        }
    }

    private fun sendControlEventJson(eventJson: String) {
        val client = webRtcClient
        if (client == null) {
            Log.w(TAG, "sendControlEventJson: webRtcClient is null")
            return
        }
        client.sendControlEvent(eventJson)
    }

    private fun newTapEventJson(x: Float, y: Float): String {
        return JSONObject().apply {
            put("type", "TAP")
            put("x", x)
            put("y", y)
        }.toString()
    }

    private fun newLongPressEventJson(x: Float, y: Float): String {
        return JSONObject().apply {
            put("type", "LONG_PRESS")
            put("x", x)
            put("y", y)
        }.toString()
    }

    private fun newScrollEventJson(x: Float, y: Float, dx: Float, dy: Float): String {
        return JSONObject().apply {
            put("type", "SCROLL")
            put("x", x)
            put("y", y)
            put("dx", dx)
            put("dy", dy)
        }.toString()
    }

    private fun newKeyEventJson(keyCode: Int): String {
        return JSONObject().apply {
            put("type", "KEY")
            put("key_code", keyCode)
        }.toString()
    }

    private fun buildDraggableControlBar(): View {
        val container = FrameLayout(this)
        container.setBackgroundColor(Color.argb(160, 40, 40, 40))

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(48), 0, dp(16), 0)
        }

        buttonRow.addIconButton("◀") { sendControlEventJson(newKeyEventJson(android.view.KeyEvent.KEYCODE_BACK)) }
        buttonRow.addIconButton("⌂") { sendControlEventJson(newKeyEventJson(android.view.KeyEvent.KEYCODE_HOME)) }
        buttonRow.addIconButton("▣") { sendControlEventJson(newKeyEventJson(android.view.KeyEvent.KEYCODE_APP_SWITCH)) }
        buttonRow.addIconButton("✕") { disconnectAndFinish() }

        container.addView(buttonRow, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
            Gravity.CENTER
        ))

        val dragHandle = TextView(this).apply {
            text = "⋮⋮"
            textSize = 16f
            setTextColor(Color.argb(200, 255, 255, 255))
            gravity = Gravity.CENTER
        }
        container.addView(dragHandle, FrameLayout.LayoutParams(
            dp(48),
            FrameLayout.LayoutParams.MATCH_PARENT,
            Gravity.START or Gravity.CENTER_VERTICAL
        ))

        setupDragBehavior(container)

        return container
    }

    private fun LinearLayout.addIconButton(label: String, onClick: () -> Unit) {
        addView(TextView(this@ControllerActivity).apply {
            text = label
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { onClick() }
        })
    }

    private fun setupDragBehavior(view: View) {
        var dX = 0f
        var dY = 0f
        var startX = 0f
        var startY = 0f
        var isDragging = false

        view.setOnTouchListener { v, event ->
            val parent = v.parent as? FrameLayout ?: return@setOnTouchListener false

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dX = v.x - event.rawX
                    dY = v.y - event.rawY
                    startX = event.rawX
                    startY = event.rawY
                    isDragging = false
                    v.parent.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!isDragging) {
                        val moveDist = kotlin.math.hypot(event.rawX - startX, event.rawY - startY)
                        if (moveDist > 10f) {
                            isDragging = true
                        }
                    }

                    if (isDragging) {
                        val newX = (event.rawX + dX).coerceIn(0f, (parent.width - v.width).toFloat().coerceAtLeast(0f))
                        val newY = (event.rawY + dY).coerceIn(0f, (parent.height - v.height).toFloat().coerceAtLeast(0f))
                        v.x = newX
                        v.y = newY
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent.requestDisallowInterceptTouchEvent(false)
                    if (!isDragging && event.actionMasked == MotionEvent.ACTION_UP) {
                        v.performClick()
                    }
                    isDragging = false
                    true
                }
                else -> false
            }
        }
    }

    private fun disconnectAndFinish() {
        Log.i(TAG, "User requested disconnect and exit")
        val service = ControllerService.getInstance() ?: run {
            finish()
            return
        }

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    Log.i(TAG, "Sending disconnect message to peer...")
                    service.webRtcClient?.disconnectPeer()
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending disconnect message: ${e.message}")
                }

                try {
                    service.webRtcClient?.dispose()
                    service.webRtcClient = null
                    Log.i(TAG, "WebRTC disposed successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Error disposing WebRTC: ${e.message}")
                }

                try {
                    service.getSignalingClient()?.resetForReconnect()
                    Log.i(TAG, "Signaling client reset for reconnect")
                } catch (e: Exception) {
                    Log.e(TAG, "Error resetting signaling: ${e.message}")
                }

                service.disconnectWebRtcAndReset()
                service.setActivityCallbacks(null, null, null)
            }
            finish()
        }
    }

    private fun dp(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        surfaceViewRenderer.setFpsReduction(Float.MAX_VALUE)
    }

    override fun onPause() {
        super.onPause()
        surfaceViewRenderer.setFpsReduction(1f)
    }

    override fun onDestroy() {
        super.onDestroy()

        pendingVideoTrack?.removeSink(surfaceViewRenderer)
        pendingVideoTrack = null
        currentVideoTrack?.removeSink(surfaceViewRenderer)
        currentVideoTrack = null
        if (surfaceInitialized) {
            try {
                surfaceViewRenderer.release()
            } catch (e: Exception) {
                Log.w(TAG, "SurfaceViewRenderer release error: ${e.message}")
            }
        }

        val service = ControllerService.getInstance()
        if (service != null && isFinishing) {
            service.setActivityCallbacks(null, null, null)
            service.activityScreenInfoCallback = null

            lifecycleScope.launch(Dispatchers.IO) {
                Log.i(TAG, "ControllerActivity destroyed, disconnecting WebRTC and signaling")
                service.disconnectWebRtcAndReset()
                service.getSignalingClient()?.resetForReconnect()
            }
        }

        serviceScope.cancel()
    }

    companion object {
        private const val TAG = "ControllerActivity"
    }
}
