package com.henry.aligntool.ui

import android.content.Context
import android.content.pm.PackageManager
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.henry.aligntool.AlignViewModel
import com.henry.aligntool.model.AlignOptions
import com.henry.aligntool.model.ExportBy
import com.henry.aligntool.model.MarkMode

/**
 * 主屏幕：选两份文件 + 选项（直接铺在首页，不再弹窗）+ 开始。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: AlignViewModel
) {
    val state by viewModel.state.collectAsState()
    val ctx = LocalContext.current
    // 启动即读取上次崩溃日志（若有），读后删除，避免重复显示
    val crashLog = remember { readCrashLog(ctx) }

    val sourcePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { viewModel.setSource(queryName(ctx, it), it) }
    }
    val targetPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { viewModel.setTarget(queryName(ctx, it), it) }
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

            // ───────── 选项直接放在首页（不再弹窗）─────────
            OptionsSection(
                options = state.options,
                onOptionsChange = { viewModel.setOptions(it) }
            )

            Button(
                onClick = { viewModel.run(ctx) },
                enabled = viewModel.canRun() && state.phase != AlignViewModel.Phase.RUNNING,
                modifier = Modifier.fillMaxWidth()
            ) { Text("开始对照") }

            if (state.phase == AlignViewModel.Phase.RUNNING) {
                Text(state.progressText, color = MaterialTheme.colorScheme.primary)
            }
            if (state.phase == AlignViewModel.Phase.ERROR) {
                Text("出错：${state.error}", color = MaterialTheme.colorScheme.error)
            }

            // 版本号（与 WordCount 一致，首页可见，便于确认用的是哪个版本）
            Text(
                "版本：${appVersion(ctx)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

/** 选项区：骨架 / 对方语言位置 / 标记 —— 改动即时生效，无需弹窗确认。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptionsSection(
    options: AlignOptions,
    onOptionsChange: (AlignOptions) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("对照选项", style = MaterialTheme.typography.labelMedium)
            Text("以哪份文件为骨架（输出基于它，插入另一份语言）", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = options.exportBy == ExportBy.SOURCE,
                    onClick = { onOptionsChange(options.copy(exportBy = ExportBy.SOURCE)) },
                    label = { Text("原文") }
                )
                FilterChip(
                    selected = options.exportBy == ExportBy.TARGET,
                    onClick = { onOptionsChange(options.copy(exportBy = ExportBy.TARGET)) },
                    label = { Text("译文") }
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = options.otherFirst,
                    onCheckedChange = { onOptionsChange(options.copy(otherFirst = it)) }
                )
                Text("对方语言显示在骨架之前")
            }

            Text("标记对方语言（便于肉眼区分双语）", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MarkMode.values().forEach { m ->
                    FilterChip(
                        selected = options.markSource == m,
                        onClick = { onOptionsChange(options.copy(markSource = m)) },
                        label = {
                            Text(
                                when (m) {
                                    MarkMode.NONE -> "不标记"
                                    MarkMode.BOLD -> "加粗"
                                    MarkMode.HIGHLIGHT -> "高亮"
                                }
                            )
                        }
                    )
                }
            }
        }
    }
}

/** 读取 App 版本号（packageManager，稳定可靠）。 */
fun appVersion(context: Context): String {
    return try {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        pi.versionName ?: "?"
    } catch (_: PackageManager.NameNotFoundException) {
        "?"
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
