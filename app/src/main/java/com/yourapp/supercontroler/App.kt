package com.yourapp.supercontroler

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.topjohnwu.superuser.Shell
import com.yourapp.remotectrl.root.RootManager
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class App : Application() {

    companion object {
        const val PREFS_NAME = "app_config"
        const val KEY_MODE = "app_mode"
        const val KEY_AUTO_START = "auto_start"
        private const val TAG = "App"
    }

    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    override fun onCreate() {
        super.onCreate()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "=== UNCAUGHT EXCEPTION on thread: ${thread.name} ===", throwable)
            try {
                val crashDir = File(getExternalFilesDir(null), "crash_logs")
                crashDir.mkdirs()
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val crashFile = File(crashDir, "crash_${timestamp}.log")
                FileWriter(crashFile, true).use { writer ->
                    PrintWriter(writer).use { pw ->
                        pw.println("=== Crash at ${Date()} ===")
                        pw.println("Thread: ${thread.name}")
                        pw.println()
                        throwable.printStackTrace(pw)
                        pw.println()
                    }
                }
                Log.i(TAG, "Crash log written to: ${crashFile.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write crash log: ${e.message}")
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }

        Shell.enableVerboseLogging = BuildConfig.DEBUG
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(30)
        )

        Handler(Looper.getMainLooper()).postDelayed({
            autoStartServiceIfNeeded()
        }, 3000)
    }

    private fun autoStartServiceIfNeeded() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val autoStart = prefs.getBoolean(KEY_AUTO_START, true)
        if (!autoStart) {
            Log.i(TAG, "Auto-start disabled")
            return
        }

        val mode = prefs.getString(KEY_MODE, "controlled") ?: "controlled"

        if (RootManager.isRequestInProgress()) {
            Log.i(TAG, "Root request in progress, delaying auto-start...")
            Handler(Looper.getMainLooper()).postDelayed({
                autoStartServiceIfNeeded()
            }, 3000)
            return
        }

        Log.i(TAG, "Auto-starting service in mode: $mode")

        try {
            if (mode == "controlled") {
                com.yourapp.remotectrl.controlled.ControlledService.start(this)
            } else {
                com.yourapp.remotectrl.controller.ControllerService.start(this)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Auto-start failed: ${e.message}")
        }
    }
}
