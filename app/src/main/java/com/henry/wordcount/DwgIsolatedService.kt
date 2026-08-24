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
import android.os.PowerManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
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

    /** v1.9.19: 息屏后防 Doze 挂起 dwg2dxf native 调用。 */
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        // 注意：本进程不初始化 Python（Application 已跳过）。只加载 native 库。
        messenger = Messenger(IncomingHandler())
        // v1.9.18: 任何创建路径都立即前台化，确保 :dwgisolated 不被 Android 14+ 冻结。
        startForegroundCompat()
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
        // v1.9.18: 不再用 10s 短空闲自停——统计批次内主进程 Python 抽取常 >10s，
        // 短自停会让 :dwgisolated 在切后台后被 Android 14+ 禁止前台重启→"切后台不统计"。
        // 改为长空闲(10min)安全网；正常由 DwgIsolatedRunner.stopIsolated 在 addFiles finally 显式停止。
        idleStopRunnable = Runnable { try { stopSelf() } catch (_: Throwable) {} }
        mainHandler.postDelayed(idleStopRunnable!!, 600_000L)
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
            // v1.9.19: 不能再"忽略"。startForegroundService 建立了 5 秒契约，
            // startForeground 失败且不 stopSelf → 系统抛 ForegroundServiceDidNotStartInTimeException 杀进程。
            // stopSelf 只解除契约；客户端已 BIND_AUTO_CREATE，实例仍存活可继续处理转换消息。
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

    /** v1.9.31: 每文件转换完成后延迟杀掉本隔离进程，强制下一个文件通过
     *  DwgIsolatedRunner.startForegroundService+bindService 拉起全新干净进程，
     *  避免 LibreDWG dwg2dxf 在同一进程连跑多文件后全局状态污染导致的字数波动/0字。
     *  延迟 300ms：保证回复 Messenger 已写入 kernel binder 缓冲(对端主进程必能收到)，
     *  再 Process.killProcess 销毁本进程(:dwgisolated 仅承载 dwg2dxf，杀掉无副作用)。
     *  进程死亡触发主进程 onServiceDisconnected → started=false → 下一文件重新前台拉起。 */
    private fun killAfterReply() {
        mainHandler.postDelayed({
            try { Process.killProcess(Process.myPid()) } catch (_: Throwable) {}
        }, 300L)
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
                    // v1.9.31: 每文件转换完成后延迟销毁隔离进程，强制下一个文件拿到全新干净进程。
                    // LibreDWG 的 dwg2dxf 在同一 :dwgisolated 进程连跑多个 DWG 后全局状态会污染，
                    // 之后产出的 DXF 损坏/无 EOF → 该文件判 0 字或乱字数，且"哪些文件坏掉"随批次顺序
                    // 变化 → 反复波动(同一 RAR 多次统计数字不一致)。延迟 300ms 确保回传的 Messenger
                    // 回复已送达主进程(kernel binder 缓冲已写入)再杀进程。
                    // 主进程在统计期间常驻 WordCountForegroundService 前台服务(见 MainActivity.addFiles)，
                    // 故下一个文件重新 startForegroundService 在后台也被允许，不会回归"切后台不统计"。
                    killAfterReply()
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
                    // v1.9.31: 同 MSG_CONVERT_DXF 分支，每文件延迟杀进程拿干净 LibreDWG 状态。
                    killAfterReply()
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
