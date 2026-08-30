package com.henry.wordcount

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock

/**
 * v1.3.80: 按 Chaquopy 官方推荐，Python 解释器在 Application.onCreate()（主线程）
 * 只启动一次。
 *
 * 此前 PythonEngine 在每次 Python 调用时（且跑在后台线程）无条件重新调用
 * Python.start(AndroidPlatform(context))，会触发 Chaquopy 的 AssetFinder/scripts
 * 资源提取竞态，导致部分设备上 Python 引擎崩溃（FileNotFoundError: AssetFinder/scripts），
 * 进而 PDF 永远走 Kotlin 兜底、pdfminer 从未生效。
 *
 * 现在改为主线程一次性启动 + isStarted() 守卫，彻底消除该竞态。
 */
class WordCountApplication : Application() {

    companion object {
        /**
         * v1.9.20: App 级常驻协程域。addFiles 等统计协程挂在这里，
         * 生命周期与进程一致——前台 service(WordCountForegroundService) 守护进程存活，
         * 协程即持续运行，切后台不取消（此前 ProcessLifecycleOwner.lifecycleScope 在
         * 部分 ROM 上随 App 进后台派发 ON_DESTROY 被取消 → 切后台不统计）。
         */
        lateinit var appScope: kotlinx.coroutines.CoroutineScope
    }


    private fun isDwgIsolatedProcess(): Boolean {
        val procName = try {
            val m = android.app.ActivityManager::class.java
            val am = getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val pids = android.os.Process.myPid()
            val infos = am.runningAppProcesses
            infos?.firstOrNull { it.pid == pids }?.processName
        } catch (_: Throwable) { null }
        return procName != null && procName.endsWith(":dwgisolated")
    }

    /** Application.onCreate 主线程启动 Python 的结果；null 表示成功 */
    @Volatile
    var pythonStartError: String? = null
        private set

    /** v1.9.69: 读取当前 APK versionCode，用于判断是否需要清空 Chaquopy 缓存。 */
    private fun currentVersionCode(): Long {
        return try {
            val pi = packageManager.getPackageInfo(packageName, 0)
            PackageInfoCompat.getLongVersionCode(pi)
        } catch (_: Throwable) { 0L }
    }

    /** v1.9.69: 版本升级时强制清空 Chaquopy 已提取资源目录，避免旧版 AssetFinder 与新版不兼容。 */
    private fun shouldClearChaquopyCache(): Boolean {
        val last = getSharedPreferences("wc_init", Context.MODE_PRIVATE).getLong("last_version_code", 0L)
        val cur = currentVersionCode()
        return cur != 0L && cur != last
    }

    private fun markVersionCode() {
        val cur = currentVersionCode()
        if (cur != 0L) {
            getSharedPreferences("wc_init", Context.MODE_PRIVATE).edit().putLong("last_version_code", cur).apply()
        }
    }

    /** v1.9.69: 递归删除 Chaquopy 在 files/chaquopy 下的提取目录。 */
    private fun clearChaquopyCache(): Boolean {
        val dir = File(filesDir, "chaquopy")
        return try {
            if (dir.exists()) dir.deleteRecursively()
            true
        } catch (e: Throwable) {
            Diag.w("清空 Chaquopy 缓存失败: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /**
     * v1.9.69: 在跨进程文件锁保护下启动 Python。
     * 主进程（main）与 :countservice 可能同时冷启动并各自调用 Python.start()，
     * 两者都会往 files/chaquopy 提取资源；文件锁把提取串行化，避免 AssetFinder 损坏。
     * 若首次启动失败且 Python 尚未启动，则清空缓存后重试一次。
     */
    private fun startPythonLocked() {
        val lockFile = File(filesDir, ".chaquopy_start.lock")
        lockFile.parentFile?.mkdirs()
        var raf: RandomAccessFile? = null
        var lock: FileLock? = null
        try {
            raf = RandomAccessFile(lockFile, "rw")
            lock = raf.channel.lock()
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(this))
            }
            pythonStartError = null
            Log.d("WordCountApp", "Python 已在 Application.onCreate 主线程启动（进程=${getProcessName()}）")
        } catch (e: Throwable) {
            pythonStartError = "${e.javaClass.simpleName}: ${e.message}"
            Log.e("WordCountApp", "Python.start 失败: $pythonStartError", e)
            // 启动失败且尚未 started 时，清空缓存再试一次（可能是旧版残留资源损坏）
            if (!Python.isStarted()) {
                try {
                    Diag.w("Python.start 失败，清空 Chaquopy 缓存后重试: $pythonStartError")
                    clearChaquopyCache()
                    Python.start(AndroidPlatform(this))
                    pythonStartError = null
                    Log.d("WordCountApp", "Python.start 重试成功")
                } catch (e2: Throwable) {
                    pythonStartError = "$pythonStartError; 重试失败: ${e2.javaClass.simpleName}: ${e2.message}"
                    Log.e("WordCountApp", "Python.start 重试失败", e2)
                }
            }
        } finally {
            runCatching { lock?.release() }
            runCatching { raf?.close() }
        }
    }

    /** 获取当前进程名，仅用于日志。 */
    private fun getProcessName(): String {
        return try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.runningAppProcesses?.firstOrNull { it.pid == android.os.Process.myPid() }?.processName ?: "?"
        } catch (_: Throwable) { "?" }
    }

    override fun onCreate() {
        super.onCreate()
        // v1.9.65: 初始化应用内诊断日志（全进程生效：main / :countservice / :dwgisolated）
        Diag.init(this)
        // v1.5.13: :dwgisolated 进程只做 native dwg2pdf 转换，不初始化 Python（Chaquopy 在
        // 非主进程初始化会失败且浪费资源，其资源提取还可能干扰主进程）。
        // v1.9.20: 初始化常驻协程域（隔离进程不初始化 Python，但仍可用 scope；此处统一放隔离进程判断之后）
        appScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        if (isDwgIsolatedProcess()) {
            Log.d("WordCountApp", "跳过 Python 初始化（隔离进程）")
            return
        }
        // v1.9.69: 升级后清空旧 Chaquopy 缓存，再用文件锁串行化多进程启动。
        if (shouldClearChaquopyCache()) {
            Diag.d("检测到版本升级，清空 Chaquopy 缓存目录以强制重新提取")
            clearChaquopyCache()
            markVersionCode()
        }
        startPythonLocked()
    }
}
