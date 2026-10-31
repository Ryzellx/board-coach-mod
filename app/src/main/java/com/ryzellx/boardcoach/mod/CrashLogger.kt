package com.ryzellx.boardcoach.mod

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Penangkap crash sederhana: menulis stacktrace ke
 * /sdcard/Android/data/<pkg>/files/boardcoach-crash.txt
 * supaya user bisa kirim isinya alih-alih cuma "telah berhenti".
 */
object CrashLogger {

    fun install(context: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val dir = context.getExternalFilesDir(null) ?: context.filesDir
                File(dir, "boardcoach-crash.txt").appendText(
                    "\n===== $ts =====\n$sw\n"
                )
            }
            prev?.uncaughtException(t, e)
        }
    }

    fun log(context: Context, tag: String, e: Throwable) {
        runCatching {
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            File(dir, "boardcoach-error.txt").appendText("\n[$tag] $sw\n")
        }
    }

    fun path(context: Context): String {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(dir, "boardcoach-crash.txt").absolutePath
    }
}
