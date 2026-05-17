package com.yourapp.remotectrl.network

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object ConnectionState {

    const val STATUS_IDLE = "idle"
    const val STATUS_CONNECTING = "connecting"
    const val STATUS_REGISTERED = "registered"
    const val STATUS_CONNECTED = "connected"
    const val STATUS_ERROR = "error"

    @Volatile
    private var currentStatus: String = STATUS_IDLE
    @Volatile
    private var currentMessage: String = ""

    private val ownerStatuses = ConcurrentHashMap<String, Pair<String, String>>()

    private val lock = ReentrantLock()

    var onStatusChange: ((String, String) -> Unit)? = null

    var onOwnerStatusChange: ((owner: String, status: String, message: String) -> Unit)? = null

    fun getStatus(): String = currentStatus
    fun getMessage(): String = currentMessage

    fun getStatusForOwner(owner: String): String {
        return ownerStatuses[owner]?.first ?: STATUS_IDLE
    }

    fun getMessageForOwner(owner: String): String {
        return ownerStatuses[owner]?.second ?: ""
    }

    fun update(status: String, message: String = "", owner: String? = null) {
        lock.withLock {
            currentStatus = status
            currentMessage = message
            if (owner != null) {
                ownerStatuses[owner] = Pair(status, message)
            }
        }
        onStatusChange?.invoke(status, message)
        if (owner != null) {
            onOwnerStatusChange?.invoke(owner, status, message)
        }
    }

    fun reset(owner: String? = null) {
        lock.withLock {
            currentStatus = STATUS_IDLE
            currentMessage = ""
            if (owner != null) {
                ownerStatuses.remove(owner)
            }
        }
        onStatusChange?.invoke(STATUS_IDLE, "")
        if (owner != null) {
            onOwnerStatusChange?.invoke(owner, STATUS_IDLE, "")
        }
    }

    fun forceReset() {
        lock.withLock {
            currentStatus = STATUS_IDLE
            currentMessage = ""
            ownerStatuses.clear()
        }
        onStatusChange?.invoke(STATUS_IDLE, "")
    }
}
