package com.mahjong.assistant.util

import android.os.Environment
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * 持久化文件日志 — 闪退后仍可读取
 * 优先写入: /sdcard/Download/mahjong_log.txt
 * 兜底:     /data/data/<pkg>/files/logs/mahjong.log
 */
object FLog {
    private var writer: PrintWriter? = null
    private var started = false
    private var logPath = ""
    private val sdf = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(privateDir: File) {
        if (started) return
        try {
            // 优先尝试公共 Download 目录 (用户可直接访问)
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            var logFile: File? = null

            if (downloadDir.exists() || downloadDir.mkdirs()) {
                logFile = File(downloadDir, "mahjong_log.txt")
                try {
                    if (logFile.exists() && logFile.length() > 512_000) {
                        logFile.delete() // 超500KB清空重来
                    }
                    writer = PrintWriter(FileWriter(logFile, true), true)
                    logPath = logFile.absolutePath
                } catch (_: Exception) {}
            }

            // Download 不可用时，兜底写私有目录
            if (writer == null) {
                val logDir = File(privateDir, "logs")
                if (!logDir.exists()) logDir.mkdirs()
                logFile = File(logDir, "mahjong.log")
                writer = PrintWriter(FileWriter(logFile, true), true)
                logPath = logFile.absolutePath
            }

            started = true
            i("FLog", "日志路径: $logPath")
        } catch (e: Exception) {
            android.util.Log.e("FLog", "初始化失败", e)
        }
    }

    @Synchronized
    fun i(tag: String, msg: String) {
        val line = "${now()} I/$tag: $msg"
        android.util.Log.i(tag, msg)
        write(line)
    }

    @Synchronized
    fun e(tag: String, msg: String, t: Throwable? = null) {
        val sb = StringBuilder("${now()} E/$tag: $msg")
        if (t != null) {
            sb.append("\n")
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            sb.append(sw.toString())
        }
        val line = sb.toString()
        android.util.Log.e(tag, msg, t)
        write(line)
    }

    @Synchronized
    fun w(tag: String, msg: String) {
        val line = "${now()} W/$tag: $msg"
        android.util.Log.w(tag, msg)
        write(line)
    }

    private fun now() = sdf.format(Date())

    @Synchronized
    private fun write(line: String) {
        try {
            writer?.let {
                it.println(line)
                it.flush()
            }
        } catch (_: Exception) {}
    }

    fun shutdown() {
        started = false
        try { writer?.close() } catch (_: Exception) {}
        writer = null
    }
}
