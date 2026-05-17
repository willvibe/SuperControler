package com.yourapp.remotectrl.root

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface

class ScreenCaptureManager(private val context: Context) {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private val displayManager = context.getSystemService(DisplayManager::class.java)

    fun startCaptureWithProjection(
        projection: MediaProjection,
        width: Int,
        height: Int,
        dpi: Int,
        encoderSurface: Surface
    ): Boolean {
        mediaProjection = projection
        return try {
            val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
            virtualDisplay = projection.createVirtualDisplay(
                "RemoteCtrlCapture",
                width,
                height,
                dpi,
                flags,
                encoderSurface,
                null,
                null
            )
            true
        } catch (e: Exception) {
            Log.e("ScreenCaptureManager", "createVirtualDisplay failed: ${e.message}")
            false
        }
    }

    fun startCapture(
        width: Int,
        height: Int,
        dpi: Int,
        encoderSurface: Surface
    ): Boolean {
        if (context.checkSelfPermission("android.permission.CAPTURE_VIDEO_OUTPUT")
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }

        return try {
            virtualDisplay = displayManager.createVirtualDisplay(
                "RemoteCtrlCapture",
                width,
                height,
                dpi,
                encoderSurface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
            )
            true
        } catch (e: Exception) {
            Log.e("ScreenCaptureManager", "createVirtualDisplay failed: ${e.message}")
            false
        }
    }

    fun stopCapture() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
    }

    fun stopCaptureAndProjection() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
    }

    fun getDisplayInfo(): Triple<Int, Int, Int> {
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        return Triple(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
    }
}