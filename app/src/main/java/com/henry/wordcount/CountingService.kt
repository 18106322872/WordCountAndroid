package com.henry.wordcount

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.*
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * v1.9.25: 独立前台统计服务，运行在 :countservice 进程。
 *
 * 根因：真机日志证明，即便主进程已启动前台服务 + WakeLock，切后台后 Activity 主进程仍被
 * 国产 ROM / Android 电源管理深度冻结（心跳中断 10 分钟级），导致统计中断。
 * 把实际统计工作搬到本独立进程——其唯一职责就是“持前台优先级 + 唤醒锁地跑统计”，
 * 不受 Activity 主进程冻结牵连。统计逻辑复用 MainActivity 的 processBatchToEntries（同一份口径），
 * 结果追加写入外部缓存 wc_results.jsonl，由 MainActivity 轮询 / ON_START 恢复，按 id 去重并入列表。
 */
class CountingService : Service() {

    companion object {
        const val CHANNEL_ID = "wordcount_counting"
        const val NOTI_ID = 101
        const val BATCH_END_MARKER = "{\"type\":\"batch_end\"}"

        @Volatile
        var lastError: String? = null
        @Volatile
        var foregroundOk: Boolean = false
        @Volatile
        var wakeLockOk: Boolean = false

        /** Activity 调用入口：把缓存文件路径 + 显示名交给本服务，返回是否成功派发。 */
        fun startBatch(ctx: Context, paths: List<String>, names: List<String>): Boolean {
            return try {
                val i = Intent(ctx, CountingService::class.java)
                i.putStringArrayListExtra("paths", ArrayList(paths))
                i.putStringArrayListExtra("names", ArrayList(names))
                ContextCompat.startForegroundService(ctx, i)
                true
            } catch (e: Throwable) {
                lastError = "统计服务启动失败：${e.javaClass.simpleName} ${e.message}"
                Log.e("WordCountCS", "startForegroundService failed", e)
                false
            }
        }

        fun stop(ctx: Context) {
            try { ctx.stopService(Intent(ctx, CountingService::class.java)) } catch (_: Throwable) {}
        }
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val nm = getSystemService(NotificationManager::class.java)
                val ch = NotificationChannel(CHANNEL_ID, "字数统计中", NotificationManager.IMPORTANCE_LOW)
                ch.setShowBadge(false)
                ch.description = "让统计在后台也能持续进行"
                nm.createNotificationChannel(ch)
            } catch (e: Throwable) {
                Log.w("WordCountCS", "createNotificationChannel failed: ${e.message}")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ---- WakeLock：切后台 + 息屏后保证 CPU 不进 Doze ----
        if (wakeLock == null) {
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WordCount:counting")
                wl.setReferenceCounted(false)
                wl.acquire(3 * 60 * 60 * 1000L)
                wakeLock = wl
                wakeLockOk = true
            } catch (e: Throwable) {
                wakeLockOk = false
                lastError = "WakeLock 获取失败：${e.message}"
                Log.w("WordCountCS", "acquire wakelock failed: ${e.message}")
            }
        }

        // ---- 前台化 ----
        val noti: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("WordCount 正在统计")
            .setContentText("后台继续进行中，请勿清理本通知")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        try {
            ServiceCompat.startForeground(
                this, NOTI_ID, noti,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            )
            foregroundOk = true
            lastError = null
            Log.d("WordCountCS", "foreground OK (wakeLock=$wakeLockOk)")
        } catch (e: Throwable) {
            foregroundOk = false
            lastError = "前台化失败(${e.javaClass.simpleName})：${e.message}"
            Log.e("WordCountCS", "startForeground failed", e)
            try { stopSelf() } catch (_: Throwable) {}
            return START_STICKY
        }

        val paths = intent?.getStringArrayListExtra("paths")
        val names = intent?.getStringArrayListExtra("names")
        if (!paths.isNullOrEmpty()) {
            serviceScope.launch {
                processBatch(paths, names ?: paths.map { File(it).name })
            }
        } else {
            Log.w("WordCountCS", "onStartCommand missing paths, stopSelf")
            stopSelf()
        }
        return START_STICKY
    }

    private suspend fun processBatch(paths: List<String>, names: List<String>) {
        try {
            logStatsLine("BATCH_START files=${paths.size}")
            runCatching { PythonEngine.start(this@CountingService) }
            val cachedFiles = paths.mapIndexed { i, p -> CachedFile(File(p), names.getOrElse(i) { File(p).name }) }
            processBatchToEntries(
                context = this@CountingService,
                cachedFiles = cachedFiles,
                onProgress = { n, d, t ->
                    updateNotification("$d/$t · $n")
                    appendProgress(n, d, t)
                    logStatsLine("PROGRESS $d/$t $n")
                },
                emit = { entry -> appendResult(entry) },
                onError = { msg -> logStatsLine("ERROR $msg") }
            )
            appendBatchEnd()
            logStatsLine("BATCH_END")
        } catch (e: Throwable) {
            Log.e("WordCountCS", "processBatch fatal: ${e.message}", e)
            logStatsLine("FATAL ${e.javaClass.simpleName} ${e.message}")
        } finally {
            stopSelf()
        }
    }

    /** 把一条结果追加写入外部缓存 wc_results.jsonl（主进程轮询/恢复用）。
     *  v1.9.26: 改为 append-mode atomic write + fsync；异常不再吞，显式写 wc_stats.log 错误行；
     *  把每文件 stats 也同步写到 wc_stats.log 的 FILE_DONE 行（用户从单一日志即可对比电脑值）。 */
    private fun appendResult(entry: FileEntry) {
        val dir = externalCacheDir ?: cacheDir
        if (dir == null) {
            Log.e("WordCountCS", "appendResult: cache dir null")
            try { logStatsLine("APPEND_ERR ${entry.id} cache_dir_null") } catch (_: Throwable) {}
            return
        }
        try {
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, "wc_results.jsonl")
            val rawJson = entry.rawResult?.let { JSONObject(it).toString() } ?: "null"
            val obj = JSONObject()
            obj.put("id", entry.id)
            obj.put("displayName", entry.displayName)
            obj.put("cachePath", entry.cachePath)
            if (entry.error != null) obj.put("error", entry.error) else obj.put("rawResultJson", rawJson)
            val line = obj.toString() + "\n"
            // append-mode（O_APPEND）在 Linux/Android 上对 <PIPE_BUF(4096B) 写是 atomic 的，
            // 不会与主进程并发读产生脏行。fsync 保证崩溃不丢。
            FileOutputStream(f, true).use { fos ->
                fos.write(line.toByteArray(Charsets.UTF_8))
                try { fos.fd.sync() } catch (_: Throwable) {}
            }
            // 同步把统计数字写到 wc_stats.log 的 FILE_DONE 行（用户从单一日志即可对比电脑值）
            val stats = (entry.rawResult?.get("stats") as? Map<*, *>)
            val statsStr = if (stats != null) {
                "w=${stats["words"]} fe=${stats["fe"]} nc=${stats["nc"]} c=${stats["chars"]}"
            } else ""
            logStatsLine("FILE_DONE ${entry.id} ${entry.displayName} $statsStr".trim())
        } catch (e: Throwable) {
            Log.e("WordCountCS", "appendResult failed for ${entry.id}", e)
            try { logStatsLine("APPEND_ERR ${entry.id} ${e.javaClass.simpleName} ${e.message}") } catch (_: Throwable) {}
        }
    }

    private fun appendBatchEnd() {
        try {
            val dir = externalCacheDir ?: cacheDir
            val f = File(dir, "wc_results.jsonl")
            val line = BATCH_END_MARKER + "\n"
            FileOutputStream(f, true).use { fos ->
                fos.write(line.toByteArray(Charsets.UTF_8))
                try { fos.fd.sync() } catch (_: Throwable) {}
            }
        } catch (e: Throwable) {
            Log.e("WordCountCS", "appendBatchEnd failed", e)
        }
    }

    /** 把进度也写进 jsonl，让主进程在轮询时同步刷新 App 内进度条。 */
    private fun appendProgress(name: String, done: Int, total: Int) {
        try {
            val dir = externalCacheDir ?: cacheDir
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, "wc_results.jsonl")
            val obj = JSONObject()
            obj.put("type", "progress")
            obj.put("done", done)
            obj.put("total", total)
            obj.put("name", name)
            val line = obj.toString() + "\n"
            FileOutputStream(f, true).use { fos ->
                fos.write(line.toByteArray(Charsets.UTF_8))
                try { fos.fd.sync() } catch (_: Throwable) {}
            }
        } catch (e: Throwable) {
            Log.e("WordCountCS", "appendProgress failed", e)
        }
    }

    private fun updateNotification(text: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            val noti = NotificationCompat.Builder(this, CHANNEL_ID)
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
            Log.w("WordCountCS", "notify progress failed: ${e.message}")
        }
    }

    /** 在 :countservice 进程写外部缓存 wc_stats.log，便于和主进程日志合并排查。 */
    private fun logStatsLine(name: String) {
        try {
            val dir = externalCacheDir ?: cacheDir
            val f = File(dir, "wc_stats.log")
            f.appendText("${System.currentTimeMillis()}\t0/0\t$name\tFGS=${if (foregroundOk) "✓" else "✗"}\tWL=${if (wakeLockOk) "✓" else "✗"}\n")
        } catch (_: Throwable) {}
    }

    override fun onDestroy() {
        Log.d("WordCountCS", "onDestroy")
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Throwable) {}
        wakeLock = null
        wakeLockOk = false
        foregroundOk = false
        serviceJob.cancel()
        super.onDestroy()
    }
}
