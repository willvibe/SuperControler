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

            Shell.cmd(
                "cp ${cacheFile.absolutePath} $TARGET_PATH",
                "chmod 777 $TARGET_PATH"
            ).exec()

            Shell.cmd("pkill -f 'com.yourapp.remotectrl.input.Main' 2>/dev/null || true").exec()
            Thread.sleep(200)

            val cmd = "app_process -Djava.class.path=$TARGET_PATH /system/bin com.yourapp.remotectrl.input.Main $sessionToken &"
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

    private fun sendRawCommand(cmd: String) {
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
            }
        }
    }

    fun tap(x: Int, y: Int) {
        if (!rootAvailable) { Log.w(TAG, "tap ignored - no root"); return }
        if (isSocketReady) {
            sendRawCommand("T,$x,$y")
        } else {
            Shell.cmd("input tap $x $y").submit()
        }
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 300) {
        if (!rootAvailable) { Log.w(TAG, "swipe ignored - no root"); return }
        if (isSocketReady) {
            sendRawCommand("S,$x1,$y1,$x2,$y2,$durationMs")
        } else {
            Shell.cmd("input swipe $x1 $y1 $x2 $y2 $durationMs").submit()
        }
    }

    fun longPress(x: Int, y: Int) {
        if (!rootAvailable) { Log.w(TAG, "longPress ignored - no root"); return }
        if (isSocketReady) {
            sendRawCommand("L,$x,$y")
        } else {
            Shell.cmd("input swipe $x $y $x $y 600").submit()
        }
    }

    fun scroll(x: Int, y: Int, dx: Int, dy: Int) {
        if (!rootAvailable) { Log.w(TAG, "scroll ignored - no root"); return }
        if (isSocketReady) {
            sendRawCommand("R,$x,$y,$dx,$dy")
        } else {
            Shell.cmd("input swipe $x $y ${x + dx} ${y + dy} 200").submit()
        }
    }

    fun key(keyCode: Int) {
        if (!rootAvailable) { Log.w(TAG, "key ignored - no root"); return }
        if (isSocketReady) {
            sendRawCommand("K,$keyCode")
        } else {
            Shell.cmd("input keyevent $keyCode").submit()
        }
    }

    fun back() = key(KeyEvent.KEYCODE_BACK)
    fun home() = key(KeyEvent.KEYCODE_HOME)
    fun recents() = key(KeyEvent.KEYCODE_APP_SWITCH)
    fun power() = key(KeyEvent.KEYCODE_POWER)

    fun inputText(text: String) {
        if (!rootAvailable) {
            Log.w(TAG, "inputText ignored - no root")
            return
        }
        val safeText = text.replace(" ", "%s")
        Shell.cmd("input text '$safeText'").submit { result ->
            if (!result.isSuccess) {
                Log.w(TAG, "input text failed: ${result.err}, falling back to clipboard")
                inputTextFallback(text)
            }
        }
    }

    private fun inputTextFallback(text: String) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("remote_input", text)
            clipboard.setPrimaryClip(clip)
            if (rootAvailable) {
                Shell.cmd("input keyevent 279").submit { result ->
                    if (!result.isSuccess) {
                        Log.w(TAG, "inputTextFallback keyevent failed: ${result.err}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "inputText fallback failed: ${e.message}")
        }
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
