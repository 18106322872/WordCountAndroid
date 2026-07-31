package com.henry.aligntool

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.henry.aligntool.ui.MainScreen
import com.henry.aligntool.ui.PreviewScreen
import java.io.File

/**
 * 应用入口：Compose + ViewModel。
 * 根据 AlignViewModel 的 phase 在「主界面」与「结果页」之间切换；
 * 结果页通过 FileProvider 调起系统分享。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: AlignViewModel = viewModel()
            val state by vm.state.collectAsStateWithLifecycle()

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
}
