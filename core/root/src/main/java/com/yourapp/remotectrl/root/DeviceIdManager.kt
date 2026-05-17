package com.yourapp.remotectrl.root

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest

object DeviceIdManager {

    fun getDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences("device", Context.MODE_PRIVATE)
        var id = prefs.getString("id", null)
        if (id != null) return id

        val androidId = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ANDROID_ID
        ) ?: "unknown"

        val raw = buildString {
            append(androidId)
            append(Build.BOARD)
            append(Build.BOOTLOADER)
            append(Build.HARDWARE)
        }

        id = sha256(raw).take(12).uppercase()
        prefs.edit().putString("id", id).apply()
        return id
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
