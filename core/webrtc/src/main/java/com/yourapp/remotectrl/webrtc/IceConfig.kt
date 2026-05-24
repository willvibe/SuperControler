package com.yourapp.remotectrl.webrtc

import org.webrtc.PeerConnection
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

object IceConfig {

    data class TurnServer(
        val url: String,
        val username: String = "",
        val password: String = ""
    )

    var stunServers = listOf(
        "stun:stun.l.google.com:19302",
        "stun:stun1.l.google.com:19302"
    )

    var turnServers = listOf<TurnServer>()

    var turnApiUrl = "https://101.33.80.14:8765/turn"

    fun buildIceServers(): List<PeerConnection.IceServer> {
        val servers = mutableListOf<PeerConnection.IceServer>()

        for (stunUrl in stunServers) {
            servers.add(
                PeerConnection.IceServer.builder(stunUrl).createIceServer()
            )
        }

        for (turn in turnServers) {
            servers.add(
                PeerConnection.IceServer.builder(turn.url)
                    .setUsername(turn.username)
                    .setPassword(turn.password)
                    .createIceServer()
            )
        }

        return servers
    }

    fun fetchIceServersFromApi(): Boolean {
        if (turnApiUrl.isEmpty()) return false
        try {
            val url = URL(turnApiUrl)
            val conn = (if (turnApiUrl.startsWith("https")) url.openConnection() as HttpsURLConnection else url.openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                instanceFollowRedirects = true
            }

            if (turnApiUrl.startsWith("https")) {
                val sc = SSLContext.getInstance("TLS")
                sc.init(null, arrayOf<TrustManager>(object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                }), java.security.SecureRandom())
                (conn as HttpsURLConnection).sslSocketFactory = sc.socketFactory
                (conn as HttpsURLConnection).hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            }

            if (conn.responseCode != 200) {
                conn.disconnect()
                return false
            }

            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val json = JSONObject(body)
            val iceArray = json.getJSONArray("iceServers")
            val newTurnServers = mutableListOf<TurnServer>()
            val newStunServers = mutableListOf<String>()

            for (i in 0 until iceArray.length()) {
                val server = iceArray.getJSONObject(i)
                val urls = server.optJSONArray("urls")
                val singleUrl = server.optString("urls", "")
                val username = server.optString("username", "")
                val credential = server.optString("credential", "")

                val urlList = mutableListOf<String>()
                if (urls != null) {
                    for (j in 0 until urls.length()) {
                        urlList.add(urls.getString(j))
                    }
                } else if (singleUrl.isNotEmpty()) {
                    urlList.add(singleUrl)
                }

                for (u in urlList) {
                    if (u.startsWith("turn:") || u.startsWith("turns:")) {
                        newTurnServers.add(TurnServer(u, username, credential))
                    } else if (u.startsWith("stun:")) {
                        newStunServers.add(u)
                    }
                }
            }

            if (newStunServers.isNotEmpty()) {
                stunServers = newStunServers
            }
            turnServers = newTurnServers
            return true
        } catch (e: Exception) {
            return false
        }
    }
}
