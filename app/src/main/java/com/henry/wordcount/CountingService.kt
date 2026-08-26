package com.henry.wordcount

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
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
 * v1.9.38: 独立前台统计服务，运行在 :countservice 进程。
 *
 * 职责：持前台优先级 + WakeLock 地跑统计。统计逻辑复用 MainActivity 的 processBatchToEntries。
 * 结果写入内部缓存 wc_results.jsonl（非外部可见缓存），供 MainActivity 切回前台时恢复。
 * 本版新增：把进度也写入 wc_results.jsonl，修复 v1.9.36 主界面进度不显示的问题；
 * 批次完成后发送一条带系统默认通知铃声的完成通知。
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

        // ---- 前台化：只显示一个简洁的进度通知 ----
        val noti: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("WordCount 正在统计")
            .setContentText("准备中...")
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
            runCatching { PythonEngine.start(this@CountingService) }
            val cachedFiles = paths.mapIndexed { i, p -> CachedFile(File(p), names.getOrElse(i) { File(p).name }) }
            processBatchToEntries(
                context = this@CountingService,
                cachedFiles = cachedFiles,
                onProgress = { n, d, t ->
                    updateNotification("$d/$t · $n")
                    appendProgress(n, d, t)
                },
                emit = { entry -> appendResult(entry) },
                onError = { msg -> Log.w("WordCountCS", "batch error: $msg") }
            )
            appendBatchEnd()
            showCompletionNotification()
        } catch (e: Throwable) {
            Log.e("WordCountCS", "processBatch fatal: ${e.message}", e)
        } finally {
            stopSelf()
        }
    }

    /** v1.9.38: 把进度写入内部缓存，供 MainActivity recoverResults 更新主界面进度条。 */
    private fun appendProgress(name: String, done: Int, total: Int) {
        try {
            val dir = cacheDir ?: return
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, "wc_results.jsonl")
            val obj = JSONObject()
            obj.put("type", "progress")
            obj.put("name", name)
            obj.put("done", done)
            obj.put("total", total)
            val line = obj.toString() + "\n"
            FileOutputStream(f, true).use { fos ->
                fos.write(line.toByteArray(Charsets.UTF_8))
                try { fos.fd.sync() } catch (_: Throwable) {}
            }
        } catch (e: Throwable) {
            Log.e("WordCountCS", "appendProgress failed", e)
        }
    }

    /** v1.9.36: 把结果写入内部缓存（cacheDir），供 MainActivity 恢复。 */
    private fun appendResult(entry: FileEntry) {
        try {
            val dir = cacheDir ?: return
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, "wc_results.jsonl")
            val rawJson = entry.rawResult?.let { JSONObject(it).toString() } ?: "null"
            val obj = JSONObject()
            obj.put("id", entry.id)
            obj.put("displayName", entry.displayName)
            obj.put("cachePath", entry.cachePath)
            if (entry.error != null) obj.put("error", entry.error) else obj.put("rawResultJson", rawJson)
            val line = obj.toString() + "\n"
            FileOutputStream(f, true).use { fos ->
                fos.write(line.toByteArray(Charsets.UTF_8))
                try { fos.fd.sync() } catch (_: Throwable) {}
            }
        } catch (e: Throwable) {
            Log.e("WordCountCS", "appendResult failed for ${entry.id}", e)
        }
    }

    private fun appendBatchEnd() {
        try {
            val dir = cacheDir ?: return
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

    /** v1.9.38: 统计完成后发送一条带系统默认通知铃声的完成通知。
     *  v1.9.52: 先取消“正在统计”通知(NOTI_ID)，避免完成通知与旧进度通知并存。 */
    private fun showCompletionNotification() {
        try {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            nm.cancel(NOTI_ID)
            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val noti = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("WordCount 统计完成")
                .setContentText("全部文件已统计完成")
                .setOngoing(false)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setSound(soundUri)
                .build()
            nm.notify(NOTI_ID + 1, noti)
        } catch (e: Throwable) {
            Log.w("WordCountCS", "showCompletionNotification failed: ${e.message}")
        }
    }

    override fun onDestroy() {
        Log.d("WordCountCS", "onDestroy")
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Throwable) {}
        // v1.9.52: 服务销毁时强制取消“正在统计”通知，杜绝 22/28、17/17 等残留。
        try { getSystemService(NotificationManager::class.java)?.cancel(NOTI_ID) } catch (_: Throwable) {}
        wakeLock = null
        wakeLockOk = false
        foregroundOk = false
        serviceJob.cancel()
        super.onDestroy()
    }
}
