package com.henry.wordcount

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * 前台 service —— 让 MainActivity 切到后台时整个进程不被系统冻结/回收，
 * 从而 dwg2dxf / OLE 抽取等耗时 IO 协程可以持续跑完。
 *
 * 设计：service 本身不做任何文件处理；只提供前台通知 + WakeLock，让 app 进程保持
 * 前台优先级且 CPU 不休眠。addFiles 等逻辑仍在 MainActivity 内执行
 * （用 ProcessLifecycleOwner.lifecycleScope）。
 *
 * 触发：MainActivity 启动 addFiles 时 start()；addFiles 完成（不论成功/失败）后 stop()。
 *
 * ============ v1.9.19 后台不统计的三处根治 ============
 * v1.9.10~v1.9.18 反复修隔离进程逻辑均无效，因为前台 service 其实一直是"形同虚设"：
 *
 * ① 【通知权限缺失】manifest 从未声明 POST_NOTIFICATIONS，也从未运行时申请。
 *    Android 13+ 默认不授予该权限 → 前台 service 的通知**根本不显示**。
 *    国产 ROM 会把"无可见通知的前台服务"当普通后台进程处理，切后台立刻冻结。
 *    → v1.9.19 已在 manifest 声明，并由 MainActivity 在 onCreate 运行时申请。
 *
 * ② 【CPU 休眠】没有任何 WakeLock。切后台 + 息屏后 CPU 进入 Doze，
 *    dwg2dxf / Python 抽取线程被挂起，统计停在原地。
 *    → v1.9.19 在 onStartCommand 获取 PARTIAL_WAKE_LOCK，onDestroy 释放。
 *
 * ③ 【startForeground 静默失败】旧代码用 try/catch 吞掉异常，并注释"进程仍保有前台优先级"，
 *    这个假设是错的：startForeground 一旦抛异常，service 根本没有前台化，而
 *    ContextCompat.startForegroundService 的 5 秒契约未履行，系统会紧接着抛
 *    ForegroundServiceDidNotStartInTimeException **杀掉整个进程**（该异常由系统在
 *    主线程抛出，不在 startForeground 调用栈内，原 try/catch 抓不到）。
 *    → v1.9.19 改用 ServiceCompat.startForeground 显式传 FOREGROUND_SERVICE_TYPE_DATA_SYNC
 *      （targetSdk 34 必须与 manifest 的 foregroundServiceType 一致），失败时记录原因到
 *      lastError 供 UI 展示，并立即 stopSelf() 主动解除 5 秒契约，避免进程被系统杀死。
 */
class WordCountForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "wordcount_stats"
        const val NOTI_ID = 100

        /** v1.9.19: 前台化/WakeLock 是否成功；失败原因回传 UI，便于真机定位后台问题。 */
        @Volatile
        var lastError: String? = null

        /** v1.9.19: 诊断——前台化是否已生效（真机排查"切后台不统计"用）。 */
        @Volatile
        var foregroundOk: Boolean = false

        /** v1.9.19: 诊断——WakeLock 是否已持有。 */
        @Volatile
        var wakeLockOk: Boolean = false

        fun start(ctx: Context) {
            val i = Intent(ctx, WordCountForegroundService::class.java)
            try {
                ContextCompat.startForegroundService(ctx, i)
            } catch (e: Throwable) {
                lastError = "前台服务启动失败：${e.javaClass.simpleName} ${e.message}"
                Log.e("WordCountFGS", "startForegroundService failed", e)
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, WordCountForegroundService::class.java))
            } catch (_: Throwable) {
            }
        }

        /**
         * v1.9.20: 更新前台通知的进度文本（已统计 X/Y · 文件名）。
         * 仅 nm.notify 替换内容，不改变前台化状态——切后台后用户在通知栏即可看到实时进度。
         */
        fun notifyProgress(ctx: Context, text: String) {
            try {
                val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
                val noti = NotificationCompat.Builder(ctx, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setContentTitle("WordCount 正在统计")
                    .setContentText(text.take(120))
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .setSilent(true)
                    .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                    .build()
                nm.notify(NOTI_ID, noti)
            } catch (e: Throwable) {
                Log.w("WordCountFGS", "notify progress failed: ${e.message}")
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d("WordCountFGS", "onCreate")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val nm = getSystemService(NotificationManager::class.java)
                val ch = NotificationChannel(CHANNEL_ID, "字数统计中", NotificationManager.IMPORTANCE_LOW)
                ch.setShowBadge(false)
                ch.description = "让统计在后台也能持续进行"
                nm.createNotificationChannel(ch)
            } catch (e: Throwable) {
                Log.w("WordCountFGS", "createNotificationChannel failed: ${e.message}")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ---- ② WakeLock：切后台 + 息屏后保证 CPU 不进 Doze，统计线程能继续跑 ----
        if (wakeLock == null) {
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WordCount:stats")
                wl.setReferenceCounted(false)
                // 上限 3 小时，避免异常路径下永久持锁耗电
                wl.acquire(3 * 60 * 60 * 1000L)
                wakeLock = wl
                wakeLockOk = true
            } catch (e: Throwable) {
                wakeLockOk = false
                lastError = "WakeLock 获取失败：${e.message}"
                Log.w("WordCountFGS", "acquire wakelock failed: ${e.message}")
            }
        }

        // ---- ③ 前台化：显式传 type，失败则主动 stopSelf 解除 5 秒契约，绝不静默吞 ----
        val noti: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("WordCount 统计服务")
            .setContentText("后台统计运行中，请勿清理本通知")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        try {
            // targetSdk 34：必须传与 manifest foregroundServiceType 一致的类型，否则抛异常
            ServiceCompat.startForeground(
                this,
                NOTI_ID,
                noti,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            )
            foregroundOk = true
            lastError = null
            Log.d("WordCountFGS", "foreground OK (wakeLock=$wakeLockOk)")
        } catch (e: Throwable) {
            foregroundOk = false
            lastError = "前台化失败(${e.javaClass.simpleName})：${e.message}。请在系统设置里允许 WordCount 的通知权限与后台运行。"
            Log.e("WordCountFGS", "startForeground failed", e)
            // 关键：主动 stopSelf 解除 startForegroundService 的 5 秒契约，
            // 否则系统会抛 ForegroundServiceDidNotStartInTimeException 杀掉整个进程，
            // 统计协程随之全部消失 —— 这正是 v1.9.18 之前"切后台不统计"的直接死因。
            try {
                stopSelf()
            } catch (_: Throwable) {
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d("WordCountFGS", "onDestroy")
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Throwable) {
        }
        // v1.9.52: 服务销毁时强制取消前台通知，避免统计完成后通知栏仍残留“正在统计”。
        try { getSystemService(NotificationManager::class.java)?.cancel(NOTI_ID) } catch (_: Throwable) {}
        wakeLock = null
        wakeLockOk = false
        foregroundOk = false
        super.onDestroy()
    }
}
