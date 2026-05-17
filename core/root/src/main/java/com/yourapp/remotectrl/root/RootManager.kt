package com.yourapp.remotectrl.root

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.topjohnwu.superuser.Shell
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

object RootManager {

    private val rootAvailable = AtomicBoolean(false)
    private val permissionsGranted = AtomicBoolean(false)
    private val initComplete = AtomicBoolean(false)
    private val requestInProgress = AtomicBoolean(false)
    private val pendingCallbacks = java.util.concurrent.CopyOnWriteArrayList<(Boolean) -> Unit>()

    var onRootStatusChanged: ((Boolean) -> Unit)? = null
    var onRootGranted: (() -> Unit)? = null

    fun isRootAvailable(): Boolean = rootAvailable.get()
    fun arePermissionsGranted(): Boolean = permissionsGranted.get()
    fun isInitComplete(): Boolean = initComplete.get()
    fun isRequestInProgress(): Boolean = requestInProgress.get()

    fun initAsync(context: Context, onComplete: ((Boolean) -> Unit)? = null) {
        Log.i("RootManager", "initAsync() called, rootAvailable=${rootAvailable.get()}, initComplete=${initComplete.get()}")

        if (rootAvailable.get()) {
            Log.i("RootManager", "Root already available, reusing cached result")
            onComplete?.invoke(true)
            return
        }

        val cachedShell = Shell.getCachedShell()
        if (cachedShell != null) {
            Log.i("RootManager", "initAsync: cached shell exists, isRoot=${cachedShell.isRoot}, status=${cachedShell.status}")
            if (cachedShell.isRoot) {
                rootAvailable.set(true)
                initComplete.set(true)
                grantHiddenPermissions(context)
                notifyRootStatus(true)
                onComplete?.invoke(true)
                invokeCallbacks(true)
                return
            }
        }

        Log.i("RootManager", "initAsync: root not available, will not trigger su popup - user should click requestRoot")
        initComplete.set(true)
        notifyRootStatus(false)
        onComplete?.invoke(false)
    }

    fun requestRoot(context: Context, onComplete: ((Boolean) -> Unit)? = null) {
        Log.i("RootManager", "requestRoot() called, current rootAvailable=${rootAvailable.get()}, requestInProgress=${requestInProgress.get()}")

        if (rootAvailable.get()) {
            onComplete?.invoke(true)
            return
        }

        if (!requestInProgress.compareAndSet(false, true)) {
            Log.w("RootManager", "requestRoot already in progress, queueing callback")
            if (onComplete != null) {
                pendingCallbacks.add(onComplete)
            }
            return
        }

        initComplete.set(false)

        val cachedShell = Shell.getCachedShell()
        if (cachedShell != null) {
            Log.i("RootManager", "requestRoot: cached shell exists, isRoot=${cachedShell.isRoot}")
            if (cachedShell.isRoot) {
                requestInProgress.set(false)
                rootAvailable.set(true)
                initComplete.set(true)
                grantHiddenPermissions(context)
                notifyRootStatus(true)
                onComplete?.invoke(true)
                return
            }
            Log.w("RootManager", "Closing cached non-root shell to force new root request")
            try {
                cachedShell.close()
            } catch (e: Exception) {
                Log.w("RootManager", "Failed to close cached non-root shell: ${e.message}")
            }
        }

        val callbackInvoked = AtomicBoolean(false)
        val timeoutHandler = Handler(Looper.getMainLooper())
        val timeoutRunnable = Runnable {
            if (callbackInvoked.compareAndSet(false, true)) {
                Log.w("RootManager", "requestRoot timeout (60s), assuming no root")
                requestInProgress.set(false)
                initComplete.set(true)
                rootAvailable.set(false)
                notifyRootStatus(false)
                onComplete?.invoke(false)
                invokeCallbacks(false)
            }
        }
        timeoutHandler.postDelayed(timeoutRunnable, 60_000)

        Log.i("RootManager", "Method 1: Shell.getShell() async (libsu 5.x recommended)...")
        Shell.getShell { shell ->
            Log.i("RootManager", "Method 1 result: isRoot=${shell.isRoot}, status=${shell.status}")
            if (shell.isRoot) {
                timeoutHandler.removeCallbacks(timeoutRunnable)
                if (callbackInvoked.compareAndSet(false, true)) {
                    requestInProgress.set(false)
                    rootAvailable.set(true)
                    initComplete.set(true)
                    grantHiddenPermissions(context)
                    notifyRootStatus(true)
                    onComplete?.invoke(true)
                    invokeCallbacks(true)
                }
                return@getShell
            }

            Log.i("RootManager", "Method 1 returned non-root shell, trying Method 2...")
            try {
                shell.close()
            } catch (_: Exception) {}

            Thread {
                diagnoseSuEnvironment()

                try {
                    Log.i("RootManager", "Method 2: Shell.Builder.create().build(\"su\")...")
                    val shell2 = Shell.Builder.create()
                        .setFlags(Shell.FLAG_REDIRECT_STDERR)
                        .setTimeout(30)
                        .build("su")

                    Log.i("RootManager", "Method 2 result: isRoot=${shell2.isRoot}, status=${shell2.status}")
                    if (shell2.isRoot) {
                        timeoutHandler.removeCallbacks(timeoutRunnable)
                        if (callbackInvoked.compareAndSet(false, true)) {
                            requestInProgress.set(false)
                            rootAvailable.set(true)
                            initComplete.set(true)
                            grantHiddenPermissions(context)
                            Handler(Looper.getMainLooper()).post {
                                notifyRootStatus(true)
                                onComplete?.invoke(true)
                                invokeCallbacks(true)
                            }
                        }
                        return@Thread
                    }
                } catch (e: Exception) {
                    Log.w("RootManager", "Method 2 failed: ${e.javaClass.simpleName} - ${e.message}")
                }

                try {
                    Log.i("RootManager", "Method 3: Runtime.exec(\"su\")...")
                    val process = Runtime.getRuntime().exec("su")
                    val os = DataOutputStream(process.outputStream)
                    os.writeBytes("id\n")
                    os.flush()
                    os.writeBytes("exit\n")
                    os.flush()

                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    val errReader = BufferedReader(InputStreamReader(process.errorStream))
                    var uid0 = false
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        Log.i("RootManager", "su stdout: $line")
                        if (line?.contains("uid=0") == true) uid0 = true
                    }
                    while (errReader.readLine().also { line = it } != null) {
                        Log.i("RootManager", "su stderr: $line")
                    }
                    reader.close()
                    errReader.close()
                    os.close()

                    val exitCode = process.waitFor()
                    Log.i("RootManager", "Method 3 result: exitCode=$exitCode, uid0=$uid0")

                    if (uid0) {
                        timeoutHandler.removeCallbacks(timeoutRunnable)
                        if (callbackInvoked.compareAndSet(false, true)) {
                            requestInProgress.set(false)
                            rootAvailable.set(true)
                            initComplete.set(true)
                            grantHiddenPermissions(context)
                            Handler(Looper.getMainLooper()).post {
                                notifyRootStatus(true)
                                onComplete?.invoke(true)
                                invokeCallbacks(true)
                            }
                        }
                        return@Thread
                    }
                } catch (e: Exception) {
                    Log.w("RootManager", "Method 3 failed: ${e.javaClass.simpleName} - ${e.message}")
                }

                Log.e("RootManager", "All root request methods failed")
                timeoutHandler.removeCallbacks(timeoutRunnable)
                if (callbackInvoked.compareAndSet(false, true)) {
                    requestInProgress.set(false)
                    initComplete.set(true)
                    rootAvailable.set(false)
                    Handler(Looper.getMainLooper()).post {
                        notifyRootStatus(false)
                        onComplete?.invoke(false)
                        invokeCallbacks(false)
                    }
                }
            }.start()
        }
    }

    private fun diagnoseSuEnvironment() {
        Log.i("RootManager", "=== SU Environment Diagnostics ===")

        val suPaths = arrayOf(
            "/system/xbin/su", "/system/bin/su", "/sbin/su",
            "/su/bin/su", "/magisk/.core/bin/su",
            "/debug_ramdisk/su", "/data/adb/magisk/su",
            "/data/adb/ksu/bin/su", "/data/adb/ap/bin/su"
        )
        for (path in suPaths) {
            val file = java.io.File(path)
            Log.i("RootManager", "  $path: exists=${file.exists()}, canExecute=${file.canExecute()}, canRead=${file.canRead()}")
        }

        try {
            val whichProcess = Runtime.getRuntime().exec(arrayOf("which", "su"))
            val reader = BufferedReader(InputStreamReader(whichProcess.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                Log.i("RootManager", "  which su: $line")
            }
            reader.close()
            whichProcess.waitFor()
        } catch (e: Exception) {
            Log.w("RootManager", "  which su failed: ${e.message}")
        }

        try {
            val envProcess = Runtime.getRuntime().exec(arrayOf("sh", "-c", "echo \$PATH"))
            val reader = BufferedReader(InputStreamReader(envProcess.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                Log.i("RootManager", "  PATH: $line")
            }
            reader.close()
            envProcess.waitFor()
        } catch (e: Exception) {
            Log.w("RootManager", "  PATH check failed: ${e.message}")
        }

        try {
            val magiskProcess = Runtime.getRuntime().exec(arrayOf("sh", "-c", "ls -la /data/adb/magisk/ 2>/dev/null || echo 'magisk dir not accessible'"))
            val reader = BufferedReader(InputStreamReader(magiskProcess.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                Log.i("RootManager", "  magisk: $line")
            }
            reader.close()
            magiskProcess.waitFor()
        } catch (e: Exception) {
            Log.w("RootManager", "  magisk check failed: ${e.message}")
        }

        Log.i("RootManager", "=== End SU Diagnostics ===")
    }

    private fun notifyRootStatus(hasRoot: Boolean) {
        try {
            onRootStatusChanged?.invoke(hasRoot)
        } catch (e: Exception) {
            Log.w("RootManager", "notifyRootStatus error: ${e.message}")
        }
    }

    private fun invokeCallbacks(result: Boolean) {
        val callbacks = ArrayList(pendingCallbacks)
        pendingCallbacks.clear()
        for (cb in callbacks) {
            try {
                cb.invoke(result)
            } catch (e: Exception) {
                Log.w("RootManager", "pendingCallback error: ${e.message}")
            }
        }
    }

    private fun grantHiddenPermissions(context: Context) {
        try {
            val packageName = context.packageName
            Shell.cmd(
                "pm grant $packageName android.permission.CAPTURE_VIDEO_OUTPUT",
                "pm grant $packageName android.permission.CAPTURE_SECURE_VIDEO_OUTPUT",
                "pm grant $packageName android.permission.WRITE_SECURE_SETTINGS",
                "dumpsys deviceidle whitelist +$packageName",
                "settings put global app_standby_enabled 0"
            ).submit { result ->
                val success = result.isSuccess
                permissionsGranted.set(success)
                Log.i("RootManager", "Hidden permissions granted: $success, out=${result.out}, err=${result.err}")
                if (success) {
                    try {
                        onRootGranted?.invoke()
                    } catch (e: Exception) {
                        Log.w("RootManager", "onRootGranted callback error: ${e.message}")
                    }
                }
                grantMiuiOptimizations(context)
            }
        } catch (e: Exception) {
            Log.w("RootManager", "grantHiddenPermissions failed: ${e.message}")
        }
    }

    private fun grantMiuiOptimizations(context: Context) {
        try {
            val packageName = context.packageName
            Shell.cmd(
                "settings put secure miui_optimization_whitelist $packageName 1",
                "dumpsys deviceidle whitelist +$packageName",
                "dumpsys battery unplug"
            ).submit { result ->
                Log.i("RootManager", "MIUI optimizations: ${result.isSuccess}")
            }
        } catch (e: Exception) {
            Log.w("RootManager", "MIUI optimizations failed: ${e.message}")
        }
    }

    fun checkRootSync(): Boolean {
        if (rootAvailable.get()) return true

        val cachedShell = Shell.getCachedShell()
        if (cachedShell != null && cachedShell.isRoot) {
            rootAvailable.set(true)
            initComplete.set(true)
            return true
        }

        return false
    }
}
