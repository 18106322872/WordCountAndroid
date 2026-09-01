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
import kotlinx.coroutines.launch

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

    /**
     * v1.9.75: 递归删除 Chaquopy 提取目录，并清掉 Chaquopy 记录资产哈希的 SharedPreferences。
     * 只删目录不删 Preferences 会导致 Chaquopy 认为文件已存在/已匹配，跳过提取 → AssetFinder/scripts 缺失。
     */
    private fun clearChaquopyCache(): Boolean {
        var ok = true
        val dir = File(filesDir, "chaquopy")
        try {
            if (dir.exists()) dir.deleteRecursively()
        } catch (e: Throwable) {
            ok = false
            Diag.w("清空 Chaquopy 缓存目录失败: ${e.javaClass.simpleName}: ${e.message}")
        }
        try {
            getSharedPreferences("chaquopy", Context.MODE_PRIVATE).edit().clear().apply()
            Diag.d("已清空 SharedPreferences(\"chaquopy\") 的资产哈希记录")
        } catch (e: Throwable) {
            ok = false
            Diag.w("清空 SharedPreferences(\"chaquopy\") 失败: ${e.javaClass.simpleName}: ${e.message}")
        }
        return ok
    }

    /**
     * v1.9.71: 在跨进程文件锁保护下启动 Python，并在锁内完成关键模块的首次 import。
     * 主进程（main）与 :countservice 可能同时冷启动并各自调用 Python.start()，
     * 两者都会往 files/chaquopy 提取资源；文件锁把提取串行化，避免 AssetFinder 损坏。
     *
     * 此前 v1.9.70 的问题：
     * ① wordcount.py 缺 import re，预热时直接 NameError；
     * ② clearChaquopyCache() 在锁外执行，:countservice 启动时可能把主进程刚提取好的资源清掉，
     *   导致 Python.start() 后 AssetFinder/scripts 仍不存在 → FileNotFoundError。
     *
     * 现在把"版本升级检测+清空缓存"也移进锁内，并打印每次尝试的完整异常以便诊断。
     */
    private fun startPythonLocked() {
        val lockFile = File(filesDir, ".chaquopy_start.lock")
        lockFile.parentFile?.mkdirs()
        var raf: RandomAccessFile? = null
        var lock: FileLock? = null
        var attempts = 0
        var started = false
        while (attempts < 2 && !started) {
            attempts++
            try {
                if (raf == null) {
                    raf = RandomAccessFile(lockFile, "rw")
                }
                lock = raf.channel.lock()
                Diag.d("获取 Chaquopy 启动锁（尝试 $attempts，进程=${resolveProcessName()}）")

                // 关键：多进程共享 files/chaquopy，清空缓存必须在锁内做，
                // 否则一个进程刚提取好，另一个进程把它删了。
                if (shouldClearChaquopyCache()) {
                    Diag.d("检测到版本升级，在锁内清空 Chaquopy 缓存目录以强制重新提取")
                    clearChaquopyCache()
                    markVersionCode()
                }

                if (!Python.isStarted()) {
                    Diag.d("调用 Python.start()（尝试 $attempts）")
                    Python.start(AndroidPlatform(this))
                    Diag.d("Python.start() 返回")
                } else {
                    Diag.d("Python.isStarted() 已为 true，跳过 Python.start()")
                }

                // 关键：在锁内完成模块预热，强制 AssetFinder 懒加载也串行化。
                pythonPrewarm()
                pythonStartError = null
                started = true
                Diag.d("Python 已启动并预热完成（进程=${resolveProcessName()}）")
            } catch (e: Throwable) {
                pythonStartError = "${e.javaClass.simpleName}: ${e.message}"
                Diag.e("Python 启动/预热失败（尝试 $attempts/$attempts）: $pythonStartError", e)
                if (attempts < 2) {
                    if (!Python.isStarted()) {
                        try {
                            Diag.w("Python 尚未 started，清空 Chaquopy 缓存后重试: $pythonStartError")
                            clearChaquopyCache()
                        } catch (e2: Throwable) {
                            Diag.e("清空缓存失败: ${e2.javaClass.simpleName}: ${e2.message}", e2)
                        }
                    } else {
                        Diag.w("Python 已 started 但预热失败，将直接重试预热: $pythonStartError")
                    }
                }
            } finally {
                runCatching { lock?.release() }
                lock = null
            }
        }
        runCatching { raf?.close() }
        if (!started) {
            Diag.e("Python 启动/预热连续 $attempts 次失败，后续 DWG/PDF 将退化到 Kotlin 兜底")
        }
    }

    /** v1.9.70: 在文件锁内强制预热关键 Python 模块，完成 Chaquopy AssetFinder 懒加载。 */
    private fun pythonPrewarm() {
        val py = Python.getInstance()
        py.getModule("wordcount")
        py.getModule("cad_core")
        Log.d("WordCountApp", "Python 模块预热完成（wordcount + cad_core）")
    }

    /**
     * v1.9.94→v1.9.95: 全面对齐桌面版 v1.8.83 最终清理逻辑：
     *   - 扫描内部 cacheDir + 外部 externalCacheDir；
     *   - 同时清理临时目录和临时文件（不限于目录）；
     *   - 清理范围：arc_* / rar_* / wc_*（保留功能性文件/目录，见 reserved 列表）；
     *   - dwg_imgs 下各子目录；
     *   - 手动清理入口 maxAgeHours=0 表示不限年龄，protectRecentMinutes 保护最近仍在修改的项。
     * v1.9.89: 对齐桌面版 wordcount.py 的 _cleanup_wordcount_tempdirs。
     * 返回 (removed, freedBytes)。
     */
    fun cleanupTempFiles(maxAgeHours: Int = 24, protectRecentMinutes: Int = 0): Pair<Int, Long> {
        val roots = listOfNotNull(cacheDir, externalCacheDir)
        val now = System.currentTimeMillis()
        val ageLimit = if (maxAgeHours <= 0) Long.MAX_VALUE else now - maxAgeHours * 60L * 60 * 1000
        val recentLimit = if (protectRecentMinutes <= 0) Long.MAX_VALUE else now - protectRecentMinutes * 60L * 1000
        val dirPrefixes = listOf("arc_", "rar_")
        val filePrefixes = listOf("wc_")
        // v1.9.95: 补充 DWG→DXF 中间产物。转换产物以 <图纸名>.dxf 落在 cacheDir 根目录
        // （压缩包内层文件的父目录就是 cacheDir），单份可达 200MB 以上且此前从不删除，
        // 是临时空间占用的大头。此前只按 arc_ / rar_ / wc_ 前缀匹配，.dxf 永远扫不到，
        // 导致用户明明每天多次测试、清理却始终提示「没有可清理的临时文件」。
        val fileSuffixes = listOf(".dxf")
        val reservedFiles = setOf("wc_results.jsonl", "wc_results.jsonl.tmp", "wc_stats.log")
        val reservedPrefixes = listOf("wc_diag_", "wc_export_")
        var removed = 0
        var freed = 0L
        var scanned = 0
        for (root in roots) {
            if (!root.exists()) continue
            val entries = try { root.listFiles() } catch (_: Throwable) { null } ?: continue
            for (item in entries) {
                val name = item.name
                val isCandidate = when {
                    name in reservedFiles -> false
                    reservedPrefixes.any { name.startsWith(it) } -> false
                    name == "dwg_imgs" && item.isDirectory -> true
                    item.isDirectory && dirPrefixes.any { name.startsWith(it) } -> true
                    item.isFile && filePrefixes.any { name.startsWith(it) } -> true
                    item.isDirectory && filePrefixes.any { name.startsWith(it) } -> true
                    // v1.9.95: cacheDir 根目录下的 .dxf 一律是 DWG 转换中间产物，可安全清理
                    item.isFile && fileSuffixes.any { name.endsWith(it, ignoreCase = true) } -> true
                    else -> false
                }
                if (!isCandidate) continue
                scanned++
                // dwg_imgs 是二级目录（dwg_imgs/<文件名>/），逐个子目录判定
                val targets = if (name == "dwg_imgs" && item.isDirectory) {
                    item.listFiles()?.toList() ?: emptyList()
                } else {
                    listOf(item)
                }
                for (t in targets) {
                    try {
                        val mtime = t.lastModified()
                        // 太新：未达到 maxAgeHours 指定年龄
                        if (mtime > ageLimit) continue
                        // 最近仍有修改：按 protectRecentMinutes 保护，避免误删正在统计的目录
                        if (mtime >= recentLimit) continue
                        val size = tempDirSize(t)
                        if (t.deleteRecursively()) {
                            removed++
                            freed += size
                        }
                    } catch (_: Throwable) {}
                }
            }
        }
        Diag.d("临时文件清理: 扫描到 $scanned 个候选, 删除 $removed 个, 释放 ${freed / 1024}KB")
        return removed to freed
    }

    /** 递归统计目录占用字节数。 */
    private fun tempDirSize(dir: File): Long {
        var s = 0L
        try {
            dir.walkTopDown().forEach { if (it.isFile) s += it.length() }
        } catch (_: Throwable) {}
        return s
    }

    /** 获取当前进程名，仅用于日志。 */
    private fun resolveProcessName(): String {
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
        // v1.9.89: 启动时后台清理过期临时文件（对齐桌面 _cleanup_wordcount_tempdirs，
        // 24h 年龄保护，不阻塞启动；主进程执行即可，隔离进程已提前 return）
        appScope.launch {
            runCatching { cleanupTempFiles() }
        }
        // v1.9.71: 缓存清理已移入 startPythonLocked() 的文件锁内，
        // 避免 main / :countservice 互相清空已提取资源。
        startPythonLocked()
    }
}
