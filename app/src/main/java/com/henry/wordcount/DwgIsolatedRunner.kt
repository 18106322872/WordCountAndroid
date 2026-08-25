package com.henry.wordcount

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * v1.5.13: 主进程侧调用器 —— 通过 Messenger 跨进程调用 DwgIsolatedService。
 *
 * 关键安全网：
 *   1. 绑定超时（8s）：防止 service 进程起不来时永久挂起。
 *   2. 转换超时（15min）：LibreDWG 对超大/损坏文件可能长时间卡住；超时后断开连接，
 *      主进程直接拿到失败结果，绝不阻塞 UI、绝不崩溃。
 *   3. 进程崩溃不可见：若 :dwgisolated 进程 native 崩溃，bind 会断开（onServiceDisconnected），
 *      我们在超时/断开兜底里返回失败 → 主流程降级。
 *
 * v1.9.12 重大修复（切后台统计停止的根因）：
 *   原实现所有 Messenger / 超时 / 回复全部挂在 Looper.getMainLooper() 上。主 app 切后台后，
 *   即使有前台 service，主线程消息泵在部分厂商 ROM / Android 14+ 仍会被节流甚至冻结，
 *   导致 :dwgisolated 回传的转换结果永远送达不到 → suspendCancellableCoroutine 永久挂起 →
 *   统计"显示运行中其实已停"。现改为独立 HandlerThread 的 Looper 处理 IPC，
 *   与 UI 主线程解耦，后台也能持续收发。
 *
 * 用法：
 *   val res = DwgIsolatedRunner.convertToPdf(context, dwgPath, pdfPath)
 *   // res.path != null 表示成功；否则 res.errorCode 含原因
 */
object DwgIsolatedRunner {

    private const val BIND_TIMEOUT_MS = 8_000L
    // v1.9.20: 10min（桌面单文件实测最长 ~492s）。原 15min 在后台卡死时每个文件拖 15 分钟，
    // 进度看起来"完全不动"；10min 平衡"不误杀大文件"与"快速失败继续"。
    private const val CONVERT_TIMEOUT_MS = 600_000L

    // v1.9.12: 独立 IPC 线程，避免主 Looper 后台冻结导致转换结果无法回传
    @Volatile
    private var ipcThread: HandlerThread? = null
    @Volatile
    private var ipcHandler: Handler? = null

    // v1.9.36: :dwgisolated 不再自己 startForeground，而是作为纯 bound service 被前台进程
    // CountingService(:countservice) 绑定，共享前台优先级。通知栏只保留 CountingService 的
    // 一个进度通知，不再有 "DWG 转换进行中" 的额外通知。
    @Volatile
    private var started: Boolean = false

    @Synchronized
    private fun ipcLooper(): Looper {
        var t = ipcThread
        if (t == null || !t.isAlive) {
            t = HandlerThread("dwg-isolated-ipc").also { it.start() }
            ipcThread = t
        }
        return t.looper
    }

    private fun ipcHandler(): Handler {
        var h = ipcHandler
        if (h == null || h.looper !== ipcLooper()) {
            h = Handler(ipcLooper())
            ipcHandler = h
        }
        return h
    }

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
     * 绑定超时 8s + 转换超时 15min + 进程崩溃(onServiceDisconnected)兜底。
     * v1.9.12: 全部基于独立 IPC 线程 Looper，后台不冻结。
     */
    private suspend fun runConvert(context: Context, input: String, output: String, requestWhat: Int): DwgConverter.DwgResult {
        return suspendCancellableCoroutine { cont ->
            val handler = ipcHandler()
            var connection: ServiceConnection? = null
            var serviceMessenger: Messenger? = null
            var done = false

            val finish: (DwgConverter.DwgResult) -> Unit = finish@{ result ->
                if (done) return@finish
                done = true
                handler.removeCallbacksAndMessages(null)
                // v1.9.13: unbind 必须在 bind 所在的 ipcLooper 线程执行（bind 已在 ipc 线程发起），
                // 用 handler(ipcLooper) post 保证线程一致，且任意线程调用 finish 都安全。
                handler.post {
                    try { connection?.let { context.unbindService(it) } } catch (_: Throwable) {}
                    // v1.9.36: 配合 service 每文件 stopSelf，确保下一文件重新 bindService 拉起全新
                    // 干净进程。若依赖 onServiceDisconnected 异步回调，可能因延迟导致下一文件仍走
                    // 复用路径而失败；此处主动 reset started 标志。
                    started = false
                }
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
                    handler.removeCallbacks(bindTimeoutRunnable)
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
                        // v1.9.12: 回复走独立 IPC 线程 Looper，后台也不冻结
                        replyTo = Messenger(object : Handler(ipcLooper()) {
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
                        handler.postDelayed(convertTimeoutRunnable, CONVERT_TIMEOUT_MS)
                    } catch (e: Throwable) {
                        Log.e("DwgIsolated", "send request failed: ${e.message}", e)
                        finish(DwgConverter.DwgResult(errorCode = -99, diagText = "无法发送转换请求：${e.message}"))
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    // 隔离进程崩溃会触发此回调（native crash 杀进程）
                    started = false
                    Log.w("DwgIsolated", "service disconnected (process likely crashed)")
                    finish(DwgConverter.DwgResult(errorCode = -96, diagText = "DWG转换进程崩溃（文件可能损坏或不兼容）"))
                }
            }

            val intent = Intent(context, DwgIsolatedService::class.java)
            // v1.9.13 根因修复：把 startService+bindService 放到独立 IPC 线程(ipcLooper)执行，
            // 使 ServiceConnection.onServiceConnected 在 ipcLooper 回调，而非主 Looper。
            // 主 app 切后台后主线程消息泵被 OEM/Android 节流冻结，onServiceConnected 不触发 ->
            // serviceMessenger.send(request) 永不执行 -> 转换根本没发起 -> “后台只待机不统计”。
            // 改在 ipcLooper 线程发起 bind，回调也走 ipcLooper，后台可持续收发。
            handler.post {
                try {
                    // v1.9.36: :dwgisolated 不再调用 startForegroundService；直接 bindService。
                    // 调用方 CountingService(:countservice) 是前台服务进程，bind 后 :dwgisolated
                    // 共享前台优先级，切后台也不会被冻结，同时避免在通知栏产生额外的 DWG 转换通知。
                    val bound = context.bindService(intent, connection!!, Context.BIND_AUTO_CREATE)
                    if (!bound) {
                        Log.w("DwgIsolated", "bindService returned false")
                        finish(DwgConverter.DwgResult(errorCode = -97, diagText = "DWG转换服务绑定失败"))
                        return@post
                    }
                } catch (e: Throwable) {
                    Log.e("DwgIsolated", "start/bind failed: ${e.message}", e)
                    finish(DwgConverter.DwgResult(errorCode = -97, diagText = "DWG转换服务启动失败：${e.message}"))
                }
            }

            handler.postDelayed(bindTimeoutRunnable, BIND_TIMEOUT_MS)

            cont.invokeOnCancellation {
                finish(DwgConverter.DwgResult(errorCode = -95, diagText = "DWG转换被取消"))
            }
        }
    }

    /**
     * v1.9.36: addFiles 结束后显式停止 :dwgisolated 进程。统计批次内隔离进程被
     * CountingService 绑定保持前台，此处 finally 中干净回收，防止进程泄漏。
     */
    fun stopIsolated(context: Context) {
        val handler = ipcHandler()
        handler.post {
            started = false
            try { DwgIsolatedService.stopService(context) } catch (_: Throwable) {}
        }
    }
}
