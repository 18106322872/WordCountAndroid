package com.henry.wordcount

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v1.9.65: 应用内诊断日志。
 *
 * 把统计流水线（DWG 解析 / OLE / IMAGE OCR / RAR 内层 / cad_core 调用等）的关键日志
 * 同时落盘到 cacheDir/wc_diag_<进程标签>.log。按进程分文件，避免 :main / :countservice
 * / :dwgisolated 多进程写同一文件竞争。每个进程日志封顶 2MB（超出清空重写，保留近期）。
 *
 * 用户无需 adb：统计完点主界面 TopAppBar 的「导出诊断」图标，即把合并后的日志通过系统
 * 分享面板发到微信 / 邮件等，便于远程定位回归（如 RAR 整包字数偏低、FA-31018 仍 0 字）。
 */
object Diag {
    private var ctx: Context? = null
    private var procLabel: String = "main"
    private val writers = mutableMapOf<String, BufferedWriter?>()
    private val lock = Any()
    private const val MAX_FILE = 2_000_000L

    fun init(context: Context) {
        try {
            val app = context.applicationContext
            ctx = app
            val pid = android.os.Process.myPid()
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val name = am?.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
            procLabel = name ?: "pid$pid"
        } catch (_: Throwable) {
            procLabel = "pid${android.os.Process.myPid()}"
        }
    }

    private fun logFile(label: String): File {
        val c = ctx ?: return File("/dev/null")
        return File(c.cacheDir, "wc_diag_$label.log")
    }

    private fun writerFor(label: String): BufferedWriter? {
        synchronized(lock) {
            val f = logFile(label)
            if (f.exists() && f.length() > MAX_FILE) {
                try { f.writeText("") } catch (_: Throwable) {}
            }
            val w = writers[label] ?: run {
                try { BufferedWriter(FileWriter(f, true)) } catch (_: Throwable) { null }
            }
            writers[label] = w
            return w
        }
    }

    fun d(msg: String) { Log.d("WordCount", msg); write("D", msg) }
    fun w(msg: String) { Log.w("WordCount", msg); write("W", msg) }
    fun e(msg: String, tr: Throwable? = null) {
        if (tr != null) Log.e("WordCount", msg, tr) else Log.e("WordCount", msg)
        val extra = tr?.let { "\n" + Log.getStackTraceString(it) } ?: ""
        write("E", msg + extra)
    }

    private fun write(level: String, msg: String) {
        val c = ctx ?: return
        val ts = try {
            SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        } catch (_: Throwable) { "?" }
        val line = "[$ts][$procLabel][$level] $msg\n"
        synchronized(lock) {
            val w = writerFor(procLabel) ?: return
            try { w.write(line); w.flush() } catch (_: Throwable) {}
        }
    }

    /** 合并所有 wc_diag_*.log 为单个导出文件，并通过 FileProvider 唤起系统分享面板 */
    fun exportAndShare(context: Context) {
        val c = context.applicationContext
        init(c)
        val ver = try { c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "?" } catch (_: Throwable) { "?" }
        val files = (c.cacheDir.listFiles { _, name ->
            name.startsWith("wc_diag_") && name.endsWith(".log")
        } ?: emptyArray()).sortedBy { it.name }
        val out = File(c.cacheDir, "wc_diag_export.log")
        try {
            out.bufferedWriter().use { bw ->
                bw.write("WordCount 诊断日志导出 @ ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n")
                bw.write("版本: v$ver\n")
                bw.write("进程标签: main=主界面, :countservice=统计服务, :dwgisolated=DWG转换隔离进程\n\n")
                if (files.isEmpty()) {
                    bw.write("(无诊断日志)\n")
                }
                for (f in files) {
                    bw.write("========== ${f.name} (${f.length()} bytes) ==========\n")
                    try {
                        f.forEachLine { bw.write(it + "\n") }
                    } catch (e: Throwable) {
                        bw.write("(读取失败: ${e.message})\n")
                    }
                    bw.write("\n")
                }
            }
        } catch (e: Throwable) {
            Log.e("WordCount", "诊断日志合并失败: ${e.message}", e)
            return
        }
        val uri = try {
            FileProvider.getUriForFile(c, c.packageName + ".fileprovider", out)
        } catch (e: Throwable) {
            Log.e("WordCount", "诊断日志分享失败(FileProvider): ${e.message}", e)
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "WordCount 诊断日志")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            c.startActivity(Intent.createChooser(intent, "导出诊断日志"))
        } catch (e: Throwable) {
            Log.e("WordCount", "启动分享面板失败: ${e.message}", e)
        }
    }
}
