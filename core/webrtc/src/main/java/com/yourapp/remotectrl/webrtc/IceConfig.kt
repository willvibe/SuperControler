package com.yourapp.remotectrl.webrtc

import org.webrtc.PeerConnection

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
}
