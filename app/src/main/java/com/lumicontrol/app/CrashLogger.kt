package com.lumicontrol.app

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashLogger {
    private var logFile: File? = null

    fun init(ctx: Context) {
        logFile = File(ctx.filesDir, "crash.log")
        try { logFile?.createNewFile() } catch (_: Exception) {}
    }

    fun log(tag: String, message: String, throwable: Throwable? = null) {
        try {
            val file = logFile ?: return
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val sb = StringBuilder()
            sb.append("=== $timestamp [$tag] $message ===\n")
            if (throwable != null) {
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                throwable.printStackTrace(pw)
                pw.flush()
                sb.append(sw.toString())
            }
            sb.append("\n")
            file.appendText(sb.toString())
        } catch (_: Exception) {}
    }

    fun getLog(): String {
        val file = logFile ?: return "暂无日志"
        return try {
            if (!file.exists()) return "暂无日志"
            file.readText()
        } catch (_: Exception) { "读取日志失败" }
    }

    fun clear() {
        try { logFile?.writeText("") } catch (_: Exception) {}
    }
}
