package com.yourapp.supercontroler

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
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
    }

    private lateinit var settingsButton: Button
    private lateinit var mainButton: Button
    private lateinit var serviceStatusText: TextView
    private lateinit var rootStatusText: TextView
    private lateinit var deviceIdText: TextView
    private lateinit var connectionStatusText: TextView
    private lateinit var serverUrlText: TextView
    private lateinit var logScrollView: ScrollView
    private lateinit var logText: TextView
    private lateinit var devicesContainer: LinearLayout
    private lateinit var requestRootButton: Button
    private lateinit var exportLogButton: Button
    private lateinit var projectionStatusText: TextView
    private lateinit var requestProjectionButton: Button

    private var currentMode = MODE_CONTROLLED
    private var isServiceRunning = false
    private val onlineDevices = mutableMapOf<String, SignalingClient.OnlineDevice>()
    private lateinit var devicesRefreshButton: Button

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
        if (isServiceRunning) {
            if (currentMode == MODE_CONTROLLED) {
                mainButton.text = "停止被控服务"
            } else {
                mainButton.text = "停止主控服务"
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

    private fun buildUI() {
        val rootView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#121212"))
        }
        val contentView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(48), dp(20), dp(20))
        }
        rootView.addView(contentView)

        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleText = TextView(this).apply {
            text = "SuperControler"
            textSize = 24f
            setTextColor(Color.parseColor("#82B1FF"))
        }
        headerLayout.addView(titleText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        settingsButton = Button(this).apply {
            text = "设置"
            textSize = 14f
            setOnClickListener { showSettingsDialog() }
        }
        headerLayout.addView(settingsButton)
        contentView.addView(headerLayout)

        contentView.addView(createModeToggle())

        val card1 = createCard()

        val deviceIdRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        deviceIdText = TextView(this).apply {
            text = "设备 ID: 加载中..."
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        deviceIdRow.addView(deviceIdText)
        val copyButton = Button(this).apply {
            text = "复制"
            textSize = 12f
            setOnClickListener {
                val deviceId = DeviceIdManager.getDeviceId(this@MainActivity)
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Device ID", deviceId)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this@MainActivity, "设备ID已复制", Toast.LENGTH_SHORT).show()
            }
        }
        deviceIdRow.addView(copyButton)
        card1.addView(deviceIdRow)

        serverUrlText = createInfoRow("服务器:", getServerUrl())
        card1.addView(serverUrlText)

        connectionStatusText = createInfoRow("连接状态:", "未连接")
        card1.addView(connectionStatusText)

        serviceStatusText = createInfoRow("服务状态:", "未启动")
        card1.addView(serviceStatusText)

        val rootRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        rootStatusText = TextView(this).apply {
            text = "Root权限: 检查中..."
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        rootRow.addView(rootStatusText)
        requestRootButton = Button(this).apply {
            text = "请求Root"
            textSize = 12f
            visibility = View.GONE
            setOnClickListener { onRequestRootClick() }
        }
        rootRow.addView(requestRootButton)
        card1.addView(rootRow)

        val projectionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        projectionStatusText = TextView(this).apply {
            text = "屏幕录制权限: 检查中..."
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        projectionRow.addView(projectionStatusText)
        requestProjectionButton = Button(this).apply {
            text = "请求录制"
            textSize = 12f
            visibility = View.GONE
            setOnClickListener { onRequestProjectionClick() }
        }
        projectionRow.addView(requestProjectionButton)
        card1.addView(projectionRow)

        val logRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val logStatusText = TextView(this).apply {
            text = "日志导出: 点击右侧按钮"
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        logRow.addView(logStatusText)
        exportLogButton = Button(this).apply {
            text = "导出日志"
            textSize = 12f
            setOnClickListener { onExportLogClick() }
        }
        logRow.addView(exportLogButton)
        card1.addView(logRow)

        contentView.addView(card1)

        contentView.addView(createSpacer(dp(12)))

        mainButton = Button(this).apply {
            textSize = 18f
            setOnClickListener { onMainButtonClick() }
        }
        contentView.addView(mainButton)

        if (currentMode == MODE_CONTROLLER) {
            contentView.addView(createDevicesSection())
        }

        val logLabel = TextView(this).apply {
            text = "日志"
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(20), 0, dp(8))
        }
        contentView.addView(logLabel)

        logScrollView = ScrollView(this)
        logText = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#81C784"))
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        logScrollView.addView(logText)
        contentView.addView(logScrollView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        setContentView(rootView)
        updateModeUI()
    }

    private fun createModeToggle(): LinearLayout {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(16), 0, dp(16))
        }

        val label = TextView(this).apply {
            text = "当前模式:"
            textSize = 14f
        }
        layout.addView(label)

        val modeButton = Button(this).apply {
            text = if (currentMode == MODE_CONTROLLED) "被控端" else "主控端"
            textSize = 14f
            setOnClickListener { showModeSwitchDialog() }
        }
        layout.addView(modeButton)

        return layout
    }

    private fun createDevicesSection(): LinearLayout {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }

        val labelLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val sectionLabel = TextView(this).apply {
            text = "在线设备"
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        labelLayout.addView(sectionLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        devicesRefreshButton = Button(this).apply {
            text = "刷新"
            textSize = 12f
            setOnClickListener {
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
            }
        }
        labelLayout.addView(devicesRefreshButton)

        val addButton = Button(this).apply {
            text = "+ 添加"
            textSize = 12f
            setOnClickListener { showAddDeviceDialog() }
        }
        labelLayout.addView(addButton)
        section.addView(labelLayout)

        devicesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(devicesContainer)

        loadSavedDevices()

        return section
    }

    private fun createCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Color.parseColor("#1E1E2E"))
        }
    }

    private fun createInfoRow(label: String, value: String): TextView {
        return TextView(this).apply {
            text = "$label $value"
            textSize = 15f
            setPadding(0, dp(6), 0, dp(6))
        }
    }

    private fun updateInfoRow(textView: TextView, label: String, value: String) {
        textView.text = "$label $value"
    }

    private fun createSpacer(height: Int): View {
        return View(this).apply { setPadding(0, height, 0, 0) }
    }

    private fun updateModeUI() {
        if (currentMode == MODE_CONTROLLED) {
            mainButton.text = if (isServiceRunning) "停止被控服务" else "启动被控服务"
        } else {
            mainButton.text = if (isServiceRunning) "停止主控服务" else "启动主控服务"
        }
        updateServerUrlDisplay()
    }

    private fun updateServerUrlDisplay() {
        updateInfoRow(serverUrlText, "服务器:", getServerUrl())
    }

    private fun showModeSwitchDialog() {
        val modes = arrayOf("被控端（等待被控制）", "主控端（控制其他设备）")
        val currentIndex = if (currentMode == MODE_CONTROLLED) 0 else 1

        AlertDialog.Builder(this)
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
        setContentView(ScrollView(this))
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
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        dialogView.addView(urlInput)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val autoStartCheck = android.widget.CheckBox(this).apply {
            text = "开机自动启动服务"
            isChecked = prefs.getBoolean("auto_start", true)
        }
        dialogView.addView(autoStartCheck)

        AlertDialog.Builder(this)
            .setTitle("服务器设置")
            .setView(dialogView)
            .setPositiveButton("保存") { _, _ ->
                val url = urlInput.text.toString().trim()
                if (url.isNotEmpty()) {
                    saveServerUrl(url)
                }
                prefs.edit().putBoolean("auto_start", autoStartCheck.isChecked).apply()
                appendLog("自动启动: ${if (autoStartCheck.isChecked) "已开启" else "已关闭"}")
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
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        dialogView.addView(nameInput)

        val idInput = EditText(this).apply {
            hint = "设备 ID"
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        dialogView.addView(idInput)

        AlertDialog.Builder(this)
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

    private fun addDeviceRow(name: String, id: String, isSaved: Boolean = true) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(Color.parseColor("#2D2D3D"))
        }

        val normalizedId = id.trim().uppercase()
        val isOnline = onlineDevices.keys.any { it.trim().uppercase() == normalizedId }

        val onlineIndicator = View(this).apply {
            setBackgroundColor(if (isOnline) Color.parseColor("#4CAF50") else Color.parseColor("#9E9E9E"))
            val size = dp(10)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                setMargins(0, 0, dp(8), 0)
            }
        }

        val infoLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        infoLayout.addView(TextView(this).apply {
            text = name
            textSize = 14f
            setTextColor(Color.WHITE)
        })
        infoLayout.addView(TextView(this).apply {
            text = "ID: $id"
            textSize = 11f
            setTextColor(Color.parseColor("#AAAAAA"))
        })

        row.addView(onlineIndicator)
        row.addView(infoLayout)

        val connectBtn = Button(this).apply {
            text = if (isOnline) "连接" else "离线"
            textSize = 12f
            isEnabled = isOnline
            setOnClickListener { connectToDevice(id) }
        }
        row.addView(connectBtn)

        if (isSaved) {
            val deleteBtn = Button(this).apply {
                text = "删除"
                textSize = 12f
                setOnClickListener { deleteDevice(name, id) }
            }
            row.addView(deleteBtn)
        }

        devicesContainer.addView(row)
    }

    private fun refreshDevicesListUI() {
        if (!::devicesContainer.isInitialized) return
        devicesContainer.removeAllViews()

        val savedDevices = getSavedDevices()
        val allOnlineDevices = onlineDevices.toMap()

        if (allOnlineDevices.isEmpty()) {
            val emptyText = TextView(this).apply {
                text = "暂无在线设备"
                setTextColor(Color.parseColor("#AAAAAA"))
                textSize = 12f
            }
            devicesContainer.addView(emptyText)
            return
        }

        for ((deviceId, device) in allOnlineDevices) {
            val savedName = findSavedDeviceName(savedDevices, deviceId)
            val displayName = savedName ?: (device.name.ifEmpty { "未知设备" })
            addDeviceRow(displayName, deviceId, savedName != null)
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
        AlertDialog.Builder(this)
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
        mainButton.text = "停止主控服务"
        updateInfoRow(serviceStatusText, "服务状态:", "启动中...")
        appendLog("主控服务启动命令已发送")
        setupDevicesCallback()
    }

    private fun stopControllerService() {
        appendLog("正在停止主控服务...")
        ControllerService.stop(this)
        isServiceRunning = false
        mainButton.text = "启动主控服务"
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
        mainButton.text = "停止被控服务"
        updateInfoRow(serviceStatusText, "服务状态:", "启动中...")
        updateInfoRow(connectionStatusText, "连接状态:", "连接中...")
        appendLog("服务启动命令已发送，等待连接...")
    }

    private fun stopControlledService() {
        appendLog("正在停止被控服务...")
        isStoppingService = true
        ControlledService.stop(this)
        isServiceRunning = false
        mainButton.text = "启动被控服务"
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
            if (currentMode == MODE_CONTROLLED) {
                mainButton.text = "停止被控服务"
            } else {
                mainButton.text = "停止主控服务"
            }
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
        deviceIdText.text = "设备 ID: $deviceId"
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
            logText.append("[$timestamp] $message\n")
            logScrollView.post { logScrollView.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun dp(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
}
