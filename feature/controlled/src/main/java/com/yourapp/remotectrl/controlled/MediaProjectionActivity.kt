package com.yourapp.remotectrl.controlled

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.topjohnwu.superuser.Shell
import com.yourapp.remotectrl.root.RootManager

class MediaProjectionActivity : Activity() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val ACTION_MEDIA_PROJECTION_RESULT = "MEDIA_PROJECTION_RESULT"
        private const val REQUEST_MEDIA_PROJECTION = 1001
        private const val TAG = "MediaProjectionActivity"
    }

    private var autoClickAttempts = 0
    private var hasResult = false
    private var handler: Handler? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handler = Handler(Looper.getMainLooper())
        requestMediaProjection()
    }

    private fun requestMediaProjection() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)

        if (RootManager.isRootAvailable()) {
            Log.i(TAG, "Root available, scheduling auto-click")
            scheduleAutoClick()
        } else {
            Log.w(TAG, "No root, user must manually click '立即开始'")
        }
    }

    private fun scheduleAutoClick() {
        val h = handler ?: return

        val delays = longArrayOf(
            2000, 3000, 4000, 5000, 6000, 7000, 8000, 10000, 12000, 15000
        )
        for (delay in delays) {
            h.postDelayed({
                if (!isFinishing && !isDestroyed && !hasResult) {
                    performAutoClick()
                }
            }, delay)
        }
    }

    private fun performAutoClick() {
        autoClickAttempts++
        if (hasResult) return
        if (!RootManager.isRootAvailable()) return

        Log.i(TAG, "Auto-click attempt #$autoClickAttempts")

        if (autoClickAttempts == 1) {
            val pos = findButtonPositionViaUiAutomator()
            if (pos != null) {
                Log.i(TAG, "Found button via uiautomator at ($pos), tapping...")
                Shell.cmd("input tap ${pos.first} ${pos.second}").exec()
                return
            }
        }

        val dm = resources.displayMetrics
        val width = dm.widthPixels
        val height = dm.heightPixels

        val tapX = (width * 0.78).toInt()
        val tapY = (height * 0.88).toInt()
        Log.i(TAG, "Tapping at ($tapX, $tapY) on ${width}x${height}")
        Shell.cmd("input tap $tapX $tapY").exec()

        if (autoClickAttempts >= 2) {
            Shell.cmd("input keyevent KEYCODE_TAB").exec()
            Shell.cmd("input keyevent KEYCODE_TAB").exec()
            Shell.cmd("input keyevent KEYCODE_ENTER").exec()
        }

        if (autoClickAttempts >= 4) {
            Shell.cmd("input keyevent KEYCODE_DPAD_RIGHT").exec()
            Shell.cmd("input keyevent KEYCODE_DPAD_RIGHT").exec()
            Shell.cmd("input keyevent KEYCODE_ENTER").exec()
        }

        if (autoClickAttempts >= 6) {
            Shell.cmd("input keyevent KEYCODE_DPAD_DOWN").exec()
            Shell.cmd("input keyevent KEYCODE_DPAD_RIGHT").exec()
            Shell.cmd("input keyevent KEYCODE_ENTER").exec()
        }
    }

    private fun findButtonPositionViaUiAutomator(): Pair<Int, Int>? {
        if (!RootManager.isRootAvailable()) return null
        try {
            Log.i(TAG, "Finding button position via uiautomator...")
            val result = Shell.cmd("uiautomator dump /dev/tty 2>/dev/null").exec()
            val output = result.out.joinToString("\n")
            Log.i(TAG, "UI dump length: ${output.length}")

            val patterns = listOf(
                Regex("""bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*text="立即开始""""),
                Regex("""text="立即开始"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]""""),
                Regex("""bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*text="Start now""""),
                Regex("""text="Start now"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]""""),
                Regex("""bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*text="Start""""),
                Regex("""text="Start"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"""")
            )

            for (pattern in patterns) {
                val match = pattern.find(output)
                if (match != null) {
                    val g = match.groupValues
                    val x1 = g[1].toIntOrNull() ?: continue
                    val y1 = g[2].toIntOrNull() ?: continue
                    val x2 = g[3].toIntOrNull() ?: continue
                    val y2 = g[4].toIntOrNull() ?: continue

                    val centerX = (x1 + x2) / 2
                    val centerY = (y1 + y2) / 2

                    Log.i(TAG, "Found button at [$x1,$y1][$x2,$y2], center=($centerX,$centerY)")
                    return Pair(centerX, centerY)
                }
            }

            Log.w(TAG, "Button not found in UI dump")
        } catch (e: Exception) {
            Log.w(TAG, "uiautomator failed: ${e.message}")
        }
        return null
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        hasResult = true
        handler?.removeCallbacksAndMessages(null)
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                Log.i(TAG, "Permission granted (resultCode=$resultCode)")
                MediaProjectionHelper.savePermission(resultCode, data)
                MediaProjectionHelper.markGranted(this)

                try {
                    val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    val projection = mpm.getMediaProjection(resultCode, data)
                    if (projection != null) {
                        Log.i(TAG, "MediaProjection created immediately in Activity, storing in holder")
                        MediaProjectionHolder.set(projection)
                    } else {
                        Log.w(TAG, "getMediaProjection returned null in Activity")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create MediaProjection in Activity: ${e.message}")
                }

                val resultIntent = Intent(ACTION_MEDIA_PROJECTION_RESULT).apply {
                    putExtra(EXTRA_RESULT_CODE, resultCode)
                    putExtra(EXTRA_RESULT_DATA, data)
                    setClass(this@MediaProjectionActivity, ControlledService::class.java)
                }
                startForegroundServiceSafely(resultIntent)
            } else {
                Log.w(TAG, "Permission denied (resultCode=$resultCode)")
                val cancelIntent = Intent(ACTION_MEDIA_PROJECTION_RESULT).apply {
                    putExtra(EXTRA_RESULT_CODE, resultCode)
                    setClass(this@MediaProjectionActivity, ControlledService::class.java)
                }
                startForegroundServiceSafely(cancelIntent)
            }
            finish()
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun startForegroundServiceSafely(intent: Intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler?.removeCallbacksAndMessages(null)
        hasResult = true
    }
}
