package com.henry.aligntool

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.henry.aligntool.engine.AlignEngine
import com.henry.aligntool.model.AlignOptions
import com.henry.aligntool.model.ExportBy
import com.henry.aligntool.model.MarkMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * UI 状态机：选两份文件 → 设选项 → 跑对齐（后台） → 预览/分享。
 */
class AlignViewModel : ViewModel() {

    data class UiState(
        val sourceName: String = "",
        val targetName: String = "",
        val sourceUri: android.net.Uri? = null,
        val targetUri: android.net.Uri? = null,
        val options: AlignOptions = AlignOptions(),
        val phase: Phase = Phase.IDLE,
        val progressText: String = "",
        val result: AlignEngine.AlignResult? = null,
        val error: String? = null
    )

    enum class Phase { IDLE, RUNNING, DONE, ERROR }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    fun setSource(name: String, uri: android.net.Uri) {
        _state.value = _state.value.copy(sourceName = name, sourceUri = uri)
    }

    fun setTarget(name: String, uri: android.net.Uri) {
        _state.value = _state.value.copy(targetName = name, targetUri = uri)
    }

    fun setOptions(options: AlignOptions) {
        _state.value = _state.value.copy(options = options)
    }

    fun canRun(): Boolean {
        val s = _state.value
        return s.sourceUri != null && s.targetUri != null
    }

    fun run(context: android.content.Context) {
        val s = _state.value
        if (!canRun()) return
        _state.value = s.copy(phase = Phase.RUNNING, progressText = "准备中…", error = null, result = null)
        viewModelScope.launch {
            try {
                val out = withContext(Dispatchers.IO) {
                    runInternal(context, s)
                }
                _state.value = _state.value.copy(phase = Phase.DONE, progressText = "完成", result = out)
            } catch (e: Throwable) {
                _state.value = _state.value.copy(phase = Phase.ERROR, error = e.message ?: "处理失败")
            }
        }
    }

    private fun runInternal(context: android.content.Context, s: UiState): AlignEngine.AlignResult {
        // 1) SAF Uri → 复制到私有缓存（避免 SAF 流只能读一次）
        val cacheDir = File(context.cacheDir, "align_in")
        cacheDir.mkdirs()
        val skelUri: android.net.Uri
        val othUri: android.net.Uri
        val skelFile: File
        val othFile: File
        when (s.options.exportBy) {
            ExportBy.SOURCE -> {
                skelUri = s.sourceUri!!; othUri = s.targetUri!!
                skelFile = copyUri(context, skelUri, File(cacheDir, "skeleton.${extOf(s.sourceName)}"))
                othFile = copyUri(context, othUri, File(cacheDir, "other.${extOf(s.targetName)}"))
            }
            ExportBy.TARGET -> {
                skelUri = s.targetUri!!; othUri = s.sourceUri!!
                skelFile = copyUri(context, skelUri, File(cacheDir, "skeleton.${extOf(s.targetName)}"))
                othFile = copyUri(context, othUri, File(cacheDir, "other.${extOf(s.sourceName)}"))
            }
        }
        // 2) 输出目录（应用私有外部存储，便于 FileProvider 分享）
        val outDir = File(context.getExternalFilesDir(null), "AlignTool")
        outDir.mkdirs()
        val outName = "AlignTool_${System.currentTimeMillis()}.${extOf(skelFile.name)}"
        val outFile = File(outDir, outName)
        // 3) 跑引擎
        val result = AlignEngine.runAlign(skelFile, othFile, s.options, outFile)
        if (!result.success) throw RuntimeException(result.message)
        return result
    }

    private fun copyUri(context: android.content.Context, uri: android.net.Uri, dest: File): File {
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { out -> input.copyTo(out) }
        } ?: throw RuntimeException("无法读取文件: $uri")
        return dest
    }

    private fun extOf(name: String): String {
        val i = name.lastIndexOf('.')
        return if (i >= 0) name.substring(i + 1).lowercase() else "docx"
    }

    /** 预览返回主界面：保留已选文件与选项，清空结果状态。 */
    fun reset() {
        _state.value = _state.value.copy(phase = Phase.IDLE, progressText = "", result = null, error = null)
    }
}
