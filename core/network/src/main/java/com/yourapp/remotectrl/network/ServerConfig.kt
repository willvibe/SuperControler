package com.yourapp.remotectrl.network

import android.content.Context
import android.content.SharedPreferences

object ServerConfig {

    private const val PREFS_NAME = "server_config"
    private const val KEY_SERVER_URL = "server_url"
    private const val DEFAULT_URL = "wss://launch.cc.cd:8765/ws"

    private var cachedUrl: String? = null

    fun getServerUrl(context: Context): String {
        cachedUrl?.let { return it }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val url = prefs.getString(KEY_SERVER_URL, DEFAULT_URL) ?: DEFAULT_URL
        cachedUrl = url
        return url
    }

    fun setServerUrl(context: Context, url: String) {
        cachedUrl = url
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_SERVER_URL, url).apply()
    }

    fun clearCache() {
        cachedUrl = null
    }
}