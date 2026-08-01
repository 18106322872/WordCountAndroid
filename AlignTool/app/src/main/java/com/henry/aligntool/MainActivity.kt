package com.henry.aligntool

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.material3.MaterialTheme
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.henry.aligntool.ui.MainScreen
import com.henry.aligntool.ui.PreviewScreen
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用入口：Compose + ViewModel。
 * 根据 AlignViewModel 的 phase 在「主界面」与「结果页」之间切换；
 * 结果页通过 FileProvider 调起系统分享。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // 在 super.onCreate 之前安装全局崩溃捕获，确保能抓到 theme/组合期的崩溃
        installCrashHandler()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val vm: AlignViewModel = viewModel()
                val state by vm.state.collectAsState()

                if (state.phase == AlignViewModel.Phase.DONE && state.result != null) {
                    PreviewScreen(
                        result = state.result!!,
                        onBack = { vm.reset() },
                        onShare = { state.result?.outputFile?.let { shareFile(it) } }
                    )
                } else {
                    MainScreen(viewModel = vm)
                }
            }
        }
    }

    private fun shareFile(outFile: File) {
        val uri = FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            outFile
        )
        val mime = when (outFile.extension.lowercase()) {
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            else -> "*/*"
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "分享双语对照文档"))
    }

    /**
     * 全局未捕获异常捕获：把真实堆栈写入 filesDir/crash.log，
     * 下次启动由 MainScreen 读取并显示，便于无调试环境下定位闪退。
     * 写完仍交给系统默认处理器，保持原生崩溃行为。
     */
    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        val crashFile = File(filesDir, "crash.log")
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val sb = StringBuilder()
                sb.append("CRASH @ $ts\n")
                sb.append("thread: ${thread.name}\n\n")
                sb.append(android.util.Log.getStackTraceString(throwable))
                crashFile.writeText(sb.toString())
            } catch (_: Throwable) {
                // 忽略写入失败
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
