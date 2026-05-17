package com.yourapp.supercontroler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON" ||
            intent.action == "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            val prefs = context.getSharedPreferences("app_config", Context.MODE_PRIVATE)
            val autoStart = prefs.getBoolean("auto_start", true)
            if (!autoStart) {
                Log.i("BootReceiver", "Auto-start disabled")
                return
            }

            val mode = prefs.getString("app_mode", "controlled") ?: "controlled"
            Log.i("BootReceiver", "Boot completed, starting service in mode: $mode")

            if (mode == "controlled") {
                com.yourapp.remotectrl.controlled.ControlledService.start(context)
            } else {
                com.yourapp.remotectrl.controller.ControllerService.start(context)
            }
        }
    }
}
