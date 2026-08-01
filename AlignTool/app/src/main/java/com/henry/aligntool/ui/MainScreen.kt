package com.henry.aligntool.ui

import android.content.Context
import android.net.Uri
import java.io.File
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.henry.aligntool.AlignViewModel

/**
 * 主屏幕：选两份文件 + 选项 + 开始。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: AlignViewModel
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showOptions by remember { mutableStateOf(false) }

    val ctx = LocalContext.current
    // 启动即读取上次崩溃日志（若有），读后删除，避免重复显示
    val crashLog = remember { readCrashLog(ctx) }

    val sourcePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { viewModel.setSource(queryName(ctx, it), it) }
    }
    val targetPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { viewModel.setTarget(queryName(ctx, it), it) }
    }

    if (showOptions) {
        OptionsSheetContent(
            options = state.options,
            onConfirm = { viewModel.setOptions(it); showOptions = false },
            onDismiss = { showOptions = false }
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Align Tool · 双语对照") }) }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (crashLog != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    // 用 error 色强调
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "⚠️ 上次运行崩溃，请把下面内容发我定位：",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            crashLog,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("原文文件", style = MaterialTheme.typography.labelMedium)
                    Text(state.sourceName.ifBlank { "未选择" }, style = MaterialTheme.typography.bodyLarge)
                    OutlinedButton(onClick = { sourcePicker.launch("*/*") }) { Text("选择原文") }
                }
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("译文文件", style = MaterialTheme.typography.labelMedium)
                    Text(state.targetName.ifBlank { "未选择" }, style = MaterialTheme.typography.bodyLarge)
                    OutlinedButton(onClick = { targetPicker.launch("*/*") }) { Text("选择译文") }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showOptions = true }, modifier = Modifier.weight(1f)) { Text("选项") }
                Button(
                    onClick = { viewModel.run(ctx) },
                    enabled = viewModel.canRun() && state.phase != AlignViewModel.Phase.RUNNING,
                    modifier = Modifier.weight(1f)
                ) { Text("开始对照") }
            }

            if (state.phase == AlignViewModel.Phase.RUNNING) {
                Text(state.progressText, color = MaterialTheme.colorScheme.primary)
            }
            if (state.phase == AlignViewModel.Phase.ERROR) {
                Text("出错：${state.error}", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

fun queryName(context: Context, uri: Uri): String {
    var name = uri.lastPathSegment ?: "file"
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) name = c.getString(i) ?: name
        }
    }
    return name
}

/** 读取并清除崩溃日志（若存在）。下次启动由主界面展示。 */
fun readCrashLog(context: Context): String? {
    val f = File(context.filesDir, "crash.log")
    if (!f.exists()) return null
    return try {
        f.readText().also { f.delete() }
    } catch (_: Throwable) {
        null
    }
}
