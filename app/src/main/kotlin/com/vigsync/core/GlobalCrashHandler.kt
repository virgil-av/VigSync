package com.vigsync.core

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*
import kotlin.system.exitProcess

class GlobalCrashHandler(private val context: Context) : Thread.UncaughtExceptionHandler {
    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
    private val crashFile = File(context.filesDir, "last_crash.txt")

    init {
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(t: Thread, e: Throwable) {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        e.printStackTrace(pw)
        val stackTrace = sw.toString()

        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val crashReport = """
            TIMESTAMP: $timestamp
            THREAD: ${t.name}
            EXCEPTION: ${e.javaClass.simpleName}
            MESSAGE: ${e.message}
            STACKTRACE:
            $stackTrace
        """.trimIndent()

        try {
            crashFile.writeText(crashReport)
            Log.e("GlobalCrashHandler", "Crash saved to ${crashFile.absolutePath}")
        } catch (ioe: Exception) {
            Log.e("GlobalCrashHandler", "Failed to save crash report", ioe)
        }

        // Call original handler or exit
        if (defaultHandler != null) {
            defaultHandler.uncaughtException(t, e)
        } else {
            exitProcess(1)
        }
    }

    fun checkAndLogLastCrash() {
        if (crashFile.exists()) {
            val report = crashFile.readText()
            Log.e("VigSync", "PREVIOUS CRASH DETECTED:\n$report")
        }
    }
}
