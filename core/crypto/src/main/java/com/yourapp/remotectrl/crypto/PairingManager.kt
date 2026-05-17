package com.yourapp.remotectrl.crypto

import android.content.Context
import java.security.MessageDigest

object PairingManager {

    private const val PREFS_NAME = "pairing"
    private const val KEY_PIN = "pin_hash"

    fun initPin(context: Context, pin: String = generatePin()): String {
        val hash = hashPin(pin)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_PIN, hash).apply()
        return pin
    }

    fun verifyPin(context: Context, inputPin: String): Boolean {
        val storedHash = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PIN, null) ?: return false
        return hashPin(inputPin) == storedHash
    }

    private fun generatePin(): String = (100000..999999).random().toString()

    private fun hashPin(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(("remotectrl:$pin").toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
