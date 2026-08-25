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

    // v1.9.35: 恢复每文件转换后 stopSelf(v1.9.31a 已验证可恢复 4万+字数)。
    // 10s 延迟用于覆盖同一批次内相邻文件的快速重绑（若重绑则取消）。
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
        // v1.9.19: 本进程也持 PARTIAL_WAKE_LOCK —— dwg2dxf 是 native 长任务，
        // 息屏进 Doze 时会被挂起，导致"切后台不统计"。
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
                .setSilent(true)
                .build()

            // v1.9.19: targetSdk 34 必须显式传与 manifest 一致的 dataSync 类型，
            // 否则抛异常 → 未履行 startForegroundService 的 5 秒契约 → 系统杀进程。
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
            Log.w("DwgIsolated", "startForeground 失败 → stopSelf 解除契约: ${e.message}")
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
                    // v1.9.35: 恢复 v1.9.31a 的「每文件转换后 stopSelf」。
                    // v1.9.12 为保持前台优先级移除 stopSelf，导致同一 :dwgisolated 进程连跑
                    // 多个 dwg2dxf 后 LibreDWG 全局状态污染 → 后续文件产出损坏/无 EOF 的 DXF →
                    // 该文件判 0 字或乱字数(手机从 4万+ 掉到 3.4万、且逐次运行结果反复波动)。
                    // 每文件转换后销毁进程，下一个文件重新 startService+bindService 拉起全新干净
                    // 进程，LibreDWG 状态不累积污染。后台不被冻结由本进程自己的 startForeground
                    // + 主进程 CountingService 的 WakeLock 共同保证。
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
                    // v1.9.35: 同 MSG_CONVERT_DXF，转换后立即销毁进程以杜绝 LibreDWG 状态污染。
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

        /** v1.9.19: 前台化诊断（本进程内可见，崩溃排查用）。 */
        @Volatile var foregroundOk: Boolean = false
        @Volatile var lastError: String? = null

        /** v1.9.18: 主进程显式停止 :dwgisolated（addFiles 结束后调用）。 */
        fun stopService(ctx: Context) {
            try { ctx.stopService(Intent(ctx, DwgIsolatedService::class.java)) } catch (_: Throwable) {}
        }
    }
}
