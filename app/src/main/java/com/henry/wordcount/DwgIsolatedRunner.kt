package com.henry.wordcount

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * v1.5.13: 主进程侧调用器 —— 通过 Messenger 跨进程调用 DwgIsolatedService。
 *
 * 关键安全网：
 *   1. 绑定超时（8s）：防止 service 进程起不来时永久挂起。
 *   2. 转换超时（35s）：LibreDWG 对超大/损坏文件可能长时间卡住；超时后断开连接，
 *      主进程直接拿到失败结果，绝不阻塞 UI、绝不崩溃。
 *   3. 进程崩溃不可见：若 :dwgisolated 进程 native 崩溃，bind 会断开（onServiceDisconnected），
 *      我们在超时/断开兜底里返回失败 → 主流程降级。
 *
 * 用法：
 *   val res = DwgIsolatedRunner.convertToPdf(context, dwgPath, pdfPath)
 *   // res.path != null 表示成功；否则 res.errorCode 含原因
 */
object DwgIsolatedRunner {

    private const val BIND_TIMEOUT_MS = 8_000L
    private const val CONVERT_TIMEOUT_MS = 35_000L

    /**
     * 在 :dwgisolated 进程执行 dwg2pdf（DWG 导出看图 / 字数 PDF 回退）。
     * 返回 DwgConverter.DwgResult；失败（超时/崩溃/未绑定）时 path==null、errorCode 描述原因。
     */
    suspend fun convertToPdf(context: Context, input: String, output: String): DwgConverter.DwgResult {
        return runConvert(context, input, output, DwgIsolatedService.MSG_CONVERT)
    }

    /**
     * v1.5.16: 在 :dwgisolated 进程执行 dwg2dxf（DWG→DXF，字数统计主路径）。
     * dwg2dxf 同为 LibreDWG 原生调用，可能在某些文件上 native 崩溃，故同样隔离。
     */
    suspend fun convertToDxf(context: Context, input: String, output: String): DwgConverter.DwgResult {
        return runConvert(context, input, output, DwgIsolatedService.MSG_CONVERT_DXF)
    }

    /**
     * 通用隔离进程转换调用（dwg2pdf / dwg2dxf 共用）。
     * 绑定超时 8s + 转换超时 35s + 进程崩溃(onServiceDisconnected)兜底。
     */
    private suspend fun runConvert(context: Context, input: String, output: String, requestWhat: Int): DwgConverter.DwgResult {
        return suspendCancellableCoroutine { cont ->
            val mainHandler = Handler(Looper.getMainLooper())
            var connection: ServiceConnection? = null
            var serviceMessenger: Messenger? = null
            var done = false

            val finish: (DwgConverter.DwgResult) -> Unit = finish@{ result ->
                if (done) return@finish
                done = true
                mainHandler.removeCallbacksAndMessages(null)
                try { connection?.let { context.unbindService(it) } } catch (_: Throwable) {}
                if (cont.isActive) cont.resume(result)
            }

            val convertTimeoutRunnable = Runnable {
                Log.w("DwgIsolated", "CONVERT timeout ($CONVERT_TIMEOUT_MS) for $input")
                finish(DwgConverter.DwgResult(errorCode = -98, diagText = "DWG转换超时（文件可能过大或引擎卡死）"))
            }

            val bindTimeoutRunnable = Runnable {
                if (!done) {
                    Log.w("DwgIsolated", "BIND timeout for service")
                    finish(DwgConverter.DwgResult(errorCode = -97, diagText = "DWG转换服务无法启动（隔离进程异常）"))
                }
            }

            connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: android.os.IBinder?) {
                    mainHandler.removeCallbacks(bindTimeoutRunnable)
                    if (binder == null) {
                        finish(DwgConverter.DwgResult(errorCode = -97, diagText = "DWG转换服务绑定为空"))
                        return
                    }
                    serviceMessenger = Messenger(binder)
                    // 构造请求
                    val request = Message.obtain(null, requestWhat).apply {
                        data = Bundle().apply {
                            putString(DwgIsolatedService.KEY_INPUT, input)
                            putString(DwgIsolatedService.KEY_OUTPUT, output)
                        }
                        replyTo = Messenger(object : Handler(Looper.getMainLooper()) {
                            override fun handleMessage(msg: Message) {
                                if (msg.what == DwgIsolatedService.MSG_RESULT) {
                                    val b = msg.data
                                    val rc = b.getInt(DwgIsolatedService.KEY_RC, -99)
                                    val diag = b.getString(DwgIsolatedService.KEY_DIAG) ?: ""
                                    val path = b.getString(DwgIsolatedService.KEY_PATH)
                                    finish(
                                        DwgConverter.DwgResult(
                                            path = if (rc == 0) path else null,
                                            errorCode = rc,
                                            diagText = diag
                                        )
                                    )
                                }
                            }
                        })
                    }
                    try {
                        serviceMessenger?.send(request)
                        mainHandler.postDelayed(convertTimeoutRunnable, CONVERT_TIMEOUT_MS)
                    } catch (e: Throwable) {
                        Log.e("DwgIsolated", "send request failed: ${e.message}", e)
                        finish(DwgConverter.DwgResult(errorCode = -99, diagText = "无法发送转换请求：${e.message}"))
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    // 隔离进程崩溃会触发此回调（native crash 杀进程）
                    Log.w("DwgIsolated", "service disconnected (process likely crashed)")
                    finish(DwgConverter.DwgResult(errorCode = -96, diagText = "DWG转换进程崩溃（文件可能损坏或不兼容）"))
                }
            }

            val intent = Intent(context, DwgIsolatedService::class.java)
            try {
                // 显式 start（先 startService 确保进程起来）再 bind
                context.startService(intent)
                val bound = context.bindService(intent, connection!!, Context.BIND_AUTO_CREATE)
                if (!bound) {
                    Log.w("DwgIsolated", "bindService returned false")
                    finish(DwgConverter.DwgResult(errorCode = -97, diagText = "DWG转换服务绑定失败"))
                    return@suspendCancellableCoroutine
                }
            } catch (e: Throwable) {
                Log.e("DwgIsolated", "start/bind failed: ${e.message}", e)
                finish(DwgConverter.DwgResult(errorCode = -97, diagText = "DWG转换服务启动失败：${e.message}"))
                return@suspendCancellableCoroutine
            }

            mainHandler.postDelayed(bindTimeoutRunnable, BIND_TIMEOUT_MS)

            cont.invokeOnCancellation {
                finish(DwgConverter.DwgResult(errorCode = -95, diagText = "DWG转换被取消"))
            }
        }
    }
}
