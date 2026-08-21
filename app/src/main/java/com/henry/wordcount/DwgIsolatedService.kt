package com.henry.wordcount

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File

/**
 * v1.5.13: 隔离 LibreDWG 原生 dwg2pdf 调用到独立进程(:dwgisolated)。
 *
 * 背景：LibreDWG 的 dwg_read_file / dwg_write_pdf 在部分 CAD 文件上会触发 native SIGSEGV
 * （空指针/越界读未压缩对象流），native 崩溃会直接杀掉整个 app 进程，Kotlin try/catch 无法捕获 → 闪退。
 * 把该函数放到独立进程，崩溃只杀死该进程；主进程通过 Messenger 调用，带超时（35s）兜底：
 *   超时/崩溃 → 主进程收到空结果 → 走降级（raw scan 结果 或 提示转换失败），app 不再闪退。
 *
 * 注意：本 service 运行在 :dwgisolated 进程，Application.onCreate 已跳过 Python 初始化，
 * 这里只加载 libdwg2dxf.so 并调用 dwg2pdf，不依赖任何 Python/Compose。
 */
class DwgIsolatedService : Service() {

    private lateinit var messenger: Messenger
    private val mainHandler = Handler(Looper.getMainLooper())
    // v1.9.12: 最后一位客户端解绑后，延迟 10s 自停。覆盖批次内文件间的快速重绑（间隔 < 10s），
    // 避免每次转换后 stopSelf 使 :dwgisolated 进程在切后台时失去前台优先级被 Android 冻结。
    private var idleStopRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        // 注意：本进程不初始化 Python（Application 已跳过）。只加载 native 库。
        messenger = Messenger(IncomingHandler())
        Log.d("DwgIsolated", "isolated service created (pid=${android.os.Process.myPid()})")
    }

    override fun onBind(intent: Intent?): IBinder {
        // 取消待定的空闲自停，保持进程存活以覆盖连续转换批次
        idleStopRunnable?.let { mainHandler.removeCallbacks(it) }
        idleStopRunnable = null
        return messenger.binder
    }

    override fun onRebind(intent: Intent?) {
        idleStopRunnable?.let { mainHandler.removeCallbacks(it) }
        idleStopRunnable = null
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // 最后一位客户端解绑 → 延迟自停；若 10s 内重绑（下个文件转换）则取消
        idleStopRunnable = Runnable { try { stopSelf() } catch (_: Throwable) {} }
        mainHandler.postDelayed(idleStopRunnable!!, 10_000L)
        return true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // v1.9.11: 前台化本进程。切后台时 Android 14+ 会冻结 cached 进程；主进程的
        // 前台 service 不会提升 :dwgisolated 子进程的优先级，导致 dwg2dxf native 调用卡死。
        // 这里在转换开始前（startService 时 app 必在前台）让本进程自己 startForeground，
        // 保证整个转换期间 :dwgisolated 处于前台优先级，不被冻结/杀。
        startForegroundCompat()
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = getSystemService(NotificationManager::class.java)
                val ch = NotificationChannel(CHANNEL_ID, "DWG转换中", NotificationManager.IMPORTANCE_LOW)
                ch.setShowBadge(false)
                ch.description = "后台持续进行 DWG 转换"
                nm.createNotificationChannel(ch)
            }
            val noti: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("WordCount 正在处理")
                .setContentText("DWG 转换进行中")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build()
            startForeground(NOTI_ID, noti)
        } catch (e: Throwable) {
            Log.w("DwgIsolated", "startForeground 失败(忽略): ${e.message}")
        }
    }

    private inner class IncomingHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_CONVERT_DXF -> {
                    // v1.5.16: DWG→DXF（字数统计主路径）。dwg2dxf 同为 LibreDWG 原生调用，
                    // 可能在某些文件上 native 崩溃，故也放在隔离进程。
                    val data = msg.data
                    val input = data.getString(KEY_INPUT) ?: ""
                    val output = data.getString(KEY_OUTPUT) ?: ""
                    val replyTo = msg.replyTo
                    val bundle = Bundle()
                    try {
                        val res = DwgConverter.convert(input, output)
                        bundle.putInt(KEY_RC, res.errorCode)
                        bundle.putString(KEY_DIAG, res.diagText)
                        bundle.putString(KEY_PATH, res.path)
                    } catch (e: Throwable) {
                        Log.e("DwgIsolated", "convert(dwg2dxf) threw: ${e.message}", e)
                        bundle.putInt(KEY_RC, -99)
                        bundle.putString(KEY_DIAG, "exception: ${e.message}")
                        bundle.putString(KEY_PATH, null)
                    }
                    val resp = Message.obtain(null, MSG_RESULT).apply { this.data = bundle }
                    try { replyTo?.send(resp) } catch (e: Throwable) {
                        Log.e("DwgIsolated", "reply failed: ${e.message}")
                    }
                    // v1.9.12: 不再每次转换后销毁进程。保留前台优先级，避免切后台时 :dwgisolated
                    // 进程被 Android 冻结导致 dwg2dxf 卡死。进程由 onUnbind 空闲延迟(10s)自停，
                    // 连续转换批次期间持续存活。native 崩溃仍由进程死亡 + 主进程重绑兜底。
                }
                MSG_CONVERT -> {
                    val data = msg.data
                    val input = data.getString(KEY_INPUT) ?: ""
                    val output = data.getString(KEY_OUTPUT) ?: ""
                    val replyTo = msg.replyTo
                    val bundle = Bundle()
                    try {
                        val res = DwgConverter.convertToPdf(input, output)
                        bundle.putInt(KEY_RC, res.errorCode)
                        bundle.putString(KEY_DIAG, res.diagText)
                        bundle.putString(KEY_PATH, res.path)
                    } catch (e: Throwable) {
                        // 理论上 convertToPdf 内部已 try/catch；若仍有异常（极少见）也安全回传
                        Log.e("DwgIsolated", "convertToPdf threw: ${e.message}", e)
                        bundle.putInt(KEY_RC, -99)
                        bundle.putString(KEY_DIAG, "exception: ${e.message}")
                        bundle.putString(KEY_PATH, null)
                    }
                    val resp = Message.obtain(null, MSG_RESULT).apply { this.data = bundle }
                    try {
                        replyTo?.send(resp)
                    } catch (e: Throwable) {
                        Log.e("DwgIsolated", "reply failed: ${e.message}")
                    }
                    // v1.9.12: 同上，保留进程存活（空闲延迟自停）。
                }
                else -> super.handleMessage(msg)
            }
        }
    }

    companion object {
        const val MSG_CONVERT = 1
        const val MSG_CONVERT_DXF = 3
        const val MSG_RESULT = 2
        const val KEY_INPUT = "input"
        const val KEY_OUTPUT = "output"
        const val KEY_RC = "rc"
        const val KEY_DIAG = "diag"
        const val KEY_PATH = "path"
        const val CHANNEL_ID = "wordcount_dwg_convert"
        const val NOTI_ID = 200
    }
}
