package com.yourapp.remotectrl.controller

import android.graphics.Color
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

    private val webRtcClient: WebRtcClient?
        get() = ControllerService.getInstance()?.webRtcClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

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

        val controlBar = buildControlBar()
        layout.addView(controlBar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            dp(56),
            Gravity.BOTTOM
        ))

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
        tryInitSurface()
    }

    private fun tryInitSurface() {
        if (surfaceInitialized) return
        if (surfaceInitAttempts > 50) {
            Log.e(TAG, "Surface init failed after 50 attempts, giving up")
            return
        }
        surfaceInitAttempts++
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
                }
                override fun onFrameResolutionChanged(w: Int, h: Int, rotation: Int) {
                    Log.i(TAG, "Frame resolution changed: ${w}x${h} rotation=$rotation")
                }
            })
            surfaceViewRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            surfaceViewRenderer.setEnableHardwareScaler(true)
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

    private fun buildControlBar(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.argb(180, 0, 0, 0))
            gravity = Gravity.CENTER

            addIconButton("◀") { sendControlEventJson(newKeyEventJson(android.view.KeyEvent.KEYCODE_BACK)) }
            addIconButton("⌂") { sendControlEventJson(newKeyEventJson(android.view.KeyEvent.KEYCODE_HOME)) }
            addIconButton("▣") { sendControlEventJson(newKeyEventJson(android.view.KeyEvent.KEYCODE_APP_SWITCH)) }
        }
    }

    private fun LinearLayout.addIconButton(label: String, onClick: () -> Unit) {
        addView(TextView(this@ControllerActivity).apply {
            text = label
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener { onClick() }
        })
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
    }

    companion object {
        private const val TAG = "ControllerActivity"
    }
}
