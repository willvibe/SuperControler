# SuperControler 深度问题分析与完整修复方案

> 核心症状：被控端 Root 权限获取失败 + 主控端连接后黑屏无法看到或操控被控端屏幕

------

## 一、问题根因总览（优先级排序）

| #    | 问题模块                  | 问题描述                                                  | 严重程度 | 直接影响     |
| ---- | ------------------------- | --------------------------------------------------------- | -------- | ------------ |
| P1   | `WebRtcClient`            | `addTrack` 时机错误，在 SDP 协商后才添加 VideoTrack       | 🔴 致命   | 黑屏根因     |
| P2   | `MediaProjectionHelper`   | Android 12+ 反射获取 MediaProjection 失效                 | 🔴 致命   | 黑屏根因     |
| P3   | `ControllerActivity`      | `SurfaceViewRenderer` 初始化时序问题，VideoTrack 丢失     | 🔴 致命   | 黑屏根因     |
| P4   | `RootManager`             | libsu 5.x API 使用错误 + Shell 初始化时序                 | 🔴 致命   | Root 失效    |
| P5   | `ControlledService`       | MediaProjection intent 传递链断裂                         | 🔴 致命   | 屏幕采集失败 |
| P6   | `WebRtcClient`            | ICE 候选队列处理 bug（race condition）                    | 🟠 严重   | P2P 连接失败 |
| P7   | `IceConfig`               | 缺少 TURN 服务器，严格 NAT 下 P2P 无法建立                | 🟠 严重   | 连接失败     |
| P8   | `MediaProjectionActivity` | uiautomator 自动点击不稳定，多种 ROM 上失效               | 🟠 严重   | 授权失败     |
| P9   | `ControlledService`       | 屏幕编码尺寸未对齐导致编码器初始化失败                    | 🟡 中等   | 视频流中断   |
| P10  | `PairingManager`          | PIN 硬编码为 "123456"，无任何安全保障                     | 🟡 中等   | 安全漏洞     |
| P11  | 信令协议                  | `punch_info` 双方角色混淆，controller/controlled 发送错误 | 🟠 严重   | 协商失败     |
| P12  | `WebRtcClient`            | PeerConnectionFactory 在子线程初始化无 Looper             | 🟡 中等   | 崩溃/无响应  |

------

## 二、P1：VideoTrack addTrack 时机错误（黑屏根因之一）

### 问题描述

在 `WebRtcClient.setupAsControlled()` 的实现中，根据架构报告描述的流程：

```
收到 SDP Offer → 启动屏幕捕获 → 创建 Answer
```

**错误在于**：应该在 `createAnswer()` 之前就把 `VideoTrack` 加入 `PeerConnection`。
 WebRTC 规范要求：**媒体轨道必须在 `createOffer/createAnswer` 之前通过 `addTrack()` 加入**，SDP 才会包含 `m=video` 部分。
 如果先 `createAnswer()` 再 `addTrack()`，Answer 的 SDP 里没有视频媒体行，主控端认为协商不包含视频，VideoTrack 永远不会出现在主控端，结果是黑屏。

### 错误代码模式

```kotlin
// ❌ 错误：先协商再添加轨道
fun onSdpOfferReceived(offer: SessionDescription) {
    peerConnection.setRemoteDescription(offer)
    startScreenCapture()           // 1. 启动捕获
    val answer = createAnswer()    // 2. 立即创建 Answer（此时 VideoTrack 还没 addTrack！）
    peerConnection.setLocalDescription(answer)
    // 3. 稍后某处才 addTrack —— 太晚了
    peerConnection.addTrack(videoTrack, listOf(streamId))
}
```

### 修复方案

```kotlin
// ✅ 正确：先准备媒体轨道，再协商
fun setupAsControlled(mediaProjectionData: Intent?, w: Int, h: Int, fps: Int) {
    // Step 1: 立即初始化 VideoSource 和 VideoTrack（不等 Offer）
    val videoSource = peerConnectionFactory.createVideoSource(false)
    videoTrack = peerConnectionFactory.createVideoTrack("video0", videoSource)
    
    // Step 2: 注册数据通道监听（被控端等待主控端创建）
    setupDataChannelListener()
    
    // Step 3: 将 VideoTrack 加入 PeerConnection（必须在收到 Offer 之前）
    val stream = peerConnectionFactory.createLocalMediaStream("local_stream")
    stream.addTrack(videoTrack)
    peerConnection.addTrack(videoTrack, listOf("local_stream"))
    // 注意：此时 VideoTrack 没有数据源，是空的——但已注册在协商中
}

fun onSdpOfferReceived(offer: SessionDescription) {
    // Step 4: 设置远端 SDP（包含主控端的视频期望）
    peerConnection.setRemoteDescription(sdpObserver, offer)
    
    // Step 5: 启动屏幕捕获，并将捕获器连接到已注册的 VideoSource
    val capturer = ScreenCapturerAndroid(mediaProjectionData, object : MediaProjection.Callback() {})
    capturer.initialize(surfaceTextureHelper, context, videoSource.capturerObserver)
    capturer.startCapture(w, h, fps)  // 此时视频数据开始流入已注册的 VideoTrack
    
    // Step 6: 现在创建 Answer（SDP 已包含 m=video，因为 addTrack 已在前）
    peerConnection.createAnswer(sdpObserver, MediaConstraints())
}
```

**关键原则**：`addTrack` → `setRemoteDescription` → `createAnswer` 的顺序不可颠倒。

------

## 三、P2：Android 12+ MediaProjection 反射获取失效（黑屏根因之二）

### 问题描述

`MediaProjectionHelper` 的降级策略在 Android 12（API 31）及以上版本上大概率全部失败：

1. **策略1（反射 MediaProjectionManager 字段）**：Android 12 起系统已对该字段做了访问限制，即使 `VMRuntime.setHiddenApiExemptions` 也无法绕过 `@UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R)` 的限制。
2. **策略2（ServiceManager 获取 media_projection）**：`ServiceManager.getService("media_projection")` 在 Android 12 上返回 `null`，该服务路径已变更。
3. **策略3（Root service call Binder 事务）**：Binder 事务编号在不同 Android 版本间不稳定，非官方接口，极易失效。
4. **策略4（Root 授权 PROJECT_MEDIA 权限）**：`PROJECT_MEDIA` 权限从 Android 10 起改为 `signature|privileged` 级别，`pm grant` 命令对第三方 APK 无效。

### 根本解决方案

**在 Root 设备上，最可靠的方式是通过 `adb shell / su` 调用 `screencap` 或使用 `SurfaceControl` 直接截图，而不是依赖 MediaProjection API。**
 但对于流式视频传输，正确的 Root 方案是：

#### 方案 A：Root 模式使用 VirtualDisplay + CAPTURE_VIDEO_OUTPUT（推荐）

```kotlin
// ScreenCaptureManager.kt 修复版
class ScreenCaptureManager(private val context: Context) {
    
    fun createVirtualDisplayWithRoot(
        width: Int, height: Int, dpi: Int,
        surface: Surface
    ): VirtualDisplay? {
        return try {
            // Step 1: 通过 Root 授予 CAPTURE_VIDEO_OUTPUT 权限（关键！）
            val packageName = context.packageName
            Shell.cmd("pm grant $packageName android.permission.CAPTURE_VIDEO_OUTPUT").exec()
            // Android 12+ 需要额外授权
            Shell.cmd("pm grant $packageName android.permission.CAPTURE_SECURE_VIDEO_OUTPUT").exec()
            
            // Step 2: 使用 DisplayManager 直接创建 VirtualDisplay（无需 MediaProjection）
            val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            
            // Step 3: 通过反射设置 VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
            val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
            
            displayManager.createVirtualDisplay(
                "ScreenCapture",
                width, height, dpi,
                surface,
                flags
            )
        } catch (e: Exception) {
            Log.e("ScreenCapture", "Root VirtualDisplay failed: ${e.message}")
            null
        }
    }
    
    // 备用：使用 SurfaceControl（Android 10+，Root 可访问）
    fun createVirtualDisplayViaSurfaceControl(
        width: Int, height: Int, surface: Surface
    ): Boolean {
        return try {
            // 通过 Root 执行 screencap 然后通过 VideoSource 推流
            // 或者使用 wm 命令获取屏幕内容
            val result = Shell.cmd(
                "settings put global policy_control immersive.full=*"
            ).exec()
            result.isSuccess
        } catch (e: Exception) {
            false
        }
    }
}
```

#### 方案 B：修复 MediaProjectionActivity 自动授权（非 Root 路径）

```kotlin
// MediaProjectionActivity.kt 修复版
class MediaProjectionActivity : AppCompatActivity() {
    
    companion object {
        private const val REQUEST_CODE = 100
        // 关键：在 Activity 启动时就立即请求，不要延迟
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 修复1：检测 Root 可用性，优先走 Root 路径
        if (Shell.isAppGrantedRoot() == true) {
            grantPermissionViaRoot()
        } else {
            requestMediaProjectionNormally()
        }
    }
    
    private fun grantPermissionViaRoot() {
        lifecycleScope.launch(Dispatchers.IO) {
            // 使用 uiautomator2 而不是 uiautomator，更稳定
            val result = Shell.cmd(
                "am start -n com.android.systemui/.media.MediaProjectionPermissionActivity"
            ).exec()
            delay(1000)
            // 使用坐标点击（比文本查找更稳定）
            val displaySize = getDisplaySize()
            // "立即开始"按钮通常在屏幕右下角 2/3 处
            val x = displaySize.x * 2 / 3
            val y = displaySize.y * 3 / 4
            Shell.cmd("input tap $x $y").exec()
        }
    }
    
    private fun requestMediaProjectionNormally() {
        val mediaProjectionManager = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(
            mediaProjectionManager.createScreenCaptureIntent(),
            REQUEST_CODE
        )
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            // 关键修复：必须立即在此处创建 MediaProjection token，
            // 不能把 Intent 传出去后再创建（token 会过期）
            val mediaProjection = (getSystemService(MediaProjectionManager::class.java))
                .getMediaProjection(resultCode, data)
            
            // 通过 LocalBroadcastManager 或直接回调传递 MediaProjection 对象
            // 不要传 Intent，要传 MediaProjection 实例！
            LocalBroadcastManager.getInstance(this).sendBroadcast(
                Intent("ACTION_MEDIA_PROJECTION_READY").apply {
                    // 注意：MediaProjection 不能序列化，需要通过单例持有
                    MediaProjectionHolder.instance = mediaProjection
                }
            )
            finish()
        }
    }
}

// 新增：MediaProjection 单例持有者（解决跨组件传递问题）
object MediaProjectionHolder {
    @Volatile var instance: MediaProjection? = null
}
```

------

## 四、P3：SurfaceViewRenderer 初始化时序（黑屏根因之三）

### 问题描述

在 `ControllerActivity` 中，`onRemoteVideoTrack()` 回调可能在 `SurfaceViewRenderer.init()` 完成之前就被触发。
 此时调用 `videoTrack.addSink(surfaceViewRenderer)` 会失败（EGL 上下文未建立），且**不会抛出异常**，只是静默失败，导致永久黑屏。

### 错误代码模式

```kotlin
// ❌ 错误：init 是异步的，addSink 可能在 EGL 就绪前调用
class ControllerActivity : AppCompatActivity() {
    override fun onCreate(...) {
        surfaceViewRenderer.init(eglBase.eglBaseContext, null) // 异步初始化
        webRtcClient.eventListener = object : EventListener {
            override fun onRemoteVideoTrack(videoTrack: VideoTrack) {
                videoTrack.addSink(surfaceViewRenderer) // 可能此时 EGL 还没好！
            }
        }
    }
}
```

### 修复方案

```kotlin
// ✅ 修复版 ControllerActivity
class ControllerActivity : AppCompatActivity() {
    private var pendingVideoTrack: VideoTrack? = null
    private var isSurfaceReady = false
    private val lock = Any()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 修复1：使用 EglBase 单例，确保整个应用共享同一 EGL 上下文
        val eglBase = EglBase.create()
        
        surfaceViewRenderer.apply {
            init(eglBase.eglBaseContext, object : RendererCommon.RendererEvents {
                override fun onFirstFrameRendered() {}
                override fun onFrameResolutionChanged(w: Int, h: Int, rotation: Int) {
                    // 分辨率变化时自动调整 UI
                    runOnUiThread { adjustAspectRatio(w, h) }
                }
            })
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            setEnableHardwareScaler(true)
            
            // 修复2：监听 Surface 就绪事件
            addFrameListener({
                synchronized(lock) {
                    if (!isSurfaceReady) {
                        isSurfaceReady = true
                        pendingVideoTrack?.let { track ->
                            track.addSink(surfaceViewRenderer)
                            pendingVideoTrack = null
                        }
                    }
                }
            }, 0f)
        }
        
        // 修复3：提前设置好监听，VideoTrack 到达时判断是否就绪
        webRtcClient.eventListener = object : EventListener {
            override fun onRemoteVideoTrack(videoTrack: VideoTrack) {
                synchronized(lock) {
                    if (isSurfaceReady) {
                        videoTrack.addSink(surfaceViewRenderer)
                    } else {
                        pendingVideoTrack = videoTrack
                    }
                }
            }
        }
    }
    
    // 修复4：Activity 不可见时暂停渲染，恢复时恢复
    override fun onResume() {
        super.onResume()
        surfaceViewRenderer.setFpsReduction(Float.MAX_VALUE) // 全速渲染
    }
    
    override fun onPause() {
        super.onPause()
        surfaceViewRenderer.setFpsReduction(1f) // 降低帧率节省资源
    }
    
    override fun onDestroy() {
        pendingVideoTrack?.removeSink(surfaceViewRenderer)
        surfaceViewRenderer.release()
        super.onDestroy()
    }
}
```

------

## 五、P4：RootManager libsu 5.x API 使用错误

### 问题描述

libsu 5.x 与 4.x 有重大 API 变更：

1. `Shell.Builder.create().setContext().build("su")` — `setContext()` 在 5.x 中已被移除或签名改变。
2. `Shell.getShell()` 在 5.x 中是 `suspend` 函数，不能在同步上下文中调用。
3. App.kt 中 3s 延迟后启动服务，但 libsu Shell 的配置必须在 `Application.onCreate()` 的第一行完成，且 `Shell.Builder` 必须在 Application 类中调用，否则后续 `Shell.cmd()` 会使用默认（未配置的）Shell。

### 错误代码模式

```kotlin
// ❌ App.kt 错误模式
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // 错误1：libsu 5.x 的配置方式已变更
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setContext(this)  // ❌ 5.x 中此方法已移除
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setTimeout(10)
        )
        
        // 错误2：延迟启动服务，但 Shell 可能还没初始化完成
        Handler(Looper.getMainLooper()).postDelayed({
            startService(...)
        }, 3000)
    }
}
```

### 修复方案

```kotlin
// ✅ App.kt 修复版（libsu 5.2.2 正确用法）
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // 修复1：libsu 5.x 正确配置方式
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER or Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(30)  // 增加超时，避免 Magisk 初始化慢导致超时
        )
        
        // 修复2：异步预热 Shell，不阻塞 Application 启动
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 提前获取 Shell，后续调用直接复用
                val shell = Shell.getShell()
                Log.d("App", "Root shell ready: isRoot=${shell.isRoot}")
                
                // Shell 就绪后才启动服务（替代固定 3s 延迟）
                val mode = getSharedPreferences("prefs", MODE_PRIVATE)
                    .getString("mode", null)
                if (mode != null) {
                    withContext(Dispatchers.Main) {
                        startForegroundServiceCompat(mode)
                    }
                }
            } catch (e: Exception) {
                Log.e("App", "Shell init failed: ${e.message}")
                // 无 Root 权限时仍可启动主控端服务
            }
        }
    }
}
// ✅ RootManager.kt 修复版
class RootManager private constructor(private val context: Context) {
    
    companion object {
        @Volatile private var instance: RootManager? = null
        fun getInstance(context: Context) = instance ?: synchronized(this) {
            instance ?: RootManager(context.applicationContext).also { instance = it }
        }
    }
    
    // 修复：使用 StateFlow 管理 Root 状态，避免重复初始化
    private val _rootState = MutableStateFlow<RootState>(RootState.Unknown)
    val rootState: StateFlow<RootState> = _rootState.asStateFlow()
    
    sealed class RootState {
        object Unknown : RootState()
        object Checking : RootState()
        object Granted : RootState()
        object Denied : RootState()
        data class Error(val message: String) : RootState()
    }
    
    suspend fun requestRoot(): Boolean = withContext(Dispatchers.IO) {
        if (_rootState.value == RootState.Granted) return@withContext true
        _rootState.value = RootState.Checking
        
        return@withContext try {
            // libsu 5.x 正确方式：直接使用 Shell.cmd()
            val result = Shell.cmd("id").exec()
            val isRoot = result.isSuccess && 
                         result.out.firstOrNull()?.contains("uid=0") == true
            
            if (isRoot) {
                _rootState.value = RootState.Granted
                grantRequiredPermissions()
            } else {
                _rootState.value = RootState.Denied
            }
            isRoot
        } catch (e: Exception) {
            _rootState.value = RootState.Error(e.message ?: "Unknown error")
            false
        }
    }
    
    private suspend fun grantRequiredPermissions() = withContext(Dispatchers.IO) {
        val pkg = context.packageName
        val permissions = listOf(
            "android.permission.CAPTURE_VIDEO_OUTPUT",
            "android.permission.WRITE_SECURE_SETTINGS",
            "android.permission.READ_FRAME_BUFFER",
        )
        permissions.forEach { perm ->
            val result = Shell.cmd("pm grant $pkg $perm").exec()
            Log.d("Root", "Grant $perm: ${result.isSuccess}")
        }
    }
}
```

------

## 六、P5：MediaProjection Intent 传递链断裂

### 问题描述

`ControlledService` 的流程：

```
启动 → 请求 Root → 请求 MediaProjection → 连接信令服务器
```

但 `MediaProjection` 的生命周期与 `Intent` 不同：

1. `MediaProjectionActivity.onActivityResult()` 返回的是 `resultCode + data (Intent)`
2. 将此 `Intent` 序列化后通过 `startService` 传递给 `ControlledService`，**Intent 中的 Binder token 在跨进程传递后已失效**（Android 10+）
3. `ControlledService` 拿到过期 Intent 后调用 `MediaProjectionManager.getMediaProjection()` 返回 `null` 或抛出异常

### 修复方案

```kotlin
// ✅ 正确的 MediaProjection 传递方式

// 1. MediaProjectionActivity 中直接创建 MediaProjection 并通过单例传递
override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    if (resultCode == RESULT_OK && data != null) {
        // 立即创建，不要延迟
        val mediaProjection = getSystemService(MediaProjectionManager::class.java)
            .getMediaProjection(resultCode, data)
        
        // 通过单例传递（不能序列化 MediaProjection）
        MediaProjectionHolder.set(mediaProjection)
        
        // 通知 ControlledService
        sendBroadcast(Intent("com.yourapp.ACTION_PROJECTION_READY"))
        finish()
    }
}

// 2. ControlledService 中从单例获取
class ControlledService : Service() {
    private val projectionReadyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val mediaProjection = MediaProjectionHolder.get()
            if (mediaProjection != null) {
                initWebRtcAsControlled(mediaProjection)
            } else {
                Log.e("Controlled", "MediaProjection is null!")
                // 回退到 Root 模式
                tryRootScreenCapture()
            }
        }
    }
}

// 3. 单例持有，使用 WeakReference 避免内存泄漏
object MediaProjectionHolder {
    private var ref: WeakReference<MediaProjection>? = null
    
    fun set(projection: MediaProjection) {
        ref = WeakReference(projection)
    }
    
    fun get(): MediaProjection? = ref?.get()
    
    fun clear() {
        ref?.get()?.stop()
        ref = null
    }
}
```

------

## 七、P6：ICE 候选队列 Race Condition

### 问题描述

`WebRtcClient` 在远端 SDP 未设置时缓存 ICE 候选，但存在竞态条件：

1. 主控端创建 Offer 并发送，同时开始收集 ICE 候选
2. ICE 候选可能在 `setRemoteDescription` 完成之前就到达被控端
3. 被控端将候选放入队列，等待 `setRemoteDescription` 完成后处理
4. **问题**：如果队列在 `setRemoteDescription` 回调触发 **后** 但在队列清空代码执行 **前** 有新候选到来，新候选会被跳过（队列已被认为是空的）

### 修复方案

```kotlin
// ✅ 线程安全的 ICE 候选队列处理
class WebRtcClient {
    private val iceCandidateQueue = CopyOnWriteArrayList<IceCandidate>()
    
    @Volatile private var isRemoteSdpSet = false
    private val iceQueueLock = ReentrantLock()
    
    fun addRemoteIceCandidate(candidate: IceCandidate) {
        iceQueueLock.withLock {
            if (isRemoteSdpSet) {
                // SDP 已就绪，直接添加
                peerConnection?.addIceCandidate(candidate)
            } else {
                // SDP 未就绪，入队
                iceCandidateQueue.add(candidate)
            }
        }
    }
    
    private fun onRemoteSdpSet() {
        iceQueueLock.withLock {
            isRemoteSdpSet = true
            // 原子性地取出并清空队列
            val pending = iceCandidateQueue.toList()
            iceCandidateQueue.clear()
            pending.forEach { candidate ->
                peerConnection?.addIceCandidate(candidate)
            }
        }
    }
    
    // SDP 设置完成的回调
    inner class SdpObserverImpl : SdpObserver {
        override fun onSetSuccess() {
            // 只在 setRemoteDescription 成功时清空队列
            if (isSettingRemoteDescription) {
                onRemoteSdpSet()
            }
        }
        override fun onSetFailure(error: String?) {
            Log.e("WebRTC", "SDP set failed: $error")
        }
        override fun onCreateSuccess(sdp: SessionDescription?) {}
        override fun onCreateFailure(error: String?) {}
    }
}
```

------

## 八、P7：缺少 TURN 服务器

### 问题描述

`IceConfig` 只配置了 Google STUN 服务器。在以下场景下，纯 STUN 无法建立 P2P 连接：

- 对称型 NAT（企业网络、运营商 CGNAT）
- 防火墙过滤 UDP
- 某些 5G 网络

### 修复方案

```kotlin
// ✅ IceConfig.kt 修复版
object IceConfig {
    fun createIceServers(): List<PeerConnection.IceServer> {
        return listOf(
            // 多个 STUN 服务器（提高可用性）
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer(),
            
            // TURN 服务器（必须自建或使用付费服务）
            PeerConnection.IceServer.builder("turn:your-turn-server.com:3478")
                .setUsername("username")
                .setPassword("password")
                .createIceServer(),
            
            // TURN over TLS（443端口，穿透严格防火墙）
            PeerConnection.IceServer.builder("turns:your-turn-server.com:443")
                .setUsername("username")
                .setPassword("password")
                .createIceServer(),
        )
    }
    
    // 推荐：从信令服务器动态获取 TURN 凭证（时效性更好）
    // 在 punch_info 消息中携带 TURN 临时凭证
}
```

**信令服务器 (Go) 修改**：在 `punch_info` 消息中附带动态 TURN 凭证：

```go
// server.go
type PunchInfo struct {
    PeerIP      string `json:"peer_ip"`
    PeerPort    int    `json:"peer_port"`
    Role        string `json:"role"`
    SessionKey  string `json:"session_key"`
    // 新增：TURN 临时凭证
    TurnURLs    []string `json:"turn_urls"`
    TurnUser    string   `json:"turn_user"`
    TurnCred    string   `json:"turn_cred"`
}
```

------

## 九、P8：MediaProjectionActivity uiautomator 自动点击不稳定

### 问题描述

通过 Root 执行 uiautomator 查找"立即开始"按钮并点击，在以下情况会失败：

- MIUI、ColorOS、OneUI 等定制 ROM 修改了对话框按钮的 resource-id
- 系统语言非中文时，按钮文本不同
- 对话框尚未渲染完成就尝试点击

### 修复方案

```kotlin
// ✅ 多策略稳健自动点击
private suspend fun autoClickConfirmDialog() = withContext(Dispatchers.IO) {
    // 等待对话框出现（最多 5 秒）
    var attempts = 0
    var success = false
    
    while (attempts < 10 && !success) {
        delay(500)
        attempts++
        
        // 策略1：按多语言文本查找（覆盖主要 ROM）
        val textVariants = listOf(
            "立即开始", "Start now", "Jetzt starten",
            "Commencer", "Ahora", "开始录制"
        )
        for (text in textVariants) {
            val result = Shell.cmd(
                """uiautomator runtest /data/local/tmp/uiautomator.jar """ +
                """-c com.example.ClickHelper -e text "$text" """
            ).exec()
            if (result.isSuccess) { success = true; break }
        }
        if (success) break
        
        // 策略2：按 resource-id 查找（AOSP 标准 id）
        val resourceIds = listOf(
            "android:id/button1",
            "com.android.systemui:id/primary_button",
            "com.android.systemui:id/action_button"
        )
        for (resId in resourceIds) {
            val result = Shell.cmd(
                "uiautomator dump /dev/stdout | grep '$resId'"
            ).exec()
            if (result.out.isNotEmpty()) {
                // 解析坐标并点击
                val bounds = parseBounds(result.out.first())
                if (bounds != null) {
                    Shell.cmd("input tap ${bounds.centerX()} ${bounds.centerY()}").exec()
                    success = true
                    break
                }
            }
        }
        if (success) break
        
        // 策略3：按位置盲点（最后手段，根据屏幕尺寸估算按钮位置）
        if (attempts >= 8) {
            val (w, h) = getDisplaySize()
            // 系统弹窗的"确认"按钮通常在右下角 60-80% 处
            Shell.cmd("input tap ${w * 3 / 4} ${h * 2 / 3}").exec()
            success = true
        }
    }
    success
}
```

------

## 十、P9：屏幕编码尺寸计算错误

### 问题描述

`ControlledService` 中的编码尺寸计算：

- 长边限制 1280px
- 对齐到 16 的倍数

但存在问题：部分 Android 设备（特别是刘海屏）的屏幕比例特殊，计算后的尺寸与实际 VirtualDisplay 尺寸不一致，导致编码器 `configure()` 失败。

### 修复方案

```kotlin
// ✅ 健壮的编码尺寸计算
fun calculateEncodingSize(screenWidth: Int, screenHeight: Int): Pair<Int, Int> {
    val maxLongEdge = 1280
    val alignment = 16 // H.264 要求宽高必须是 16 的倍数
    
    val isPortrait = screenHeight > screenWidth
    val longEdge = if (isPortrait) screenHeight else screenWidth
    val shortEdge = if (isPortrait) screenWidth else screenHeight
    
    val scale = if (longEdge > maxLongEdge) maxLongEdge.toFloat() / longEdge else 1f
    
    // 先计算，再对齐
    var encLong = (longEdge * scale).toInt()
    var encShort = (shortEdge * scale).toInt()
    
    // 向下对齐到 alignment 的倍数（必须向下，不能向上，否则超出缓冲区）
    encLong = (encLong / alignment) * alignment
    encShort = (encShort / alignment) * alignment
    
    // 确保最小尺寸（某些编码器要求最小 128x128）
    encLong = maxOf(encLong, 128)
    encShort = maxOf(encShort, 128)
    
    return if (isPortrait) Pair(encShort, encLong) else Pair(encLong, encShort)
}

// 使用时验证
fun setupScreenCapture(projection: MediaProjection, screenW: Int, screenH: Int, dpi: Int) {
    val (encW, encH) = calculateEncodingSize(screenW, screenH)
    Log.d("Encode", "Screen: ${screenW}x${screenH} → Encode: ${encW}x${encH}")
    
    // VirtualDisplay 使用编码尺寸（而不是屏幕原始尺寸）
    val virtualDisplay = projection.createVirtualDisplay(
        "ScreenCapture",
        encW, encH, dpi,  // 宽高必须与编码器一致
        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
        surface, null, null
    )
}
```

------

## 十一、P11：punch_info 角色逻辑混淆

### 问题描述

根据架构报告的信令流程图，服务器向双方发送 `punch_info` 时：

- 向主控端发送 `role=controller`
- 向被控端发送 `role=controlled`

但 `SignalingClient` 处理 `punch_info` 时，如果角色判断逻辑错误，主控端会等待 Offer（误以为自己是被控端），被控端会发送 Offer（误以为自己是主控端），导致信令死锁。

### 修复方案

```kotlin
// ✅ SignalingClient 中明确的角色处理
private fun handlePunchInfo(json: JSONObject) {
    val role = json.getString("role")
    val peerIp = json.getString("peer_ip")
    val sessionKey = json.getString("session_key")
    
    Log.d("Signaling", "punch_info received: role=$role, peer=$peerIp")
    
    when (role) {
        "controller" -> {
            // 我是主控端，我应该发起 WebRTC Offer
            callbacks.onPunchInfoReceived(
                peerIp = peerIp,
                shouldInitiateOffer = true,  // 明确告知上层：我发 Offer
                sessionKey = sessionKey
            )
        }
        "controlled" -> {
            // 我是被控端，我等待主控端的 Offer
            callbacks.onPunchInfoReceived(
                peerIp = peerIp,
                shouldInitiateOffer = false,  // 明确告知上层：我等 Offer
                sessionKey = sessionKey
            )
        }
        else -> Log.e("Signaling", "Unknown role in punch_info: $role")
    }
}
```

------

## 十二、P12：PeerConnectionFactory 初始化线程问题

### 问题描述

`PeerConnectionFactory.initialize()` 和 `PeerConnectionFactory.builder().createPeerConnectionFactory()` 必须在有 `Looper` 的线程上调用，或者在 `Dispatchers.Main` 上调用。如果在 `Dispatchers.IO` 上调用会导致崩溃或 Native 层死锁。

### 修复方案

```kotlin
// ✅ 正确的 PeerConnectionFactory 初始化
class WebRtcClient(private val context: Context) {
    private lateinit var peerConnectionFactory: PeerConnectionFactory
    
    suspend fun initialize() = withContext(Dispatchers.Main) {
        // 必须在主线程初始化
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(BuildConfig.DEBUG)
                .createInitializationOptions()
        )
        
        val videoEncoderFactory = DefaultVideoEncoderFactory(
            eglBase.eglBaseContext,
            true,  // enableIntelVp8Encoder
            true   // enableH264HighProfile
        )
        val videoDecoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(videoEncoderFactory)
            .setVideoDecoderFactory(videoDecoderFactory)
            .setOptions(PeerConnectionFactory.Options().apply {
                disableEncryption = false
                disableNetworkMonitor = false
            })
            .createPeerConnectionFactory()
    }
}
```

------

## 十三、完整修复后的被控端启动流程

修复所有问题后，正确的被控端启动流程应为：

```
ControlledService.onCreate()
    │
    ├─ 1. 初始化 PeerConnectionFactory（主线程）
    │
    ├─ 2. 初始化 WebRtcClient（预创建 VideoSource + VideoTrack）
    │      └─ peerConnection.addTrack(videoTrack)  ← 关键！必须在协商前
    │
    ├─ 3. 请求 Root 权限（异步，等待完成）
    │      └─ Shell.getShell() → 成功后 grantPermissions()
    │
    ├─ 4. 获取 MediaProjection
    │      ├─ [Root 路径] DisplayManager.createVirtualDisplay() 直接创建
    │      └─ [非Root路径] 启动 MediaProjectionActivity，等待 Holder.get() 非空
    │
    ├─ 5. 连接信令服务器并注册设备
    │
    └─ 6. 等待 punch_info
           └─ 收到后等待主控端 SDP Offer
                  └─ 收到 Offer → setRemoteDescription
                         └─ onSetSuccess → 将捕获器绑定到 VideoSource
                                └─ capturer.startCapture()
                                       └─ createAnswer() → sendSdpAnswer()
```

------

## 十四、完整修复后的主控端显示流程

```
ControllerActivity.onCreate()
    │
    ├─ 1. 初始化 EglBase（单例）
    │
    ├─ 2. 初始化 SurfaceViewRenderer
    │      └─ 注册 RendererEvents，记录 isSurfaceReady = false
    │
    ├─ 3. 注册 VideoTrack 监听
    │      └─ 收到 VideoTrack 时：
    │            ├─ isSurfaceReady = true → 立即 addSink()
    │            └─ isSurfaceReady = false → 存入 pendingVideoTrack
    │
    ├─ 4. 当 SurfaceViewRenderer 首帧渲染完成
    │      └─ isSurfaceReady = true
    │            └─ pendingVideoTrack 非空 → addSink()
    │
    └─ 5. 触摸事件 → DataChannel → InputInjector（被控端）
```

------

## 十五、P10：安全问题修复

PIN 固定为 "123456" 的修复（次要但重要）：

```kotlin
// ✅ PairingManager.kt 修复版
class PairingManager(context: Context) {
    private val prefs = context.getSharedPreferences("pairing", Context.MODE_PRIVATE)
    
    // 生成真正随机的 6 位 PIN
    fun generateAndStorePin(): String {
        val pin = (100000..999999).random().toString()
        val salt = generateSalt()
        val hash = hashPin(pin, salt)
        prefs.edit()
            .putString("pin_hash", hash)
            .putString("pin_salt", salt)
            .apply()
        return pin  // 只返回明文 PIN 用于显示，不存储
    }
    
    fun verifyPin(inputPin: String): Boolean {
        val storedHash = prefs.getString("pin_hash", null) ?: return false
        val salt = prefs.getString("pin_salt", null) ?: return false
        return hashPin(inputPin, salt) == storedHash
    }
    
    private fun generateSalt() = ByteArray(16).also { 
        SecureRandom().nextBytes(it) 
    }.let { Base64.encodeToString(it, Base64.NO_WRAP) }
    
    private fun hashPin(pin: String, salt: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(Base64.decode(salt, Base64.NO_WRAP))
        return Base64.encodeToString(digest.digest(pin.toByteArray()), Base64.NO_WRAP)
    }
}
```

------

## 十六、修复优先级建议

按照"最小改动、最大效果"原则，建议按以下顺序修复：

| 优先级     | 修复项                               | 预期效果                |
| ---------- | ------------------------------------ | ----------------------- |
| **第1步**  | P1: 修复 addTrack 时机               | 解决黑屏（最关键）      |
| **第2步**  | P3: 修复 SurfaceViewRenderer 时序    | 解决黑屏（主控端侧）    |
| **第3步**  | P4: 修复 RootManager libsu 5.x API   | 解决 Root 权限失效      |
| **第4步**  | P5: 修复 MediaProjection 传递链      | 解决屏幕捕获失败        |
| **第5步**  | P2: 修复 Android 12+ MediaProjection | 解决 Android 12+ 黑屏   |
| **第6步**  | P6: 修复 ICE 候选队列                | 解决偶发连接失败        |
| **第7步**  | P11: 修复 punch_info 角色逻辑        | 解决信令死锁            |
| **第8步**  | P7: 添加 TURN 服务器                 | 解决严格 NAT 下连接失败 |
| **第9步**  | P9: 修复编码尺寸计算                 | 解决刘海屏设备视频异常  |
| **第10步** | P12: 修复 PeerConnectionFactory 线程 | 解决崩溃                |
| **第11步** | P8: 改善自动点击稳定性               | 提升用户体验            |
| **第12步** | P10: 修复 PIN 安全问题               | 安全加固                |

------

*分析完成。所有问题均源于 WebRTC 媒体协商时序、Android 系统 API 版本兼容性，以及 Root 权限获取流程的三个核心维度。按照上述优先级修复后，黑屏和 Root 权限失效问题应可完全解决。*