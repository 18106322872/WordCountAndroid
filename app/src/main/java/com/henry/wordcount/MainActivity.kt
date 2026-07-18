package com.henry.wordcount

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = mutableListOf<Uri>()
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { uris.add(it) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                    ?.forEach { uris.add(it) }
            }
            Intent.ACTION_VIEW -> {
                intent.data?.let { uris.add(it) }
            }
        }
        setContent { WordCountApp(initialUris = uris) }
    }
}

data class InnerResult(
    val name: String,
    val words: Int, val fe: Int, val nc: Int, val chars: Int,
    val pages: Int?
)

data class FileResult(
    val name: String,
    val ext: String,
    val isArchive: Boolean,
    val words: Int, val fe: Int, val nc: Int, val chars: Int,
    val pages: Int?,
    val pagesReason: String?,
    val sheets: List<String>,
    val inner: List<InnerResult>,
    val hasUnreliable: Boolean,
)

data class FileEntry(
    val id: String,
    val displayName: String,
    val cachePath: String,
    var selected: Boolean = true,
    val result: FileResult? = null,
    val error: String? = null,
    val rawResult: Map<*, *>? = null,
)

/** 从 URI 推断文件扩展名（优先 filename，其次 MIME type） */
private fun guessExt(context: android.content.Context, uri: Uri): String {
    val name = androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)?.name
    if (!name.isNullOrBlank()) {
        val dotIdx = name.lastIndexOf('.')
        if (dotIdx > 0) return name.substring(dotIdx).lowercase()
    }
    try {
        val mime = context.contentResolver.getType(uri)
        if (!mime.isNullOrBlank()) {
            if (mime.startsWith("image/")) {
                return when (mime) {
                    "image/png" -> ".png"
                    "image/jpeg" -> ".jpg"
                    "image/gif" -> ".gif"
                    "image/webp" -> ".webp"
                    "image/bmp" -> ".bmp"
                    else -> ".png"
                }
            }
            return when (mime) {
                "application/pdf" -> ".pdf"
                "text/plain" -> ".txt"
                "application/msword", "application/vnd.ms-word" -> ".doc"
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> ".docx"
                "application/vnd.ms-excel" -> ".xls"
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> ".xlsx"
                "application/vnd.ms-powerpoint" -> ".ppt"
                "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> ".pptx"
                "application/dxf", "application/x-dxf" -> ".dxf"
                "application/dwg", "image/vnd.dwg" -> ".dwg"
                "application/zip", "application/x-zip-compressed", "application/x-7z-compressed",
                "application/x-rar-compressed", "application/gzip",
                "application/x-tar", "application/x-bzip2", "application/x-xz" -> {
                    // 从文件名推断压缩格式
                    val fname = name ?: ""
                    when {
                        fname.endsWith(".zip", ignoreCase = true) -> ".zip"
                        fname.endsWith(".rar", ignoreCase = true) -> ".rar"
                        fname.endsWith(".7z", ignoreCase = true) -> ".7z"
                        fname.endsWith(".tar", ignoreCase = true) ||
                        fname.endsWith(".tgz", ignoreCase = true) -> ".tar"
                        fname.endsWith(".gz", ignoreCase = true) -> ".gz"
                        fname.endsWith(".bz2", ignoreCase = true) -> ".bz2"
                        fname.endsWith(".xz", ignoreCase = true) -> ".xz"
                        else -> ".zip"
                    }
                }
                else -> ""
            }
        }
    } catch (_: Exception) {}
    try {
        val path = uri.path
        if (!path.isNullOrBlank()) {
            val dotIdx = path.lastIndexOf('.')
            if (dotIdx > 0) return path.substring(dotIdx).lowercase()
        }
    } catch (_: Exception) {}
    return ""
}

// ══════════════════════════════════════════════════════════
// 文件类型分类（v1.0.15：全部纯 Java/Kotlin，不再依赖 Chaquopy）
// ══════════════════════════════════════════════════════════
private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "bmp", "tif", "tiff", "gif", "webp")
private val OLD_OFFICE_EXTS = setOf("doc", "xls", "ppt")       // POI scratchpad
private val OOXML_EXTS = setOf("docx", "xlsx", "pptx")          // OoXmlEngine（ZIP+XML）
private val PDF_EXTS = setOf("pdf")                              // PdfExtractor（自实现）
private val DWG_EXTS = setOf("dwg")                              // DwgEngine
private val ARCHIVE_EXTS = setOf(                                // ArchiveEngine（commons-compress）
    "zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz"
)
private val TXT_EXTS = setOf("txt")                               // 纯 Kotlin 读文本

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordCountApp(initialUris: List<Uri>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val entries = remember { mutableStateListOf<FileEntry>() }
    var busy by remember { mutableStateOf(false) }

    // SAF 文件选择器
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) addFiles(context, scope, snackbar, entries, busyRef = { busy }, busySet = { busy = it }, uris)
    }

    fun pickWithPermission() {
        picker.launch(arrayOf("*/*"))
    }

    // 处理启动时从千牛/微信分享进来的文件
    LaunchedEffect(Unit) {
        if (initialUris.isNotEmpty()) {
            addFiles(context, scope, snackbar, entries, busyRef = { busy }, busySet = { busy = it }, initialUris)
        }
    }

    // 合计统计（已选中且有结果的项目）
    val totals = run {
        val sel = entries.filter { it.selected && it.result != null }
        var w = 0; var fe = 0; var nc = 0; var ch = 0; var pg = 0
        sel.forEach { r ->
            w += r.result!!.words; fe += r.result!!.fe; nc += r.result!!.nc; ch += r.result!!.chars
            r.result!!.pages?.let { pg += it }
        }
        mapOf("words" to w, "fe" to fe, "nc" to nc, "chars" to ch, "pages" to pg)
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("字数统计  v1.0.15") }) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface(shadowElevation = 4.dp) {
                Column(Modifier.padding(12.dp)) {
                    // 第一行：全选 / 取消全选 / 清空列表 / 合计
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (entries.isNotEmpty()) {
                            OutlinedButton(onClick = {
                                val idx = entries.indices
                                for (i in idx) entries[i] = entries[i].copy(selected = true)
                            }, modifier = Modifier.padding(end = 4.dp)) { Text("全选", style = MaterialTheme.typography.labelLarge) }
                            OutlinedButton(onClick = {
                                val idx = entries.indices
                                for (i in idx) entries[i] = entries[i].copy(selected = false)
                            }) { Text("取消全选", style = MaterialTheme.typography.labelLarge) }
                            OutlinedButton(onClick = {
                                entries.clear()
                            }) { Icon(Icons.Default.DeleteSweep, contentDescription = "清空", Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp)); Text("清空", style = MaterialTheme.typography.labelLarge) }
                        }
                        Spacer(Modifier.weight(1f))
                        Text("合计（已选 ${entries.count { it.selected }} 项）", fontWeight = FontWeight.Bold)
                    }
                    // 第二行：字数/中文/非中文/页数
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Text("字数 ${totals["words"]} ｜ 中文 ${totals["fe"]} ｜ 非中文 ${totals["nc"]} ｜ 页数 ${totals["pages"]}")
                    }
                    Spacer(Modifier.padding(4.dp))
                    // 第三行：操作按钮
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { pickWithPermission() }, modifier = Modifier.weight(1f)) { Text("选择文件") }
                        OutlinedButton(
                            onClick = { exportUnreliable(context, scope, snackbar, entries) },
                            modifier = Modifier.weight(1f),
                            enabled = entries.any { it.selected && it.result?.hasUnreliable == true }
                        ) { Text("导出不可识别内容") }
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (entries.isEmpty() && !busy) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Description,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = Color(0xFFBDBDBD)
                    )
                    Spacer(Modifier.padding(12.dp))
                    Text("从千牛/微信 → 长按文件 → 用其他应用打开 → 选「字数统计」",
                        color = Color.Gray, modifier = Modifier.padding(horizontal = 8.dp))
                    Text("或点下方「选择文件」从本机选取",
                        color = Color.Gray, modifier = Modifier.padding(horizontal = 8.dp))
                }
            }
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(entries, key = { it.id }) { entry ->
                    FileCard(entry,
                        cachePath = entry.cachePath,
                        onToggle = { e ->
                            val i = entries.indexOf(e)
                            if (i >= 0) entries[i] = e.copy(selected = !e.selected)
                        },
                        onDelete = { e ->
                            val i = entries.indexOf(e)
                            if (i >= 0) entries.removeAt(i)
                        },
                    )
                }
                if (busy) item {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}

@Composable
fun FileCard(entry: FileEntry, cachePath: String, onToggle: (FileEntry) -> Unit, onDelete: (FileEntry) -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = entry.selected, onCheckedChange = { onToggle(entry) })
                Column(Modifier.weight(1f)) {
                    // 文件名可点击 → 打开文件（用其他应用）
                    Text(
                        text = entry.displayName,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable {
                            tryOpenFile(cachePath)
                        },
                        color = Color(0xFF1565C0)  // 蓝色提示可点击
                    )
                    val r = entry.result
                    if (r != null) {
                        // 统计摘要行：字数 | 中文 | 非中文 | 页数
                        val pagesStr = when {
                            r.pages != null -> "页 ${r.pages}"
                            r.pagesReason != null -> r.pagesReason
                            else -> ""
                        }
                        Text(
                            "字数 ${r.words} ｜ 中文 ${r.fe} ｜ 非中文 ${r.nc}" +
                                    (if (pagesStr.isNotBlank()) " ｜ $pagesStr" else ""),
                            style = MaterialTheme.typography.bodySmall, color = Color.Gray
                        )
                        if (r.hasUnreliable) Text("含无法准确统计的内容（可导出）", style = MaterialTheme.typography.bodySmall, color = Color(0xFFB26A00))
                    } else if (entry.error != null) {
                        val shortErr = entry.error!!.substringBefore('\n').take(200)
                        Text("处理出错：$shortErr", style = MaterialTheme.typography.bodySmall, color = Color(0xFFB00020))
                    } else {
                        Text("统计中…", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                }
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
                    Text(" ${entry.result?.ext?.uppercase() ?: "?"} ", Modifier.padding(6.dp, 2.dp), style = MaterialTheme.typography.labelSmall)
                }
                IconButton(onClick = { onDelete(entry) }) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "删除", tint = Color.Gray)
                }
            }
            // 内部文件列表（压缩包内各文件详情）
            entry.result?.inner?.forEach { inner ->
                Row(Modifier.padding(start = 40.dp, top = 2.dp)) {
                    Text("└ ${inner.name}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("字 ${inner.words} 中 ${inner.fe} 非 ${inner.nc}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
            // 工作表名列表（Excel 等）
            entry.result?.sheets?.forEach { s ->
                Text("▪ $s", Modifier.padding(start = 40.dp, top = 2.dp), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
        }
    }
}

/** 封装「用其他应用打开文件」的 Intent 操作 */
private fun tryOpenFile(filePath: String) {
    try {
        val file = File(filePath)
        if (!file.exists()) return
        // 注意：cacheDir 的文件需要通过 FileProvider 暴露 URI
        // 这里用一个简单的 VIEW intent，让系统选择应用打开
        val context = PlatformContextProvider.context ?: return
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, guessMimeType(file.extension.lowercase()))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "打开文件"))
    } catch (e: Exception) {
        Log.w("WordCount", "打开文件失败: ${e.message}")
    }
}

/** 简易 MIME 类型推断（用于 Intent 打开） */
private fun guessMimeType(ext: String): String = when (ext) {
    "pdf" -> "application/pdf"
    "doc" -> "application/msword"
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "xls" -> "application/vnd.ms-excel"
    "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    "ppt" -> "application/vnd.ms-powerpoint"
    "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    "txt", "log", "csv" -> "text/plain"
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "html", "htm" -> "text/html"
    "zip" -> "application/zip"
    "rar" -> "application/x-rar-compressed"
    "7z" -> "application/x-7z-compressed"
    "dwg" -> "image/vnd.dwg"
    "dxf" -> "application/dxf"
    else -> "*/*"
}

/** 平台 Context 提供器（Compose 外部需要访问 Context 时使用）*/
object PlatformContextProvider {
    @JvmStatic
    var context: android.content.Context? = null
}

// ---------------------------------------------------------------------------
// 业务逻辑
// ---------------------------------------------------------------------------

/**
 * 纯 Kotlin 字数统计（与 Python 端 wordcount.py 算法完全一致）。
 * Word 口径：字数 = 中文字符和朝鲜语单词 + 非中文单词
 * 返回 (words, fe, nc, chars)
 */
internal fun countTextKotlin(text: String): Quadruple<Int, Int, Int, Int> {
    val farEastRegex = Regex("[\\u1100-\\u11FF\\u3000-\\u303F\\u3130-\\u318F\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uA960-\\uA97C\\uAC00-\\uD7A3\\uD7B0-\\uD7FF\\uF900-\\uFAFF\\uFF00-\\uFFEF]")
    val nonCjkRegex = Regex("[^\\s\\u1100-\\u11FF\\u3000-\\u303F\\u3130-\\u318F\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uA960-\\uA97C\\uAC00-\\uD7A3\\uD7B0-\\uD7FF\\uF900-\\uFAFF\\uFF00-\\uFFEF]+")

    val paragraphs = text.split(Regex("\\n\\s*\\n"))
    var totalFe = 0
    var totalNc = 0
    var totalChars = 0

    for (para in paragraphs) {
        val trimmed = para.trim()
        if (trimmed.isNotEmpty()) {
            totalFe += farEastRegex.findAll(trimmed).count()
            totalNc += nonCjkRegex.findAll(trimmed).count()
            totalChars += trimmed.replace(Regex("\\s"), "").length
        }
    }

    val words = totalFe + totalNc
    return Quadruple(words, totalFe, totalNc, totalChars)
}

/**
 * 根据文件内容和类型估算页数。
 * 规则：
 *   - DOC/DOCX：每 2000 中文字符 ≈ 1 页（Word 默认 A4 宋体五号约每页~40行×38字≈1520字）
 *   - XLSX：按工作表数计页数
 *   - PPT/PPTX：按幻灯片页数计
 *   - PDF：尝试从元数据读取，否则按字符估算
 *   - TXT/其他：按字符数估算（中文 1800 字/页，英文 500 词/页）
 */
internal fun estimatePages(text: String, ext: String, sheets: List<String> = emptyList(), metaPages: Int? = null): Pair<Int?, String?> {
    // 如果已有元数据中的页数（如 PDF 的 /Count），优先使用
    metaPages?.let { return Pair(it, null) }

    // 特殊格式：PPT/PPTX 按工作表/幻灯片数
    if (ext == ".ppt" || ext == ".pptx") {
        val slideCount = if (sheets.isNotEmpty()) sheets.size else null
        slideCount?.let { return Pair(it, null) }
    }

    // 特殊格式：XLSX 按工作表数
    if (ext == ".xlsx" || ext == ".xls") {
        val sheetCount = if (sheets.isNotEmpty()) sheets.size else null
        sheetCount?.let { return Pair(it, null) }
    }

    // 通用估算：基于字符数
    if (text.isBlank()) return Pair(null, null)
    val stats = countTextKotlin(text)
    val charCount = stats.fourth
    val cjkCount = stats.second

    return if (cjkCount > 0) {
        // 含中文：约 1800 字/页
        val est = maxOf(1, (charCount + 1799) / 1800)
        Pair(est, "~${est}页（估算）")
    } else {
        // 纯西文：约 3300 字符/页
        val est = maxOf(1, (charCount + 3299) / 3300)
        Pair(est, "~${est}页（估算）")
    }
}

data class Quadruple<out A, out B, out C, out D>(val first: A, val second: B, val third: C, val fourth: D)

private fun copyUriToCache(context: android.content.Context, uri: Uri): File {
    val name = androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)?.name
        ?: "file_${System.currentTimeMillis()}"
    val safe = name.replace(Regex("[^\\w.\\-]"), "_")
    var outName = "wc_${System.currentTimeMillis()}_$safe"
    val extFromName = safe.substringAfterLast('.', "").lowercase()
    if (extFromName.isBlank() || extFromName.length > 6 || extFromName.contains(" ")) {
        val guessed = guessExt(context, uri)
        if (guessed.isNotBlank()) outName += guessed
    }
    val out = File(context.cacheDir, outName)
    context.contentResolver.openInputStream(uri)?.use { input ->
        out.outputStream().use { input.copyTo(it) }
    }
    return out
}

/**
 * 核心：添加并处理文件列表（v1.0.15 全部纯 Java/Kotlin，零 Python 依赖）。
 *
 * 路由规则：
 *   - 图片          → OCR（默认禁用）
 *   - 老Office(.doc/.xls/.ppt) → OldOfficeEngine(POI)
 *   - OOXML(.docx/.xlsx/.pptx) → OoXmlEngine(ZIP+XML)
 *   - PDF           → PdfExtractor(自实现解析器)
 *   - DWG           → DwgEngine(二进制扫描)
 *   - 压缩包(.zip等) → ArchiveEngine(commons-compress)
 *   - TXT 及其他    → 直接读 UTF-8 文本
 */
private fun addFiles(
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    snackbar: SnackbarHostState,
    entries: androidx.compose.runtime.snapshots.SnapshotStateList<FileEntry>,
    busyRef: () -> Boolean,
    busySet: (Boolean) -> Unit,
    uris: List<Uri>
) {
    if (busyRef()) return
    // 注册 Context 供外部使用
    PlatformContextProvider.context = context

    scope.launch(Dispatchers.Main) {
        busySet(true)
        try {
            val files = uris.map { copyUriToCache(context, it) }
            val imageFiles = mutableListOf<File>()
            val oldOfficeFiles = mutableListOf<File>()
            val ooxmlFiles = mutableListOf<File>()      // v1.0.15: 纯 Kotlin
            val pdfFiles = mutableListOf<File>()         // v1.0.15: 纯 Kotlin
            val dwgFiles = mutableListOf<File>()
            val archiveFiles = mutableListOf<File>()     // v1.0.15: 新增
            val txtFiles = mutableListOf<File>()

            for (f in files) {
                val ext = f.extension.lowercase().removePrefix(".")
                when {
                    ext in IMAGE_EXTS -> imageFiles.add(f)
                    ext in OLD_OFFICE_EXTS -> oldOfficeFiles.add(f)
                    ext in OOXML_EXTS -> ooxmlFiles.add(f)
                    ext in PDF_EXTS -> pdfFiles.add(f)
                    ext in DWG_EXTS -> dwgFiles.add(f)
                    ext in ARCHIVE_EXTS -> archiveFiles.add(f)
                    ext in TXT_EXTS || ext.isBlank() -> txtFiles.add(f)
                    else -> txtFiles.add(f)  // 其他未知格式当纯文本读
                }
            }

            withContext(Dispatchers.IO) {
                // ── 1. 压缩包处理 ──────────────────────────────
                archiveFiles.forEachIndexed { i, f ->
                    processArchiveFile(i, f, entries)
                }

                // ── 2. OOXML 处理（DOCX/XLSX/PPTX）────────────
                ooxmlFiles.forEachIndexed { i, f ->
                    processOoXmlFile(i, f, entries)
                }

                // ── 3. PDF 处理 ───────────────────────────────
                pdfFiles.forEachIndexed { i, f ->
                    processPdfFile(i, f, entries)
                }

                // ── 4. 老格式 Office（DOC/XLS/PPT）────────────
                oldOfficeFiles.forEachIndexed { i, f ->
                    processOldOfficeFile(i, f, entries)
                }

                // ── 5. DWG 处理 ───────────────────────────────
                dwgFiles.forEachIndexed { i, f ->
                    processDwgFile(i, f, entries)
                }

                // ── 6. TXT 处理 ───────────────────────────────
                txtFiles.forEachIndexed { i, f ->
                    processTxtFile(i, f, entries)
                }

                // ── 7. 图片处理 ───────────────────────────────
                imageFiles.forEachIndexed { i, f ->
                    processImageFile(i, f, context, entries)
                }
            }
        } catch (e: Throwable) {
            Log.e("WordCount", "文件处理异常: ${e.javaClass.simpleName}: ${e.message}", e)
            scope.launch { snackbar.showSnackbar("处理出错：${e.message}") }
        } finally {
            busySet(false)
        }
    }
}

// ══════════════════════════════════════════════════════════
// 各类文件处理函数
// ══════════════════════════════════════════════════════════

private suspend fun processArchiveFile(index: Int, f: File, entries: MutableList<FileEntry>) {
    try {
        val result = ArchiveEngine.processArchive(f)
        val stats = countTextKotlin(result.text)
        val (pages, pagesReason) = estimatePages(result.text, ".${f.extension.lowercase()}", result.sheets)
        val fr = FileResult(
            name = f.name, ext = ".${f.extension.lowercase()}",
            isArchive = true,
            words = stats.first, fe = stats.second, nc = stats.third, chars = stats.fourth,
            pages = pages, pagesReason = pagesReason,
            sheets = result.sheets, inner = result.inner,
            hasUnreliable = false
        )
        entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${index}_arch", displayName = f.name, cachePath = f.absolutePath, result = fr))
    } catch (e: Throwable) {
        Log.w("WordCount", "压缩包解析失败 ${f.name}: ${e.message}")
        entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${index}_arch", displayName = f.name, cachePath = f.absolutePath,
            error = "压缩包解析失败（${e.message}）"))
    }
}

private suspend fun processOoXmlFile(index: Int, f: File, entries: MutableList<FileEntry>) {
    try {
        val text = OoXmlEngine.extractText(f)
        if (text.isBlank()) {
            entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${index}_oo", displayName = f.name, cachePath = f.absolutePath,
                error = "此文件内容为空或无法读取"))
            return
        }
        val stats = countTextKotlin(text)
        val extDot = ".${f.extension.lowercase()}"
        val sheets = extractSheetsFromOoXml(f, extDot)
        val (pages, pagesReason) = estimatePages(text, extDot, sheets)
        val resMap = mapOf(
            "name" to f.name, "ext" to extDot,
            "stats" to mapOf("words" to stats.first, "fe" to stats.second, "nc" to stats.third, "chars" to stats.fourth),
            "meta" to mapOf<String, Any?>("sheets" to sheets)
        )
        val fr = toFileResult(resMap, f.absolutePath)
        entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${index}_oo", displayName = f.name, cachePath = f.absolutePath, result = fr, rawResult = resMap))
    } catch (e: Throwable) {
        Log.w("WordCount", "OOXML 解析失败 ${f.name}: ${e.message}")
        entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${index}_oo", displayName = f.name, cachePath = f.absolutePath,
            error = "无法解析此文件（${e.message}）"))
    }
}

private suspend fun processPdfFile(index: Int, f: File, entries: MutableList<FileEntry>) {
    try {
        val text = PdfExtractor.extractText(f)
        if (text.isBlank()) {
            // 可能是扫描件/图片 PDF
            entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${index}_pdf", displayName = f.name, cachePath = f.absolutePath,
                error = "PDF 未提取到文字（可能是扫描件或图片 PDF）"))
            return
        }
        val stats = countTextKotlin(text)
        val resMap = mapOf(
            "name" to f.name, "ext" to ".pdf",
            "stats" to mapOf("words" to stats.first, "fe" to stats.second, "nc" to stats.third, "chars" to stats.fourth),
            "meta" to emptyMap<String, Any?>()
        )
        val fr = toFileResult(resMap, f.absolutePath)
        entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${index}_pdf", displayName = f.name, cachePath = f.absolutePath, result = fr, rawResult = resMap))
    } catch (e: Throwable) {
        Log.w("WordCount", "PDF 解析失败 ${f.name}: ${e.message}")
        entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${index}_pdf", displayName = f.name, cachePath = f.absolutePath,
            error = "PDF 解析失败（${e.message}）"))
    }
}

private suspend fun processOldOfficeFile(index: Int, f: File, entries: MutableList<FileEntry>) {
    try {
        val text = OldOfficeEngine.extractText(f)
        if (text.isBlank()) {
            entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${index}_old", displayName = f.name, cachePath = f.absolutePath,
                error = "此老格式文件内容为空或无法读取"))
            return
        }
        val stats = countTextKotlin(text)
        val extDot = ".${f.extension.lowercase()}"
        val (pages, pagesReason) = estimatePages(text, extDot)
        val resMap = mapOf(
            "name" to f.name, "ext" to extDot,
            "stats" to mapOf("words" to stats.first, "fe" to stats.second, "nc" to stats.third, "chars" to stats.fourth),
            "meta" to emptyMap<String, Any?>()
        )
        val fr = toFileResult(resMap, f.absolutePath)
        entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${index}_old", displayName = f.name, cachePath = f.absolutePath, result = fr, rawResult = resMap))
    } catch (e: Throwable) {
        Log.w("WordCount", "老格式解析失败 ${f.name}: ${e.message}")
        entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${index}_old", displayName = f.name, cachePath = f.absolutePath,
            error = "无法解析此老格式（${e.message}），建议另存为 .docx/.xlsx/.pptx"))
    }
}

private suspend fun processDwgFile(index: Int, f: File, entries: MutableList<FileEntry>) {
    try {
        val text = DwgEngine.extractText(f)
        if (text.isBlank()) {
            entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${index}_dwg", displayName = f.name, cachePath = f.absolutePath,
                error = "DWG 文件未提取到文字（可能为纯图形/复杂编码），建议导出为 DXF 后统计"))
            return
        }
        val stats = countTextKotlin(text)
        val resMap = mapOf(
            "name" to f.name, "ext" to ".dwg",
            "stats" to mapOf("words" to stats.first, "fe" to stats.second, "nc" to stats.third, "chars" to stats.fourth),
            "meta" to emptyMap<String, Any?>()
        )
        val fr = toFileResult(resMap, f.absolutePath)
        entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${index}_dwg", displayName = f.name, cachePath = f.absolutePath, result = fr, rawResult = resMap))
    } catch (e: Throwable) {
        Log.w("WordCount", "DWG 解析失败 ${f.name}: ${e.message}")
        entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${index}_dwg", displayName = f.name, cachePath = f.absolutePath,
            error = "DWG 解析失败（${e.message}）"))
    }
}

private suspend fun processTxtFile(index: Int, f: File, entries: MutableList<FileEntry>) {
    try {
        val text = f.readText(Charsets.UTF_8)
        if (text.isBlank()) {
            entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${index}_txt", displayName = f.name, cachePath = f.absolutePath,
                error = "文件内容为空"))
            return
        }
        val stats = countTextKotlin(text)
        val (pages, pagesReason) = estimatePages(text, ".txt")
        val resMap = mapOf(
            "name" to f.name, "ext" to ".txt",
            "stats" to mapOf("words" to stats.first, "fe" to stats.second, "nc" to stats.third, "chars" to stats.fourth),
            "meta" to emptyMap<String, Any?>()
        )
        val fr = toFileResult(resMap, f.absolutePath)
        entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${index}_txt", displayName = f.name, cachePath = f.absolutePath, result = fr, rawResult = resMap))
    } catch (e: Throwable) {
        Log.w("WordCount", "TXT 读取失败 ${f.name}: ${e.javaClass.simpleName}: ${e.message}")
        entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${index}_txt", displayName = f.name, cachePath = f.absolutePath,
            error = "读取失败（${e.message}）"))
    }
}

private suspend fun processImageFile(index: Int, f: File, context: android.content.Context, entries: MutableList<FileEntry>) {
    try {
        if (!OcrEngine.ocrEnabled) {
            entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${index}_img", displayName = f.name, cachePath = f.absolutePath,
                error = "图片文字识别暂不可用（当前版本已默认禁用以防止闪退）"))
        } else {
            val text = OcrEngine.recognize(context, f)
            val stats = countTextKotlin(text)
            val resMap = mapOf(
                "name" to f.name, "ext" to ".img",
                "stats" to mapOf("words" to stats.first, "fe" to stats.second, "nc" to stats.third, "chars" to stats.fourth),
                "meta" to emptyMap<String, Any?>()
            )
            val fr = toFileResult(resMap, f.absolutePath)
            entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${index}_img", displayName = f.name, cachePath = f.absolutePath, result = fr, rawResult = resMap))
        }
    } catch (e: OutOfMemoryError) {
        Runtime.getRuntime().gc()
        Log.w("WordCount", "图片过大 OOM ${f.name}")
        entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${index}_img", displayName = f.name, cachePath = f.absolutePath, error="图片过大，内存不足"))
    } catch (e: Throwable) {
        Log.w("WordCount", "OCR 失败 ${f.name}: ${e.javaClass.simpleName}: ${e.message}")
        entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${index}_img", displayName = f.name, cachePath = f.absolutePath, error="OCR 识别失败（${e.message}）"))
    }
}

// ══════════════════════════════════════════════════════════
// 辅助函数
// ══════════════════════════════════════════════════════════

/** 从 OOXML 文件提取工作表/幻灯片名称 */
private fun extractSheetsFromOoXml(file: File, ext: String): List<String> {
    return try {
        when (ext) {
            ".xlsx" -> {
                ZipFile(file).use { zip ->
                    zip.entries().toList()
                        .filter { it.name.startsWith("xl/worksheets/sheet") && it.name.endsWith(".xml") }
                        .sortedBy { it.name }
                        .mapNotNull { entry ->
                            val nameMatch = Regex("sheet(\\d*)\\.xml").find(entry.name.substringAfterLast('/'))
                            nameMatch?.let { "工作表${it.groupValues[0]}" }
                        }.ifEmpty { listOf("工作表1") }
                }
            }
            ".pptx" -> {
                ZipFile(file).use { zip ->
                    val count = zip.entries().count { it.name.startsWith("ppt/slides/slide") && it.name.endsWith(".xml") }
                    if (count > 0) (1..count).map { "第${it}页" } else emptyList()
                }
            }
            else -> emptyList()
        }
    } catch (_: Exception) { emptyList() }
}

private fun toFileResult(m: Map<*, *>?, srcPath: String): FileResult {
    val stats = m?.get("stats") as? Map<*, *> ?: emptyMap<String, Any>()
    val meta = m?.get("meta") as? Map<*, *> ?: emptyMap<String, Any>()
    val inner = (meta["inner"] as? List<*>)?.mapNotNull { it as? Map<*, *> }.orEmpty().map { im ->
        val s = im["stats"] as? Map<*, *> ?: emptyMap<String, Any>()
        InnerResult(
            name = (im["name"] as? String) ?: "",
            words = (s["words"] as? Number)?.toInt() ?: 0,
            fe = (s["fe"] as? Number)?.toInt() ?: 0,
            nc = (s["nc"] as? Number)?.toInt() ?: 0,
            chars = (s["chars"] as? Number)?.toInt() ?: 0,
            pages = (im["meta"] as? Map<*, *>)?.get("pages") as? Int
        )
    }
    val imgPages = meta["img_pages"] as? List<*>
    val imageOnly = meta["image_only"] as? Boolean ?: false
    val ext = (m?.get("ext") as? String) ?: ""

    // 从 stats 或 m 中取 pages
    val explicitPages = (m?.get("pages") as? Number)?.toInt()
        ?: (stats["pages"] as? Number)?.toInt()

    return FileResult(
        name = (m?.get("name") as? String) ?: "",
        ext = ext,
        isArchive = (m?.get("is_archive") as? Boolean) ?: false,
        words = (stats["words"] as? Number)?.toInt() ?: 0,
        fe = (stats["fe"] as? Number)?.toInt() ?: 0,
        nc = (stats["nc"] as? Number)?.toInt() ?: 0,
        chars = (stats["chars"] as? Number)?.toInt() ?: 0,
        pages = explicitPages ?: (m?.get("pages_reason")?.let { null }),  // 有 reason 则 pages=null
        pagesReason = m?.get("pages_reason") as? String,
        sheets = (meta["sheets"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
        inner = inner,
        hasUnreliable = ext == ".pdf" && (imageOnly || !imgPages.isNullOrEmpty())
    )
}

private fun exportUnreliable(
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    snackbar: SnackbarHostState,
    entries: List<FileEntry>
) {
    scope.launch(Dispatchers.Main) {
        try {
            val sel = entries.filter { it.selected && it.result?.hasUnreliable == true && it.rawResult != null }
            if (sel.isEmpty()) { snackbar.showSnackbar("没有可导出的不可识别内容"); return@launch }
            val filesInfo = sel.map {
                listOf<Any?>(
                    it.result!!.name,
                    it.rawResult!!["stats"],
                    it.rawResult!!["meta"],
                    it.cachePath,
                    it.result!!.ext
                )
            }
            val out = File(context.getExternalFilesDir(null), "无法准确统计内容_${System.currentTimeMillis()}.pdf")
            var res: String? = null
            withContext(Dispatchers.IO) {
                res = PythonEngine.buildExportPdf(context, filesInfo, out.absolutePath)
            }
            if (res != null) {
                val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", out)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/pdf")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "打开导出的 PDF"))
            } else {
                snackbar.showSnackbar("无可导出内容（需 fitz；当前构建未含 pymupdf）")
            }
        } catch (e: Exception) {
            snackbar.showSnackbar("导出失败：${e.message}")
        }
    }
}
