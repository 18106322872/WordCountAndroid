package com.henry.aligntool.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.henry.aligntool.engine.AlignEngine

/**
 * 结果页（spec §6 预览/分享）。
 * 手机版 MVP 不渲染 OOXML 内容，而是展示配对统计并提供系统分享（FileProvider）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(
    result: AlignEngine.AlignResult,
    onBack: () -> Unit,
    onShare: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("完成") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("已生成双语对照文档", style = MaterialTheme.typography.titleMedium)
            Text("状态：${if (result.success) "成功" else "失败"}", style = MaterialTheme.typography.bodyLarge)
            Text("配对块数：${result.paired}", style = MaterialTheme.typography.bodyLarge)
            Text("未配对块数：${result.extras}", style = MaterialTheme.typography.bodyLarge)
            Text("输出文件：${result.outputFile?.name ?: "-"}", style = MaterialTheme.typography.bodyMedium)
            if (result.message.isNotBlank()) {
                Text("备注：${result.message}", style = MaterialTheme.typography.bodyMedium)
            }

            Button(onClick = onShare, modifier = Modifier.fillMaxWidth()) { Text("分享文档") }
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("返回") }
        }
    }
}
