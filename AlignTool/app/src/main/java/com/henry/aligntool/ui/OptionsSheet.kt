package com.henry.aligntool.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.henry.aligntool.model.AlignOptions
import com.henry.aligntool.model.ExportBy
import com.henry.aligntool.model.MarkMode

/**
 * 选项面板（等价桌面 GUI 选项区，spec §5.6）。
 *
 * - exportBy：以哪份文件为「骨架」（原文 / 译文）。
 * - otherFirst：对方语言插在骨架块之前还是之后。
 * - markSource：是否标记对方语言（加粗 / 黄底高亮），便于肉眼区分双语。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OptionsSheetContent(
    options: AlignOptions,
    onConfirm: (AlignOptions) -> Unit,
    onDismiss: () -> Unit
) {
    var exportBy by remember { mutableStateOf(options.exportBy) }
    var otherFirst by remember { mutableStateOf(options.otherFirst) }
    var markSource by remember { mutableStateOf(options.markSource) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(AlignOptions(exportBy, otherFirst, markSource)) }) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        title = { Text("对照选项") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().padding(4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("以哪份文件为骨架", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = exportBy == ExportBy.SOURCE,
                        onClick = { exportBy = ExportBy.SOURCE },
                        label = { Text("原文") }
                    )
                    FilterChip(
                        selected = exportBy == ExportBy.TARGET,
                        onClick = { exportBy = ExportBy.TARGET },
                        label = { Text("译文") }
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = otherFirst, onCheckedChange = { otherFirst = it })
                    Text("对方语言显示在骨架之前")
                }

                Text("标记对方语言", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MarkMode.values().forEach { m ->
                        FilterChip(
                            selected = markSource == m,
                            onClick = { markSource = m },
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
    )
}
