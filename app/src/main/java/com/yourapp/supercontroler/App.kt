package com.yourapp.supercontroler

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.topjohnwu.superuser.Shell
import com.yourapp.remotectrl.root.RootManager

class App : Application() {

    companion object {
        const val PREFS_NAME = "app_config"
        const val KEY_MODE = "app_mode"
        const val KEY_AUTO_START = "auto_start"
    }

    override fun onCreate() {
        super.onCreate()

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
            Log.i("App", "Auto-start disabled")
            return
        }

        val mode = prefs.getString(KEY_MODE, "controlled") ?: "controlled"

        if (RootManager.isRequestInProgress()) {
            Log.i("App", "Root request in progress, delaying auto-start...")
            Handler(Looper.getMainLooper()).postDelayed({
                autoStartServiceIfNeeded()
            }, 3000)
            return
        }

        Log.i("App", "Auto-starting service in mode: $mode")

        try {
            if (mode == "controlled") {
                com.yourapp.remotectrl.controlled.ControlledService.start(this)
            } else {
                com.yourapp.remotectrl.controller.ControllerService.start(this)
            }
        } catch (e: Exception) {
            Log.e("App", "Auto-start failed: ${e.message}")
        }
    }
}
