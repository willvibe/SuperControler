package com.yourapp.supercontroler

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import androidx.core.view.WindowCompat
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.yourapp.remotectrl.controlled.ControlledService
import com.yourapp.remotectrl.controller.ControllerActivity
import com.yourapp.remotectrl.controller.ControllerService
import com.yourapp.remotectrl.network.ConnectionState
import com.yourapp.remotectrl.network.SignalingClient
import com.yourapp.remotectrl.controlled.MediaProjectionActivity
import com.yourapp.remotectrl.controlled.MediaProjectionHelper
import com.yourapp.remotectrl.root.DeviceIdManager
import com.yourapp.remotectrl.root.LogExporter
import com.yourapp.remotectrl.root.RootManager
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "app_config"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_MODE = "app_mode"
        private const val KEY_DEVICES = "saved_devices"
        private const val MODE_CONTROLLED = "controlled"
        private const val MODE_CONTROLLER = "controller"
        private const val PERMISSION_REQUEST = 100

        private const val COLOR_BG = "#F2F2F7"
        private const val COLOR_CARD = "#FFFFFF"
        private const val COLOR_TEXT_PRIMARY = "#000000"
        private const val COLOR_TEXT_SECONDARY = "#8E8E93"
        private const val COLOR_BLUE = "#007AFF"
        private const val COLOR_RED = "#FF3B30"
        private const val COLOR_DIVIDER = "#E5E5EA"
    }

    private lateinit var settingsButton: TextView
    private lateinit var serviceStatusText: TextView
    private lateinit var rootStatusText: TextView
    private lateinit var deviceIdText: TextView
    private lateinit var connectionStatusText: TextView
    private lateinit var serverUrlText: TextView
    private lateinit var logScrollView: ScrollView
    private lateinit var logText: TextView
    private lateinit var devicesContainer: LinearLayout
    private lateinit var requestRootButton: TextView
    private lateinit var exportLogButton: TextView
    private lateinit var projectionStatusText: TextView
    private lateinit var requestProjectionButton: TextView
    private lateinit var modeStatusText: TextView

    private var currentMode = MODE_CONTROLLED
    private var isServiceRunning = false
    private val logList = java.util.LinkedList<String>()
    private val MAX_LOG_LINES = 200
    private val onlineDevices = mutableMapOf<String, SignalingClient.OnlineDevice>()
    private lateinit var devicesRefreshButton: TextView

    private val devicesBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "com.yourapp.supercontroler.DEVICES_UPDATE") {
                val jsonStr = intent.getStringExtra("devices_json") ?: return
                try {
                    val jsonArray = JSONArray(jsonStr)
                    onlineDevices.clear()
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        val device = SignalingClient.OnlineDevice(
                            id = obj.getString("id"),
                            name = obj.getString("name"),
                            publicIp = obj.getString("publicIp"),
                            isOnline = obj.getBoolean("isOnline")
                        )
                        onlineDevices[device.id] = device
                    }
                    Log.i("MainActivity", "Received devices broadcast: ${onlineDevices.size} devices")
                    runOnUiThread {
                        if (currentMode == MODE_CONTROLLER) {
                            refreshDevicesListUI()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Failed to parse devices broadcast: ${e.message}")
                }
            }
        }
    }

    private val connectionCallback: (String, String, String) -> Unit = { owner, status, message ->
        val relevantOwner = if (currentMode == MODE_CONTROLLED) "controlled" else "controller"
        if (owner == relevantOwner) {
            runOnUiThread {
                updateInfoRow(connectionStatusText, "连接状态:", message.ifEmpty { "未知" })
                when (status) {
                    ConnectionState.STATUS_ERROR -> {
                        appendLog("❌ 连接错误: $message")
                    }
                    ConnectionState.STATUS_IDLE -> {
                        appendLog("⚠ 连接断开: $message")
                    }
                    ConnectionState.STATUS_CONNECTED -> {
                        appendLog("✓ $message")
                    }
                    else -> {
                        appendLog("连接状态: $message")
                    }
                }
                updateServiceStatusFromConnection(status)
            }
        }
    }

    private fun updateServiceStatusFromConnection(status: String) {
        val owner = if (currentMode == MODE_CONTROLLED) "controlled" else "controller"
        when (status) {
            ConnectionState.STATUS_IDLE -> {
                if (ControlledService.isRunning() || ControllerService.getInstance() != null) {
                    isServiceRunning = true
                    updateInfoRow(serviceStatusText, "服务状态:", "运行中(未连接)")
                } else {
                    isServiceRunning = false
                    updateInfoRow(serviceStatusText, "服务状态:", "未启动")
                }
            }
            ConnectionState.STATUS_CONNECTING -> {
                isServiceRunning = true
                updateInfoRow(serviceStatusText, "服务状态:", "连接中...")
            }
            ConnectionState.STATUS_REGISTERED -> {
                isServiceRunning = true
                updateInfoRow(serviceStatusText, "服务状态:", "运行中(已注册)")
            }
            ConnectionState.STATUS_CONNECTED -> {
                isServiceRunning = true
                updateInfoRow(serviceStatusText, "服务状态:", "运行中(已连接)")
            }
            ConnectionState.STATUS_ERROR -> {
                isServiceRunning = true
                val errMsg = ConnectionState.getMessageForOwner(owner)
                updateInfoRow(serviceStatusText, "服务状态:", "连接错误: $errMsg")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ConnectionState.onOwnerStatusChange = connectionCallback
        loadConfig()
        buildUI()
        checkPermissions()
        initDevice()
        checkServiceRunning()
        setupDevicesCallback()
        registerDevicesBroadcastReceiver()

        if (currentMode == MODE_CONTROLLED) {
            autoStartControlledServiceIfNeeded()
        } else {
            autoStartControllerServiceIfNeeded()
        }
    }

    private fun autoStartControlledServiceIfNeeded() {
        if (com.yourapp.remotectrl.controlled.ControlledService.getInstance() != null) {
            Log.i("MainActivity", "ControlledService already running")
            isServiceRunning = true
            updateInfoRow(serviceStatusText, "服务状态:", "运行中")
            return
        }
        Log.i("MainActivity", "Auto-starting ControlledService")
        startControlledService()
    }

    private fun autoStartControllerServiceIfNeeded() {
        if (ControllerService.getInstance() != null) {
            Log.i("MainActivity", "ControllerService already running")
            isServiceRunning = true
            return
        }
        Log.i("MainActivity", "Auto-starting ControllerService")
        startControllerService()
    }

    override fun onStart() {
        super.onStart()
        initRoot()
    }

    private fun registerDevicesBroadcastReceiver() {
        val filter = IntentFilter("com.yourapp.supercontroler.DEVICES_UPDATE")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(devicesBroadcastReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(devicesBroadcastReceiver, filter)
        }
    }

    private fun setupDevicesCallback() {
        val svc = ControllerService.getInstance()
        if (svc == null) {
            Log.w("MainActivity", "setupDevicesCallback: ControllerService not running yet, will retry")
            Handler(Looper.getMainLooper()).postDelayed({
                if (ControllerService.getInstance() != null) {
                    setupDevicesCallback()
                }
            }, 2000)
            return
        }
        svc.onDevicesUpdate = { devices ->
            runOnUiThread {
                onlineDevices.clear()
                onlineDevices.putAll(devices)
                appendLog("收到设备在线状态更新: ${devices.size} 个设备在线")
                if (currentMode == MODE_CONTROLLER) {
                    refreshDevicesListUI()
                }
            }
        }
        val currentDevices = svc.getOnlineDevices()
        Log.i("MainActivity", "setupDevicesCallback: current service devices=${currentDevices.size}")
        if (currentDevices.isNotEmpty()) {
            onlineDevices.clear()
            onlineDevices.putAll(currentDevices)
            if (currentMode == MODE_CONTROLLER) {
                refreshDevicesListUI()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ConnectionState.onOwnerStatusChange = connectionCallback
        setupDevicesCallback()
        syncUIFromConnectionState()
        RootManager.onRootStatusChanged = { hasRoot ->
            runOnUiThread {
                updateRootStatusUI(hasRoot)
            }
        }
    }

    private fun syncUIFromConnectionState() {
        val owner = if (currentMode == MODE_CONTROLLED) "controlled" else "controller"
        val status = ConnectionState.getStatusForOwner(owner)
        val msg = ConnectionState.getMessageForOwner(owner)
        Log.i("MainActivity", "syncUIFromConnectionState: owner=$owner, status=$status, msg=$msg")
        runOnUiThread {
            updateInfoRow(connectionStatusText, "连接状态:", msg.ifEmpty { "未连接" })
            updateServiceStatusFromConnection(status)
            checkServiceRunning()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ConnectionState.onOwnerStatusChange = null
        ConnectionState.onStatusChange = null
        RootManager.onRootStatusChanged = null
        try { unregisterReceiver(devicesBroadcastReceiver) } catch (_: Exception) {}
    }

    private fun loadConfig() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        currentMode = prefs.getString(KEY_MODE, MODE_CONTROLLED) ?: MODE_CONTROLLED

        val turnUrl = prefs.getString("turn_url", "") ?: ""
        val turnUser = prefs.getString("turn_user", "") ?: ""
        val turnPass = prefs.getString("turn_pass", "") ?: ""
        com.yourapp.remotectrl.webrtc.IceConfig.turnServers = if (turnUrl.isNotEmpty()) {
            listOf(com.yourapp.remotectrl.webrtc.IceConfig.TurnServer(turnUrl, turnUser, turnPass))
        } else {
            emptyList()
        }
    }

    private fun saveMode(mode: String) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODE, mode).apply()
        currentMode = mode
    }

    fun getServerUrl(): String {
        return com.yourapp.remotectrl.network.ServerConfig.getServerUrl(this)
    }

    private fun saveServerUrl(url: String) {
        com.yourapp.remotectrl.network.ServerConfig.setServerUrl(this, url)
        updateServerUrlDisplay()
        appendLog("服务器地址已更新: $url")
    }

    // ==========================================
    // UI 构建逻辑 (iOS 风格)
    // ==========================================

    private fun buildUI() {
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(COLOR_BG))
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1C1C1E"))
            setPadding(dp(20), dp(16), dp(12), dp(12))
        }
        val titleText = TextView(this).apply {
            text = "SuperControler"
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        settingsButton = TextView(this).apply {
            text = "⚙"
            textSize = 22f
            setTextColor(Color.parseColor("#AAAAAA"))
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setOnClickListener { showSettingsDialog() }
        }
        topBar.addView(titleText)
        topBar.addView(settingsButton)
        rootLayout.addView(topBar)

        val scrollView = ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val contentView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(40))
        }
        scrollView.addView(contentView)
        rootLayout.addView(scrollView)

        contentView.addView(createModeToggle())

        val infoCard = createCardContainer()

        deviceIdText = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.parseColor(COLOR_TEXT_SECONDARY))
            gravity = Gravity.END
        }
        val copyButton = createIOSButton("复制", Color.parseColor(COLOR_BLUE)) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Device ID", DeviceIdManager.getDeviceId(this@MainActivity))
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this@MainActivity, "设备 ID 已复制", Toast.LENGTH_SHORT).show()
        }
        infoCard.addView(createRow("设备 ID", deviceIdText, copyButton, true))

        serverUrlText = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.parseColor(COLOR_TEXT_SECONDARY))
            gravity = Gravity.END
        }
        infoCard.addView(createRow("服务器", serverUrlText, null, true))

        connectionStatusText = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.parseColor(COLOR_TEXT_SECONDARY))
            gravity = Gravity.END
        }
        infoCard.addView(createRow("连接状态", connectionStatusText, null, true))

        serviceStatusText = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.parseColor(COLOR_TEXT_SECONDARY))
            gravity = Gravity.END
        }
        infoCard.addView(createRow("服务状态", serviceStatusText, null, true))

        rootStatusText = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.parseColor(COLOR_TEXT_SECONDARY))
            gravity = Gravity.END
        }
        requestRootButton = createIOSButton("请求Root", Color.parseColor(COLOR_BLUE)) { onRequestRootClick() }.apply { visibility = View.GONE }
        infoCard.addView(createRow("Root权限", rootStatusText, requestRootButton, true))

        projectionStatusText = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.parseColor(COLOR_TEXT_SECONDARY))
            gravity = Gravity.END
        }
        requestProjectionButton = createIOSButton("请求录制", Color.parseColor(COLOR_BLUE)) { onRequestProjectionClick() }.apply { visibility = View.GONE }
        infoCard.addView(createRow("屏幕录制", projectionStatusText, requestProjectionButton, true))

        exportLogButton = createIOSButton("导出", Color.parseColor(COLOR_BLUE)) { onExportLogClick() }
        val logLabelValue = TextView(this).apply {
            text = ""
            textSize = 16f
            setTextColor(Color.parseColor(COLOR_TEXT_SECONDARY))
            gravity = Gravity.END
        }
        infoCard.addView(createRow("运行日志", logLabelValue, exportLogButton, false))

        contentView.addView(createSectionHeader("设备信息与状态"))
        contentView.addView(infoCard)

        if (currentMode == MODE_CONTROLLER) {
            contentView.addView(createDevicesSection())
        }

        contentView.addView(createSectionHeader("实时日志"))
        val logCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getRoundRect(Color.parseColor("#1C1C1E"), 12f)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(250)).apply {
                setMargins(dp(16), 0, dp(16), 0)
            }
        }
        logScrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        logText = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#34C759"))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setLineSpacing(dp(4).toFloat(), 1.0f)
        }
        logScrollView.addView(logText)
        logCard.addView(logScrollView)
        contentView.addView(logCard)

        setContentView(rootLayout)
        updateModeUI()
    }

    private fun createModeToggle(): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        modeStatusText = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.parseColor(COLOR_TEXT_SECONDARY))
            gravity = Gravity.END
        }
        val modeButton = createIOSButton("切换", Color.parseColor(COLOR_BLUE)) { showModeSwitchDialog() }
        val modeCard = createCardContainer()
        modeCard.addView(createRow("当前模式", modeStatusText, modeButton, false))

        container.addView(createSectionHeader("运行模式"))
        container.addView(modeCard)
        return container
    }

    private fun createDevicesSection(): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(8))
        }
        val sectionTitle = TextView(this).apply {
            text = "在线设备"
            textSize = 13f
            isAllCaps = true
            setTextColor(Color.parseColor(COLOR_TEXT_SECONDARY))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerLayout.addView(sectionTitle)

        devicesRefreshButton = createIOSButton("刷新", Color.parseColor(COLOR_BLUE)) {
            val svc = ControllerService.getInstance()
            if (svc != null) {
                svc.refreshDevicesList()
                appendLog("正在刷新设备列表...")
                Handler(Looper.getMainLooper()).postDelayed({
                    val currentDevices = svc.getOnlineDevices()
                    Log.i("MainActivity", "Refresh: got ${currentDevices.size} devices from service")
                    if (currentDevices.isNotEmpty()) {
                        onlineDevices.clear()
                        onlineDevices.putAll(currentDevices)
                        refreshDevicesListUI()
                        appendLog("✓ 已刷新 ${currentDevices.size} 个在线设备")
                    } else {
                        appendLog("⚠ 暂无在线设备")
                        refreshDevicesListUI()
                    }
                }, 3000)
            } else {
                appendLog("⚠ 主控服务未运行，无法刷新设备列表")
                startControllerService()
            }
        }.apply { setPadding(0, 0, dp(16), 0) }

        val addButton = createIOSButton("添加", Color.parseColor(COLOR_BLUE)) { showAddDeviceDialog() }

        headerLayout.addView(devicesRefreshButton)
        headerLayout.addView(addButton)
        container.addView(headerLayout)

        devicesContainer = createCardContainer()
        container.addView(devicesContainer)

        loadSavedDevices()

        return container
    }

    // ==========================================
    // iOS 风格 UI 辅助方法
    // ==========================================

    private fun createSectionHeader(title: String): TextView {
        return TextView(this).apply {
            text = title
            textSize = 13f
            isAllCaps = true
            setTextColor(Color.parseColor(COLOR_TEXT_SECONDARY))
            setPadding(dp(20), dp(24), dp(20), dp(8))
        }
    }

    private fun createCardContainer(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getRoundRect(Color.parseColor(COLOR_CARD), 12f)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(16), 0, dp(16), 0)
            }
            elevation = dp(2).toFloat()
        }
    }

    private fun createRow(labelStr: String, valueView: TextView, actionView: View?, showDivider: Boolean): LinearLayout {
        val wrapper = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(50)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }

        val label = TextView(this).apply {
            text = labelStr
            textSize = 16f
            setTextColor(Color.parseColor(COLOR_TEXT_PRIMARY))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        row.addView(label)

        val space = View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 0, 1f) }
        row.addView(space)

        valueView.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(16), 0, if (actionView != null) dp(12) else 0, 0)
            }
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxLines = 1
        }
        row.addView(valueView)

        if (actionView != null) {
            row.addView(actionView)
        }

        wrapper.addView(row)

        if (showDivider) {
            wrapper.addView(createDivider())
        }
        return wrapper
    }

    private fun createIOSButton(text: String, color: Int, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(color)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            val typedValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)
            setBackgroundResource(typedValue.resourceId)
            setOnClickListener { onClick() }
        }
    }

    private fun createDivider(): View {
        return View(this).apply {
            setBackgroundColor(Color.parseColor(COLOR_DIVIDER))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                setMargins(dp(16), 0, 0, 0)
            }
        }
    }

    private fun getRoundRect(color: Int, radiusDp: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp.toInt()).toFloat()
        }
    }

    private fun createCard(): LinearLayout {
        return createCardContainer()
    }

    private fun createInfoRow(label: String, value: String): TextView {
        return TextView(this).apply {
            text = value
            textSize = 16f
            setTextColor(Color.parseColor(COLOR_TEXT_SECONDARY))
            gravity = Gravity.END
        }
    }

    private fun updateInfoRow(textView: TextView, label: String, value: String) {
        textView.text = value
    }

    private fun createSpacer(height: Int): View {
        return View(this).apply { setPadding(0, height, 0, 0) }
    }

    // ==========================================
    // UI 交互与逻辑回调
    // ==========================================

    private fun updateModeUI() {
        modeStatusText.text = if (currentMode == MODE_CONTROLLED) "被控端" else "主控端"
        updateServerUrlDisplay()
    }

    private fun updateServerUrlDisplay() {
        updateInfoRow(serverUrlText, "服务器:", getServerUrl())
    }

    private fun showModeSwitchDialog() {
        val modes = arrayOf("被控端（等待被控制）", "主控端（控制其他设备）")
        val currentIndex = if (currentMode == MODE_CONTROLLED) 0 else 1

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            .setTitle("选择运行模式")
            .setSingleChoiceItems(modes, currentIndex) { dialog, which ->
                val newMode = if (which == 0) MODE_CONTROLLED else MODE_CONTROLLER
                if (newMode != currentMode) {
                    saveMode(newMode)
                    rebuildUI()
                    appendLog("模式已切换为: ${if (newMode == MODE_CONTROLLED) "被控端" else "主控端"}")
                    if (newMode == MODE_CONTROLLED) startControlledService()
                    else startControllerService()
                }
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun rebuildUI() {
        setContentView(View(this))
        buildUI()
    }

    private fun showSettingsDialog() {
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }

        val urlInput = EditText(this).apply {
            hint = "服务器 WebSocket URL"
            setText(getServerUrl())
            background = getRoundRect(Color.parseColor(COLOR_BG), 8f)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(16)
            }
        }
        dialogView.addView(urlInput)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val autoStartCheck = CheckBox(this).apply {
            text = "开机自动启动服务"
            isChecked = prefs.getBoolean("auto_start", true)
            setTextColor(Color.parseColor(COLOR_TEXT_PRIMARY))
        }
        dialogView.addView(autoStartCheck)

        val turnUrlInput = EditText(this).apply {
            hint = "TURN 服务器 URL (如: turn:1.2.3.4:3478)"
            setText(prefs.getString("turn_url", ""))
            background = getRoundRect(Color.parseColor(COLOR_BG), 8f)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(16)
                bottomMargin = dp(8)
            }
        }
        dialogView.addView(turnUrlInput)

        val turnUserInput = EditText(this).apply {
            hint = "TURN 用户名"
            setText(prefs.getString("turn_user", ""))
            background = getRoundRect(Color.parseColor(COLOR_BG), 8f)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        }
        dialogView.addView(turnUserInput)

        val turnPassInput = EditText(this).apply {
            hint = "TURN 密码"
            setText(prefs.getString("turn_pass", ""))
            background = getRoundRect(Color.parseColor(COLOR_BG), 8f)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        }
        dialogView.addView(turnPassInput)

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            .setTitle("服务器设置")
            .setView(dialogView)
            .setPositiveButton("保存") { _, _ ->
                val url = urlInput.text.toString().trim()
                if (url.isNotEmpty()) {
                    saveServerUrl(url)
                }
                prefs.edit().putBoolean("auto_start", autoStartCheck.isChecked).apply()

                val turnUrl = turnUrlInput.text.toString().trim()
                val turnUser = turnUserInput.text.toString().trim()
                val turnPass = turnPassInput.text.toString().trim()
                prefs.edit()
                    .putString("turn_url", turnUrl)
                    .putString("turn_user", turnUser)
                    .putString("turn_pass", turnPass)
                    .apply()

                com.yourapp.remotectrl.webrtc.IceConfig.turnServers = if (turnUrl.isNotEmpty()) {
                    listOf(com.yourapp.remotectrl.webrtc.IceConfig.TurnServer(turnUrl, turnUser, turnPass))
                } else {
                    emptyList()
                }

                appendLog("自动启动: ${if (autoStartCheck.isChecked) "已开启" else "已关闭"}")
                appendLog("TURN 服务器: ${if (turnUrl.isNotEmpty()) turnUrl else "未配置"}")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAddDeviceDialog() {
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }

        val nameInput = EditText(this).apply {
            hint = "设备名称 (如: 我的手机)"
            background = getRoundRect(Color.parseColor(COLOR_BG), 8f)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            }
        }
        dialogView.addView(nameInput)

        val idInput = EditText(this).apply {
            hint = "设备 ID"
            background = getRoundRect(Color.parseColor(COLOR_BG), 8f)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        dialogView.addView(idInput)

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            .setTitle("添加设备")
            .setView(dialogView)
            .setPositiveButton("保存") { _, _ ->
                val name = nameInput.text.toString().trim()
                val id = idInput.text.toString().trim()
                if (name.isNotEmpty() && id.isNotEmpty()) {
                    saveDevice(name, id)
                } else {
                    appendLog("错误: 设备名称和ID不能为空")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun saveDevice(name: String, id: String) {
        val normalizedId = id.trim().uppercase()
        val devices = getSavedDevices()
        devices.put(name, normalizedId)
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_DEVICES, devices.toString()).apply()
        refreshDevicesListUI()
        appendLog("设备已添加: $name ($normalizedId)")
    }

    private fun getSavedDevices(): JSONObject {
        val json = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DEVICES, "{}") ?: "{}"
        return try { JSONObject(json) } catch (e: Exception) { JSONObject() }
    }

    private fun loadSavedDevices() {
        if (!::devicesContainer.isInitialized) return
        refreshDevicesListUI()
    }

    private fun addDeviceRow(name: String, id: String, isSaved: Boolean = true, isLast: Boolean = false) {
        val normalizedId = id.trim().uppercase()
        val isOnline = onlineDevices.keys.any { it.trim().uppercase() == normalizedId }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(60)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }

        val onlineIndicator = View(this).apply {
            background = getRoundRect(
                if (isOnline) Color.parseColor("#34C759") else Color.parseColor(COLOR_TEXT_SECONDARY),
                5f
            )
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply {
                setMargins(0, 0, dp(12), 0)
            }
        }

        val infoLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        infoLayout.addView(TextView(this).apply {
            text = name
            textSize = 16f
            setTextColor(Color.parseColor(COLOR_TEXT_PRIMARY))
        })
        infoLayout.addView(TextView(this).apply {
            text = "ID: $id"
            textSize = 12f
            setTextColor(Color.parseColor(COLOR_TEXT_SECONDARY))
        })

        row.addView(onlineIndicator)
        row.addView(infoLayout)

        val connectBtn = createIOSButton(
            if (isOnline) "连接" else "离线",
            if (isOnline) Color.parseColor(COLOR_BLUE) else Color.parseColor(COLOR_TEXT_SECONDARY)
        ) {
            if (isOnline) connectToDevice(id)
        }.apply { isEnabled = isOnline }
        row.addView(connectBtn)

        if (isSaved) {
            val deleteBtn = createIOSButton("删除", Color.parseColor(COLOR_RED)) {
                deleteDevice(name, id)
            }.apply { setPadding(dp(12), dp(4), 0, dp(4)) }
            row.addView(deleteBtn)
        }

        devicesContainer.addView(row)

        if (!isLast) {
            devicesContainer.addView(createDivider())
        }
    }

    private fun refreshDevicesListUI() {
        if (!::devicesContainer.isInitialized) return
        devicesContainer.removeAllViews()

        val savedDevices = getSavedDevices()
        val allOnlineDevices = onlineDevices.toMap()

        if (savedDevices.length() == 0 && allOnlineDevices.isEmpty()) {
            val emptyText = TextView(this).apply {
                text = "暂无设备"
                setTextColor(Color.parseColor(COLOR_TEXT_SECONDARY))
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(0, dp(24), 0, dp(24))
            }
            devicesContainer.addView(emptyText)
            return
        }

        val displayList = mutableListOf<Pair<String, String>>()
        val names = savedDevices.names()
        if (names != null) {
            for (i in 0 until names.length()) {
                val name = names.getString(i)
                displayList.add(Pair(name, savedDevices.getString(name)))
            }
        }
        for ((deviceId, device) in allOnlineDevices) {
            if (!displayList.any { it.second.trim().uppercase() == deviceId.trim().uppercase() }) {
                displayList.add(Pair(device.name.ifEmpty { "未知设备" }, deviceId))
            }
        }

        displayList.forEachIndexed { index, pair ->
            addDeviceRow(pair.first, pair.second, savedDevices.has(pair.first), index == displayList.size - 1)
        }
    }

    private fun findSavedDeviceName(savedDevices: org.json.JSONObject, deviceId: String): String? {
        val normalizedId = deviceId.trim().uppercase()
        val names = savedDevices.names() ?: return null
        for (i in 0 until names.length()) {
            val name = names.getString(i)
            val id = savedDevices.getString(name)
            if (id.trim().uppercase() == normalizedId) {
                return name
            }
        }
        return null
    }

    private fun deleteDevice(name: String, id: String) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            .setTitle("删除设备")
            .setMessage("确定要删除设备 \"$name\" 吗？")
            .setPositiveButton("删除") { _, _ ->
                val devices = getSavedDevices()
                devices.remove(name)
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putString(KEY_DEVICES, devices.toString()).apply()
                refreshDevicesListUI()
                appendLog("设备已删除: $name")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun connectToDevice(targetId: String) {
        val normalizedId = targetId.trim().uppercase()
        appendLog("正在连接设备: $normalizedId")

        if (ControllerService.getInstance() == null) {
            startControllerService()
        }

        val intent = Intent(this, ControllerActivity::class.java).apply {
            putExtra("device_id", normalizedId)
            putExtra("pin", "123456")
        }
        startActivity(intent)
        appendLog("已启动主控界面")
    }

    // ==========================================
    // 业务逻辑 (保持不变)
    // ==========================================

    private fun onMainButtonClick() {
        if (currentMode == MODE_CONTROLLED) {
            toggleControlledService()
        } else {
            if (ControllerService.getInstance() != null) {
                stopControllerService()
            } else {
                startControllerService()
            }
        }
    }

    private fun startControllerService() {
        appendLog("正在启动主控服务...")
        ControllerService.start(this)
        isServiceRunning = true
        updateInfoRow(serviceStatusText, "服务状态:", "启动中...")
        appendLog("主控服务启动命令已发送")
        setupDevicesCallback()
    }

    private fun stopControllerService() {
        appendLog("正在停止主控服务...")
        ControllerService.stop(this)
        isServiceRunning = false
        updateInfoRow(serviceStatusText, "服务状态:", "已停止")
        updateInfoRow(connectionStatusText, "连接状态:", "未连接")
        appendLog("主控服务已停止")
    }

    private fun toggleControlledService() {
        if (isServiceRunning) {
            stopControlledService()
        } else {
            startControlledService()
        }
    }

    private fun startControlledService() {
        appendLog("正在启动被控服务...")
        ConnectionState.onOwnerStatusChange = connectionCallback

        if (!RootManager.isRootAvailable()) {
            appendLog("正在请求 Root 权限，请在弹窗中允许...")
            RootManager.requestRoot(this) { hasRoot ->
                runOnUiThread {
                    updateRootStatusUI(hasRoot)
                    if (hasRoot) {
                        appendLog("Root 权限已获取，启动服务...")
                        doStartControlledService()
                    } else {
                        appendLog("⚠ 未获取 Root 权限，服务将以受限模式启动（无输入控制）")
                        Toast.makeText(this, "未获取Root权限，输入控制不可用", Toast.LENGTH_LONG).show()
                        doStartControlledService()
                    }
                }
            }
        } else {
            doStartControlledService()
        }
    }

    private fun doStartControlledService() {
        ControlledService.start(this)
        isServiceRunning = true
        updateInfoRow(serviceStatusText, "服务状态:", "启动中...")
        updateInfoRow(connectionStatusText, "连接状态:", "连接中...")
        appendLog("服务启动命令已发送，等待连接...")
    }

    private fun stopControlledService() {
        appendLog("正在停止被控服务...")
        isStoppingService = true
        ControlledService.stop(this)
        isServiceRunning = false
        updateInfoRow(serviceStatusText, "服务状态:", "已停止")
        updateInfoRow(connectionStatusText, "连接状态:", "未连接")
        appendLog("服务已停止")
    }

    private var isStoppingService = false

    private fun checkServiceRunning() {
        val owner = if (currentMode == MODE_CONTROLLED) "controlled" else "controller"
        val currentStatus = ConnectionState.getStatusForOwner(owner)
        val controlledRunning = ControlledService.isRunning()
        val controllerRunning = ControllerService.getInstance() != null
        val msg = ConnectionState.getMessageForOwner(owner)

        Log.i("MainActivity", "checkServiceRunning: owner=$owner, status=$currentStatus, msg=$msg, controlled=$controlledRunning, controller=$controllerRunning")

        if (controlledRunning || controllerRunning) {
            isServiceRunning = true
            updateInfoRow(connectionStatusText, "连接状态:", msg.ifEmpty { "连接中..." })
            updateServiceStatusFromConnection(currentStatus)
            appendLog("检测到服务正在运行，状态: ${msg.ifEmpty { "连接中..." }}")
        } else {
            isServiceRunning = false
            updateInfoRow(connectionStatusText, "连接状态:", "未连接")
            updateInfoRow(serviceStatusText, "服务状态:", "未启动")
            updateModeUI()
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.INTERNET)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.INTERNET)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.FOREGROUND_SERVICE)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.FOREGROUND_SERVICE)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), PERMISSION_REQUEST)
        }
    }

    private fun initDevice() {
        val deviceId = DeviceIdManager.getDeviceId(this)
        deviceIdText.text = deviceId
        appendLog("设备 ID: $deviceId")
        updateServerUrlDisplay()
    }

    private fun initRoot() {
        if (currentMode == MODE_CONTROLLER) {
            updateInfoRow(rootStatusText, "Root权限:", "主控端不需要")
            requestRootButton.visibility = View.GONE
            updateProjectionStatusUI()
            return
        }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val hasCheckedRootBefore = prefs.getBoolean("root_checked_before", false)

        if (!hasCheckedRootBefore) {
            updateInfoRow(rootStatusText, "Root权限:", "未获取")
            requestRootButton.visibility = View.VISIBLE
            appendLog("首次启动，请点击「请求Root」按钮获取Root权限")
            updateProjectionStatusUI()
            return
        }

        appendLog("正在检查 Root 权限...")
        updateInfoRow(rootStatusText, "Root权限:", "检查中...")

        RootManager.initAsync(this) { hasRoot ->
            runOnUiThread {
                updateRootStatusUI(hasRoot)
                updateProjectionStatusUI()
            }
        }
    }

    private fun updateProjectionStatusUI() {
        if (currentMode == MODE_CONTROLLER) {
            updateInfoRow(projectionStatusText, "屏幕录制权限:", "主控端不需要")
            requestProjectionButton.visibility = View.GONE
            return
        }

        val hasProjection = MediaProjectionHelper.isProjectionValid()
        val hasGrantedBefore = MediaProjectionHelper.hasGrantedBefore(this)
        val hasRoot = RootManager.isRootAvailable()

        when {
            hasProjection -> {
                updateInfoRow(projectionStatusText, "屏幕录制权限:", "已获取 ✓ (缓存有效)")
                requestProjectionButton.visibility = View.GONE
            }
            hasRoot -> {
                updateInfoRow(projectionStatusText, "屏幕录制权限:", "Root可用，将自动获取")
                requestProjectionButton.visibility = View.GONE
            }
            hasGrantedBefore -> {
                updateInfoRow(projectionStatusText, "屏幕录制权限:", "已授权但缓存失效")
                requestProjectionButton.visibility = View.VISIBLE
            }
            else -> {
                updateInfoRow(projectionStatusText, "屏幕录制权限:", "未获取")
                requestProjectionButton.visibility = View.VISIBLE
            }
        }
    }

    private fun onRequestProjectionClick() {
        appendLog("正在请求屏幕录制权限...")
        requestProjectionButton.isEnabled = false

        if (RootManager.isRootAvailable()) {
            val projection = MediaProjectionHelper.tryAllMethods(this)
            if (projection != null) {
                appendLog("屏幕录制权限已通过Root获取！")
                updateProjectionStatusUI()
                requestProjectionButton.isEnabled = true
                return
            }
        }

        val intent = Intent(this, MediaProjectionActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        appendLog("已弹出屏幕录制授权弹窗，请点击「立即开始」")
        requestProjectionButton.isEnabled = true
    }

    private fun updateRootStatusUI(hasRoot: Boolean) {
        if (hasRoot) {
            appendLog("Root 权限获取成功")
            updateInfoRow(rootStatusText, "Root权限:", "可用 ✓")
            requestRootButton.visibility = View.GONE
        } else {
            appendLog("Root 权限不可用，输入控制功能受限（屏幕共享正常）")
            updateInfoRow(rootStatusText, "Root权限:", "不可用")
            requestRootButton.visibility = View.VISIBLE
        }
    }

    private fun onRequestRootClick() {
        appendLog("正在请求 Root 权限，请在弹窗中允许...")
        updateInfoRow(rootStatusText, "Root权限:", "请求中...")
        requestRootButton.isEnabled = false
        Toast.makeText(this, "正在请求Root权限，请查看Magisk授权弹窗...", Toast.LENGTH_LONG).show()

        RootManager.requestRoot(this) { hasRoot ->
            runOnUiThread {
                requestRootButton.isEnabled = true
                if (hasRoot) {
                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putBoolean("root_checked_before", true).apply()
                    appendLog("Root权限获取成功！")
                    Toast.makeText(this, "Root权限获取成功！", Toast.LENGTH_SHORT).show()
                } else {
                    appendLog("Root权限请求失败。请确认：1.已安装Magisk/SuperSU 2.超级用户设置设为'询问'模式 3.未在Magisk中拒绝并记住")
                    Toast.makeText(this, "Root权限获取失败，请检查Magisk设置", Toast.LENGTH_LONG).show()
                }
                updateRootStatusUI(hasRoot)
            }
        }
    }

    private fun onExportLogClick() {
        appendLog("正在导出日志...")
        exportLogButton.isEnabled = false

        LogExporter.exportLogs(this) { success, path ->
            runOnUiThread {
                exportLogButton.isEnabled = true
                if (success) {
                    appendLog("日志导出成功: $path")
                    Toast.makeText(this, "日志已导出到: $path", Toast.LENGTH_LONG).show()
                } else {
                    appendLog("日志导出失败: $path")
                    Toast.makeText(this, "导出失败: $path", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun appendLog(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())

        runOnUiThread {
            logList.add("[$timestamp] $message")
            if (logList.size > MAX_LOG_LINES) {
                logList.removeFirst()
            }
            logText.text = logList.joinToString("\n") + "\n"
            logScrollView.post { logScrollView.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun dp(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
}
