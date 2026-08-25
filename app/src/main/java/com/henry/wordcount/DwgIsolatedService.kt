package com.henry.wordcount

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log

/**
 * v1.9.36: 隔离 LibreDWG 原生 dwg2dxf / dwg2pdf 调用到独立进程(:dwgisolated)。
 *
 * 背景：LibreDWG 的 dwg_read_file / dwg 写入在部分 CAD 文件上会触发 native SIGSEGV，
 * native 崩溃会直接杀掉整个 app 进程，Kotlin try/catch 无法捕获 → 闪退。
 * 把该函数放到独立进程，崩溃只杀死该进程；主进程通过 Messenger 调用，带超时兜底。
 *
 * v1.9.36 清理：本 service 不再自己 startForeground，而是作为纯 bound service 被前台进程
 * CountingService(:countservice) 绑定，共享前台优先级。通知栏只保留 CountingService 的进度通知。
 *
 * 注意：本 service 运行在 :dwgisolated 进程，Application.onCreate 已跳过 Python 初始化，
 * 这里只加载 libdwg2dxf.so 并调用，不依赖任何 Python/Compose。
 */
class DwgIsolatedService : Service() {

    private lateinit var messenger: Messenger
    private val mainHandler = Handler(Looper.getMainLooper())

    // v1.9.36: 每文件转换后 stopSelf，下一个文件重新 bindService 拉起全新干净进程，
    // 杜绝 LibreDWG 全局状态污染导致的 0 字/乱字数。
    private var idleStopRunnable: Runnable? = null

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
        // 最后一位客户端解绑 → 延迟自停；若短时间内重绑（下个文件转换）则取消
        idleStopRunnable = Runnable { try { stopSelf() } catch (_: Throwable) {} }
        mainHandler.postDelayed(idleStopRunnable!!, 10_000L)
        return true
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
                    // v1.9.36: 转换后立即销毁进程，下一个文件重新 bind 拉起全新干净进程。
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
                    // v1.9.36: 同 MSG_CONVERT_DXF，转换后立即销毁进程。
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

        /** v1.9.36: 主进程显式停止 :dwgisolated（addFiles 结束后调用）。 */
        fun stopService(ctx: Context) {
            try { ctx.stopService(Intent(ctx, DwgIsolatedService::class.java)) } catch (_: Throwable) {}
        }
    }
}
