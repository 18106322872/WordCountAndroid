package com.henry.wordcount

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * v1.9.10: 前台占位 service —— 让 MainActivity 切到后台时整个进程不被 Android 14+ 强制暂停，
 * 从而 dwg2dxf / OLE 抽取等耗时 IO 协程可以持续跑完。
 *
 * 背景：v1.9.8 改用 ProcessLifecycleOwner.lifecycleScope 作为 workScope，让 Kotlin 协程在后台
 * 也能运行，但 dwg2dxf 是 Android 子进程调用（通过 :dwgisolated 进程）。Android 14+ 在主 app
 * 切后台后会强制挂起所有子进程（包括 :dwgisolated），导致 dwg2dxf 调用卡住、统计卡死。
 *
 * 设计：service 本身不做任何文件处理；只通过 startForeground 提供前台通知，让 app 进程保持
 * 前台优先级。addFiles 等所有逻辑仍在 MainActivity 内执行（用 ProcessLifecycleOwner.lifecycleScope）。
 *
 * 触发：MainActivity 启动 addFiles 时 startService；addFiles 完成（不论成功/失败）后 stopService。
 */
class WordCountForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "wordcount_stats"
        const val NOTI_ID = 100
        fun start(ctx: Context) {
            val i = Intent(ctx, WordCountForegroundService::class.java)
            ContextCompat.startForegroundService(ctx, i)
        }
        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, WordCountForegroundService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(CHANNEL_ID, "字数统计中", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            ch.description = "让统计在后台也能持续进行"
            nm.createNotificationChannel(ch)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val noti: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("WordCount 正在统计")
                .setContentText("后台继续进行中")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build()
            // v1.9.13: startForeground 包 try/catch —— 任何通知/类型异常都不能抛出到
            // 主线程，否则前台 service 进程崩溃 → 整个统计协程被杀死 → 切后台统计"暂停"且
            // 切回前台不会重启（进程已死）。捕获后进程仍保有前台优先级，统计可继续。
            startForeground(NOTI_ID, noti)
        } catch (e: Throwable) {
            Log.w("WordCountFGS", "startForeground 失败(忽略，进程仍前台优先级): ${e.message}")
        }
        return START_NOT_STICKY
    }
}