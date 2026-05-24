package com.yourapp.remotectrl.root

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import android.view.KeyEvent
import com.topjohnwu.superuser.Shell
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.UUID

class InputInjector(private val context: Context) {

    companion object {
        private const val TAG = "InputInjector"
        private const val SOCKET_NAME = "supercontroler_input"
        private const val PAYLOAD_JAR = "input-server.jar"
        private const val TARGET_PATH = "/data/local/tmp/input-server.jar"
        private const val TARGET_SO_PATH = "/data/local/tmp/libuinput-ctrl.so"
    }

    private val rootAvailable: Boolean
        get() = RootManager.isRootAvailable()

    private var localSocket: LocalSocket? = null
    private var outStream: OutputStream? = null
    private val sessionToken = UUID.randomUUID().toString()
    @Volatile
    private var isSocketReady = false
    private val socketLock = Object()

    init {
        Log.i(TAG, "InputInjector created, rootAvailable=$rootAvailable")
        if (rootAvailable) {
            deployAndStartPayload()
        }
    }

    private fun deployAndStartPayload() {
        try {
            val assetManager = context.assets
            val cacheFile = File(context.cacheDir, PAYLOAD_JAR)
            assetManager.open(PAYLOAD_JAR).use { input ->
                FileOutputStream(cacheFile).use { output ->
                    input.copyTo(output)
                }
            }

            val soFile = File(context.applicationInfo.nativeLibraryDir, "libuinput-ctrl.so")
            val soExists = soFile.exists()
            Log.i(TAG, "Native SO exists: $soExists at ${soFile.absolutePath}")

            val commands = mutableListOf(
                "cp ${cacheFile.absolutePath} $TARGET_PATH",
                "chmod 777 $TARGET_PATH"
            )
            if (soExists) {
                commands.add("cp ${soFile.absolutePath} $TARGET_SO_PATH")
                commands.add("chmod 777 $TARGET_SO_PATH")
            }

            Shell.cmd(*commands.toTypedArray()).exec()

            Shell.cmd("pkill -f 'com.yourapp.remotectrl.input.Main' 2>/dev/null || true").exec()
            Thread.sleep(200)

            val displayMetrics = context.resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels

            val cmd = if (soExists) {
                "(app_process -Djava.class.path=$TARGET_PATH /system/bin com.yourapp.remotectrl.input.Main $sessionToken $screenWidth $screenHeight > /dev/null 2>&1 &)"
            } else {
                "(app_process -Djava.class.path=$TARGET_PATH /system/bin com.yourapp.remotectrl.input.Main $sessionToken > /dev/null 2>&1 &)"
            }
            Shell.cmd(cmd).submit()

            Thread {
                var retries = 0
                while (retries < 10 && !isSocketReady) {
                    Thread.sleep(500)
                    try {
                        connectSocket()
                        isSocketReady = true
                        Log.i(TAG, "Ghost process socket connected and authenticated.")
                        break
                    } catch (e: Exception) {
                        retries++
                        Log.w(TAG, "Socket connect attempt $retries failed: ${e.message}")
                    }
                }
                if (!isSocketReady) {
                    Log.e(TAG, "Failed to connect to ghost process after 10 attempts")
                }
            }.start()

        } catch (e: Exception) {
            Log.e(TAG, "Deploy payload failed: ${e.message}")
        }
    }

    private fun connectSocket() {
        localSocket = LocalSocket()
        localSocket?.connect(LocalSocketAddress(SOCKET_NAME))
        outStream = localSocket?.outputStream
        sendRawCommand(sessionToken)
    }

    private fun ensureSocketConnected() {
        synchronized(socketLock) {
            if (isSocketReady && outStream != null) return
            try {
                connectSocket()
                isSocketReady = true
            } catch (e: Exception) {
                Log.w(TAG, "Socket reconnect failed: ${e.message}")
                isSocketReady = false
                outStream = null
            }
        }
    }

    // 【修复6】增加 retryCount 参数实现自动重试
    private fun sendRawCommand(cmd: String, retryCount: Int = 0) {
        synchronized(socketLock) {
            if (outStream == null) {
                try {
                    connectSocket()
                    isSocketReady = true
                } catch (e: Exception) {
                    Log.e(TAG, "Socket not available, falling back to shell: ${e.message}")
                    isSocketReady = false
                    outStream = null
                    return
                }
            }
            try {
                outStream?.write((cmd + "\n").toByteArray())
                outStream?.flush()
            } catch (e: Exception) {
                Log.e(TAG, "Socket write failed, marking disconnected: ${e.message}")
                isSocketReady = false
                outStream = null
                localSocket = try { localSocket?.close(); null } catch (_: Exception) { null }
                // 【修复6】重连并重试一次指令
                if (retryCount < 1) {
                    sendRawCommand(cmd, retryCount + 1)
                } else {
                    // do nothing, retry exhausted
                }
            }
        }
    }

    fun tap(x: Int, y: Int) {
        if (!rootAvailable) { Log.w(TAG, "tap ignored - no root"); return }
        if (isSocketReady) {
            sendRawCommand("T,$x,$y")
        } else {
            Log.e(TAG, "Socket未就绪，已拦截 Tap 事件，拒绝使用框架层注入")
        }
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 300) {
        if (!rootAvailable) { Log.w(TAG, "swipe ignored - no root"); return }
        if (isSocketReady) {
            sendRawCommand("S,$x1,$y1,$x2,$y2,$durationMs")
        } else {
            Log.e(TAG, "Socket未就绪，已拦截 Swipe 事件")
        }
    }

    fun longPress(x: Int, y: Int) {
        if (!rootAvailable) { Log.w(TAG, "longPress ignored - no root"); return }
        if (isSocketReady) {
            sendRawCommand("L,$x,$y")
        } else {
            Log.e(TAG, "Socket未就绪，已拦截 LongPress 事件")
        }
    }

    fun scroll(x: Int, y: Int, dx: Int, dy: Int) {
        if (!rootAvailable) { Log.w(TAG, "scroll ignored - no root"); return }
        if (isSocketReady) {
            sendRawCommand("R,$x,$y,$dx,$dy")
        } else {
            Log.e(TAG, "Socket未就绪，已拦截 Scroll 事件")
        }
    }

    fun key(keyCode: Int) {
        if (!rootAvailable) { Log.w(TAG, "key ignored - no root"); return }
        if (isSocketReady) {
            sendRawCommand("K,$keyCode")
        } else {
            Log.e(TAG, "Socket未就绪，已拦截 Key 事件")
        }
    }

    fun back() = key(KeyEvent.KEYCODE_BACK)
    fun home() = key(KeyEvent.KEYCODE_HOME)
    fun recents() = key(KeyEvent.KEYCODE_APP_SWITCH)
    fun power() = key(KeyEvent.KEYCODE_POWER)

    fun inputText(text: String) {
        Log.e(TAG, "纯内核级注入模式下，禁止使用系统框架级的 input text 命令。请通过模拟点击屏幕软键盘输入文本。")
    }

    fun destroy() {
        try {
            outStream?.close()
        } catch (_: Exception) {}
        try {
            localSocket?.close()
        } catch (_: Exception) {}
        outStream = null
        localSocket = null
        isSocketReady = false
        Shell.cmd("pkill -f 'com.yourapp.remotectrl.input.Main' 2>/dev/null || true").submit()
        Log.i(TAG, "InputInjector destroyed, ghost process killed")
    }
}
