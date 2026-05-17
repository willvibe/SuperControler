package com.yourapp.remotectrl.root

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogExporter {

    private const val TAG = "LogExporter"

    fun exportLogs(context: Context, onResult: (Boolean, String) -> Unit) {
        Thread {
            try {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val fileName = "supercontroler_log_$timestamp.txt"

                val dir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    File(context.getExternalFilesDir(null), "SuperControler")
                } else {
                    File(Environment.getExternalStorageDirectory(), "SuperControler")
                }

                if (!dir.exists()) {
                    dir.mkdirs()
                }

                val logFile = File(dir, fileName)
                val writer = FileWriter(logFile)

                writer.write("===== SuperControler Log Export =====\n")
                writer.write("Time: ${Date()}\n")
                writer.write("Device: ${Build.MODEL} (${Build.VERSION.RELEASE})\n")
                writer.write("Package: ${context.packageName}\n")
                writer.write("=====================================\n\n")

                val process = Runtime.getRuntime().exec("logcat -d -s ControlledService Signaling WebRtcClient ControllerActivity ControllerService MainActivity RootManager MediaProjection")
                process.inputStream.bufferedReader().use { reader ->
                    reader.lineSequence().forEach { line ->
                        writer.write(line)
                        writer.write("\n")
                    }
                }

                writer.write("\n===== End of Log Export =====\n")
                writer.close()

                Log.i(TAG, "Log exported to: ${logFile.absolutePath}")
                onResult(true, logFile.absolutePath)
            } catch (e: Exception) {
                Log.e(TAG, "Export failed: ${e.message}")
                onResult(false, e.message ?: "Unknown error")
            }
        }.start()
    }
}
