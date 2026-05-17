package com.yourapp.remotectrl.root

import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.view.KeyEvent
import com.topjohnwu.superuser.Shell

class InputInjector(private val context: Context) {

    private val rootAvailable: Boolean
        get() = RootManager.isRootAvailable()

    init {
        Log.i("InputInjector", "InputInjector created, RootManager.isRootAvailable=${RootManager.isRootAvailable()}")
    }

    fun tap(x: Int, y: Int) {
        if (rootAvailable) execRoot("input tap $x $y")
        else Log.w("InputInjector", "tap ignored - no root")
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 300) {
        if (rootAvailable) execRoot("input swipe $x1 $y1 $x2 $y2 $durationMs")
        else Log.w("InputInjector", "swipe ignored - no root")
    }

    fun longPress(x: Int, y: Int) {
        if (rootAvailable) execRoot("input swipe $x $y $x $y 600")
        else Log.w("InputInjector", "longPress ignored - no root")
    }

    fun scroll(x: Int, y: Int, dx: Int, dy: Int) {
        if (rootAvailable) execRoot("input swipe $x $y ${x + dx} ${y + dy} 200")
        else Log.w("InputInjector", "scroll ignored - no root")
    }

    fun key(keyCode: Int) {
        if (rootAvailable) execRoot("input keyevent $keyCode")
        else Log.w("InputInjector", "key ignored - no root")
    }

    fun back() = key(KeyEvent.KEYCODE_BACK)
    fun home() = key(KeyEvent.KEYCODE_HOME)
    fun recents() = key(KeyEvent.KEYCODE_APP_SWITCH)
    fun power() = key(KeyEvent.KEYCODE_POWER)

    fun inputText(text: String) {
        if (!rootAvailable) {
            Log.w("InputInjector", "inputText ignored - no root")
            return
        }
        val escaped = shellEscape(text)
        try {
            Shell.cmd("am broadcast -a ADB_INPUT_TEXT --es text $escaped").submit { result ->
                if (!result.isSuccess) {
                    inputTextFallback(text)
                }
            }
        } catch (e: Exception) {
            Log.w("InputInjector", "inputText failed: ${e.message}")
            inputTextFallback(text)
        }
    }

    private fun inputTextFallback(text: String) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = android.content.ClipData.newPlainText("remote_input", text)
            clipboard.setPrimaryClip(clip)
            if (rootAvailable) {
                Shell.cmd("input keyevent 279").submit {}
            }
        } catch (e: Exception) {
            Log.e("InputInjector", "inputText fallback failed: ${e.message}")
        }
    }

    private fun shellEscape(text: String): String {
        val sb = StringBuilder("'")
        for (ch in text) {
            when (ch) {
                '\'' -> sb.append("'\\''")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(ch)
            }
        }
        sb.append("'")
        return sb.toString()
    }

    private fun execRoot(cmd: String) {
        try {
            Shell.cmd(cmd).submit { result ->
                if (!result.isSuccess) {
                    Log.w("InputInjector", "Failed: $cmd -> ${result.err}")
                }
            }
        } catch (e: Exception) {
            Log.w("InputInjector", "execRoot failed: $cmd -> ${e.message}")
        }
    }
}
