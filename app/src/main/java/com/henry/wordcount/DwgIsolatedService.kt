package com.henry.wordcount

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.File

/**
 * v1.9.38: 隔离 LibreDWG 原生 dwg2dxf 调用到独立进程(:dwgisolated)。
 *
 * 背景：LibreDWG 的 dwg_read_file / dwg_write_pdf 在部分 CAD 文件上会触发 native SIGSEGV
 * （空指针/越界读未压缩对象流），native 崩溃会直接杀掉整个 app 进程，Kotlin try/catch 无法捕获 → 闪退。
 * 把该函数放到独立进程，崩溃只杀死该进程；主进程通过 Messenger 调用，带超时（10min）兜底：
 *   超时/崩溃 → 主进程收到失败结果 → 走降级，app 不再闪退。
 *
 * v1.9.31a 已验证：每次转换后 stopSelf 销毁 :dwgisolated，下一文件重新拉起全新进程，
 * 是恢复 4万+/无 0 字的必要条件。v1.9.36 为去掉通知栏而改为纯 bindService，结果
 * :dwgisolated 没有前台优先级，后台转换不稳定 → 字数掉回 3.6万。本版恢复前台化。
 *
 * 注意：本 service 运行在 :dwgisolated 进程，Application.onCreate 已跳过 Python 初始化，
 * 这里只加载 libdwg2dxf.so 并调用 dwg2dxf，不依赖任何 Python/Compose。
 */
class DwgIsolatedService : Service() {

    private lateinit var messenger: Messenger
    private val mainHandler = Handler(Looper.getMainLooper())

    // v1.9.31a: 最后一位客户端解绑后延迟 10s 自停。覆盖批次内文件间的快速重绑。
    private var idleStopRunnable: Runnable? = null

    /** v1.9.19: 息屏后防 Doze 挂起 dwg2dxf native 调用。 */
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        messenger = Messenger(IncomingHandler())
        Log.d("DwgIsolated", "isolated service created (pid=${android.os.Process.myPid()})")
    }

    override fun onBind(intent: Intent?): IBinder {
        idleStopRunnable?.let { mainHandler.removeCallbacks(it) }
        idleStopRunnable = null
        return messenger.binder
    }

    override fun onRebind(intent: Intent?) {
        idleStopRunnable?.let { mainHandler.removeCallbacks(it) }
        idleStopRunnable = null
    }

    override fun onUnbind(intent: Intent?): Boolean {
        idleStopRunnable = Runnable { try { stopSelf() } catch (_: Throwable) {} }
        mainHandler.postDelayed(idleStopRunnable!!, 10_000L)
        return true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // v1.9.11/38: 前台化本进程。切后台时 Android 14+ 会冻结 cached 进程；
        // 只有 :dwgisolated 自己也是前台服务，LibreDWG native 调用才不被冻结/节流，
        // 这是输出稳定 DXF（恢复 4万+字数）的必要条件。
        startForegroundCompat()
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        // v1.9.19: 本进程也持 PARTIAL_WAKE_LOCK
        if (wakeLock == null) {
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WordCount:dwg2dxf")
                wl.setReferenceCounted(false)
                wl.acquire(60 * 60 * 1000L)
                wakeLock = wl
            } catch (e: Throwable) {
                Log.w("DwgIsolated", "acquire wakelock failed: ${e.message}")
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = getSystemService(NotificationManager::class.java)
                // v1.9.38: IMPORTANCE_MIN 让 DWG 转换通知尽可能低调，仅在转换期间存在。
                val ch = NotificationChannel(CHANNEL_ID, "DWG转换中", NotificationManager.IMPORTANCE_MIN)
                ch.setShowBadge(false)
                ch.description = "后台持续进行 DWG 转换"
                nm.createNotificationChannel(ch)
            }
            // v1.9.55: 隔离进程必须保持前台优先级，但不再向用户展示额外的“DWG 转换进行中”通知，
            // 只保留 CountingService 的统计进度通知与完成通知。
            val noti: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("")
                .setContentText("")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setSilent(true)
                .build()

            ServiceCompat.startForeground(
                this,
                NOTI_ID,
                noti,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            )
            foregroundOk = true
        } catch (e: Throwable) {
            foregroundOk = false
            lastError = "${e.javaClass.simpleName}: ${e.message}"
            Log.w("DwgIsolated", "startForeground 失败 → stopSelf: ${e.message}")
            try { stopSelf() } catch (_: Throwable) {}
        }
    }

    override fun onDestroy() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Throwable) {}
        wakeLock = null
        super.onDestroy()
    }

    private inner class IncomingHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_CONVERT_DXF -> {
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
                    // v1.9.31a/38: 每次转换后销毁进程，下一文件重新拉起全新进程，
                    // 杜绝 LibreDWG 全局状态污染导致的 0 字/乱字数。
                    try { stopSelf() } catch (_: Throwable) {}
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
                        Log.e("DwgIsolated", "convertToPdf threw: ${e.message}", e)
                        bundle.putInt(KEY_RC, -99)
                        bundle.putString(KEY_DIAG, "exception: ${e.message}")
                        bundle.putString(KEY_PATH, null)
                    }
                    val resp = Message.obtain(null, MSG_RESULT).apply { this.data = bundle }
                    try { replyTo?.send(resp) } catch (e: Throwable) {
                        Log.e("DwgIsolated", "reply failed: ${e.message}")
                    }
                    try { stopSelf() } catch (_: Throwable) {}
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

        @Volatile var foregroundOk: Boolean = false
        @Volatile var lastError: String? = null

        fun stopService(ctx: Context) {
            try { ctx.stopService(Intent(ctx, DwgIsolatedService::class.java)) } catch (_: Throwable) {}
        }
    }
}
