package com.henry.wordcount

import android.app.Application
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

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

    override fun onCreate() {
        super.onCreate()
        // v1.5.13: :dwgisolated 进程只做 native dwg2pdf 转换，不初始化 Python（Chaquopy 在
        // 非主进程初始化会失败且浪费资源，其资源提取还可能干扰主进程）。
        if (isDwgIsolatedProcess()) {
            Log.d("WordCountApp", "跳过 Python 初始化（隔离进程）")
            return
        }
        try {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(this))
            }
            pythonStartError = null
            Log.d("WordCountApp", "Python 已在 Application.onCreate 主线程启动")
        } catch (e: Throwable) {
            pythonStartError = "${e.javaClass.simpleName}: ${e.message}"
            Log.e("WordCountApp", "Python.start 失败: $pythonStartError", e)
        }
    }
}
