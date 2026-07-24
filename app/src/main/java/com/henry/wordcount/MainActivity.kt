package com.henry.wordcount

import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {
    /** 外部可通过此引用向已有列表追加新文件（onNewIntent 时使用） */
    companion object {
        @Volatile var pendingUris: List<Uri>? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = extractUrisFromIntent(intent)
        setContent { WordCountApp(initialUris = uris) }
    }

    /** v1.0.16: 处理从微信/千牛等应用后续传入的文件，追加到已有列表而非替换 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // 必须调用，否则 getIntent() 返回旧 intent
        val newUris = extractUrisFromIntent(intent)
        if (newUris.isNotEmpty()) {
            pendingUris = newUris
        }
    }

    private fun extractUrisFromIntent(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        return mutableListOf<Uri>().apply {
            when (intent.action) {
                Intent.ACTION_SEND -> {
                    (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { add(it) }
                }
                Intent.ACTION_SEND_MULTIPLE -> {
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.forEach { add(it) }
                }
                Intent.ACTION_VIEW -> { intent.data?.let { add(it) } }
            }
        }
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
    // 1) 从 URI 的 display name 取扩展名
    val name = androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)?.name
    if (!name.isNullOrBlank()) {
        val dotIdx = name.lastIndexOf('.')
        if (dotIdx > 0) return name.substring(dotIdx + 1).lowercase() // ← v1.1.31: 不含前导点
    }
    // 2) 从 ContentResolver 的 MIME type 反推
    try {
        val mime = context.contentResolver.getType(uri)
        if (!mime.isNullOrBlank()) {
            if (mime.startsWith("image/")) {
                // 图片 MIME → 扩展名映射
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
                // 压缩包
                "application/zip" -> ".zip"
                "application/x-zip-compressed" -> ".zip"
                "application/x-rar-compressed", "application/rar" -> ".rar"
                "application/gzip", "application/x-gzip" -> ".gz"
                "application/x-tar", "application/tar" -> ".tar"
                "application/x-7z-compressed" -> ".7z"
                else -> ""
            }
        }
    } catch (_: Exception) {}
    // 3) 从 URI path 取（content URI 通常无效，但 file:// 可以）
    try {
        val path = uri.path
        if (!path.isNullOrBlank()) {
            val dotIdx = path.lastIndexOf('.')
            if (dotIdx > 0) return path.substring(dotIdx).lowercase()
        }
    } catch (_: Exception) {}
    return ""
}

/** v1.1.50: 为文件名生成稳定的短hash（用于通用名后缀，区分同名文件） */
private fun absoluteHashCode(s: String): Int {
    var h = 0
    for (c in s) {
        h = 31 * h + c.code
        // 防止溢出为负数时显示问题（取绝对值但保留分布）
        if (h == Int.MIN_VALUE) h = 0 else if (h < 0) h = -h
    }
    return h
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordCountApp(initialUris: List<Uri>) {
    val context = LocalContext.current
    // 运行时从 PackageManager 读取真实版本号，始终与 build.gradle 的 versionName 同步（不依赖 BuildConfig）
    val appVersionName = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.26"
    } catch (e: Exception) {
        "1.0.26"
    }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val entries = remember { mutableStateListOf<FileEntry>() }
    var busy by remember { mutableStateOf(false) }
    // v1.1.1: 文档比较模式开关
    var compareMode by remember { mutableStateOf(false) }

    // SAF 文件选择器（不需要任何存储权限——OpenMultipleDocuments 在所有 Android 版本上均无需授权即可使用）
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) addFiles(context, scope, snackbar, entries, busyRef = { busy }, busySet = { busy = it }, uris)
    }

    /** 选文件入口：直接启动 SAF 选择器（无需任何运行时权限申请） */
    fun pickWithPermission() {
        picker.launch(arrayOf("*/*"))
    }

    // 处理启动时从千牛/微信分享进来的文件
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (initialUris.isNotEmpty()) {
            addFiles(context, scope, snackbar, entries, busyRef = { busy }, busySet = { busy = it }, initialUris)
        }
    }

    // v1.0.16: 监听从微信/千牛后续传入的文件（onNewIntent → pendingUris），追加到已有列表
    androidx.compose.runtime.LaunchedEffect(Unit) {
        // 每隔 2 秒检查一次是否有新文件需要追加（轻量轮询，避免复杂状态管理）
        while (true) {
            kotlinx.coroutines.delay(2000)
            val uris = MainActivity.pendingUris
            if (uris != null && uris.isNotEmpty() && !busy) {
                MainActivity.pendingUris = null // 消费掉
                addFiles(context, scope, snackbar, entries, busyRef = { busy }, busySet = { busy = it }, uris)
            }
        }
    }

    val totals = run {
        val sel = entries.filter { it.selected && it.result != null }
        var w = 0; var fe = 0; var nc = 0; var ch = 0; var pg = 0
        sel.forEach { r ->
            w += r.result!!.words; fe += r.result!!.fe; nc += r.result!!.nc; ch += r.result!!.chars
            pg += r.result!!.pages ?: estimatePages(r.result!!.chars)
        }
        mapOf("words" to w, "fe" to fe, "nc" to nc, "chars" to ch, "pages" to pg)
    }

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Column {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TabToggle("字数统计", !compareMode) { compareMode = false }
                        Spacer(Modifier.width(6.dp))
                        TabToggle("文档比较", compareMode) { compareMode = true }
                        Spacer(Modifier.weight(1f))
                        Text("v${appVersionName}",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.Gray,
                            modifier = Modifier.padding(end = 16.dp))
                    }
                }
            })
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = if (!compareMode) {
            {
                Surface(shadowElevation = 4.dp) {
                    Column(Modifier.padding(12.dp)) {
                        // 全选/取消全选 + 合计行
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
                                }) { Text("清空", style = MaterialTheme.typography.labelLarge) }
                            }
                            Spacer(Modifier.weight(1f))
                            Text("合计（已选 ${entries.count { it.selected }} 项）", fontWeight = FontWeight.Bold)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Text("字数 ${totals["words"]} ｜ 中文 ${totals["fe"]} ｜ 非中文 ${totals["nc"]} ｜ 页数 ${totals["pages"]}")
                        }
                        Spacer(Modifier.padding(4.dp))
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
        } else { {} }
    ) { padding ->
        if (compareMode) {
            CompareScreen(
                context = context,
                scope = scope,
                snackbar = snackbar,
                availableFiles = entries.toList(),
                modifier = Modifier.padding(padding)
            )
        } else {
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
                            onToggle = { e ->
                                val i = entries.indexOf(e)
                                if (i >= 0) entries[i] = e.copy(selected = !e.selected)
                            },
                            onDelete = { e ->
                                val i = entries.indexOf(e)
                                if (i >= 0) entries.removeAt(i)
                            },
                            onOpen = { e -> openWithOtherApp(context, e) }
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
}

@Composable
private fun TabToggle(label: String, selected: Boolean, onClick: () -> Unit) {
    val color = if (selected) MaterialTheme.colorScheme.primary else Color.Gray
    Text(
        label,
        color = color,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.clickable { onClick() }
    )
}

@Composable
fun FileCard(entry: FileEntry, onToggle: (FileEntry) -> Unit, onDelete: (FileEntry) -> Unit, onOpen: (FileEntry) -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = entry.selected, onCheckedChange = { onToggle(entry) })
                Column(Modifier.weight(1f)) {
                    // 文件名可点击：用其它应用打开
                    Text(
                        entry.displayName, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onOpen(entry) }
                    )
                    val r = entry.result
                    if (r != null) {
                        // v1.1.16: 估算页数标注"(估)"——当 pagesReason 含 estimate/layout 时
                        val isEstimated = r.pagesReason?.contains("estimate") == true ||
                            r.pagesReason?.contains("layout") == true
                        val pageLabel = if (isEstimated) "页 ${r.pages ?: estimatePages(r.chars)}(估)"
                            else "页 ${r.pages ?: estimatePages(r.chars)}"
                    Text(
                        "字数 ${r.words} ｜ 中文 ${r.fe} ｜ 非中文 ${r.nc} ｜ $pageLabel" +
                                (if (r.pagesReason != null && !isEstimated) " ｜ ${r.pagesReason}" else ""),
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray
                    )
                        if (r.hasUnreliable) Text("含无法准确统计的内容（可导出）", style = MaterialTheme.typography.bodySmall, color = Color(0xFFB26A00))
                    } else if (entry.error != null) {
                        // 截取首行/前200字，避免满屏 traceback；改前缀为"处理出错"
                        val shortErr = entry.error!!.substringBefore('\n').take(200)
                        Text("处理出错：$shortErr", style = MaterialTheme.typography.bodySmall, color = Color(0xFFB00020))
                    } else {
                        Text("统计中…", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                }
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
                    Text(" ${entry.result?.ext?.uppercase() ?: "?"} ", Modifier.padding(6.dp, 2.dp), style = MaterialTheme.typography.labelSmall)
                }
                // 用其它应用打开
                IconButton(onClick = { onOpen(entry) }) {
                    Text("打开", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                // 删除按钮
                IconButton(onClick = { onDelete(entry) }) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "删除", tint = Color.Gray)
                }
            }
            entry.result?.inner?.forEach { inner ->
                Row(Modifier.padding(start = 40.dp, top = 2.dp)) {
                    Text("└ ${inner.name}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("字 ${inner.words} 中 ${inner.fe} 非 ${inner.nc}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
            entry.result?.sheets?.forEach { s ->
                Text("▪ 工作表：$s", Modifier.padding(start = 40.dp, top = 2.dp), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 业务逻辑
// ---------------------------------------------------------------------------

/**
 * 纯 Kotlin 字数统计（与 Python 端 wordcount.py 算法完全一致）。
 * Word 口径：字数 = 中文字符和朝鲜语单词 + 非中文单词
 * 返回 (words, fe, nc, chars)
 */
fun countTextKotlin(text: String): Quadruple<Int, Int, Int, Int> {
    // FarEast 正则：CJK + 朝鲜语 + 全角（与 Python 端 _FAR 完全一致）
    val farEastRegex = Regex("[\\u1100-\\u11FF\\u3000-\\u303F\\u3130-\\u318F\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uA960-\\uA97C\\uAC00-\\uD7A3\\uD7B0-\\uD7FF\\uF900-\\uFAFF\\uFF00-\\uFFEF]")
    val nonCjkRegex = Regex("[^\\s\\u1100-\\u11FF\\u3000-\\u303F\\u3130-\\u318F\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uA960-\\uA97C\\uAC00-\\uD7A3\\uD7B0-\\uD7FF\\uF900-\\uFAFF\\uFF00-\\uFFEF]+")

    // 按段落分割（与 Python 端 re.split(r"\n\s*\n") 一致）
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

/** 简单四元组（避免引入额外依赖）*/
data class Quadruple<out A, out B, out C, out D>(val first: A, val second: B, val third: C, val fourth: D)

/** 用系统/其它应用打开缓存文件（点击文件名或「打开」触发）。 */
private fun openWithOtherApp(context: android.content.Context, entry: FileEntry) {
    try {
        val file = File(entry.cachePath)
        if (!file.exists()) {
            Log.w("WordCount", "打开失败：缓存文件不存在 ${entry.displayName}")
            return
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val mime = mimeForExt(entry.result?.ext ?: "")
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "用其他应用打开"))
    } catch (e: Throwable) {
        Log.w("WordCount", "打开文件失败 ${entry.displayName}: ${e.message}")
    }
}

/** 扩展名 → MIME type（用于「用其他应用打开」）。 */
private fun mimeForExt(ext: String): String {
    return when (ext.lowercase()) {
        ".doc" -> "application/msword"
        ".docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        ".xls" -> "application/vnd.ms-excel"
        ".xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        ".ppt" -> "application/vnd.ms-powerpoint"
        ".pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        ".pdf" -> "application/pdf"
        ".txt" -> "text/plain"
        ".csv" -> "text/csv"
        ".xml" -> "text/xml"
        ".json" -> "application/json"
        ".html", ".htm" -> "text/html"
        ".png" -> "image/png"
        ".jpg", ".jpeg" -> "image/jpeg"
        ".gif" -> "image/gif"
        ".bmp" -> "image/bmp"
        ".webp" -> "image/webp"
        ".dwg" -> "application/dwg"
        ".dxf" -> "application/dxf"
        else -> "*/*"
    }
}

/** 缓存文件 + 原始显示名称（用于修复 wc_XXXX 临时名问题） */
data class CachedFile(val file: File, val displayName: String)

/** 判断字符串是否像真实文件名（含非十六进制字符、有扩展名、长度合理） */
private fun looksLikeRealFilename(s: String): Boolean {
    val trimmed = s.trim()
    if (trimmed.length < 3 || trimmed.length > 200) return false
    // 纯十六进制/数字字符串（如 UUID 片段）不算真文件名
    val alphaCount = trimmed.count { it.isLetter() }
    if (alphaCount == 0) return false  // 纯数字/符号
    // 必须包含至少一个非十六进制字母（排除纯 hash）
    val nonHexAlpha = trimmed.count { it.isLetter() && it !in 'a'..'f' && it !in 'A'..'F' }
    if (alphaCount > 0 && nonHexAlpha == 0 && trimmed.length > 10) {
        // 全是 hex 字母+数字且较长 → 可能是 hash/UUID
        return false
    }
    return true
}

/**
 * 获取 URI 对应的显示文件名。
 * v1.0.35 重写：所有策略结果必须通过 looksLikeHash() 检测，
 * 某些 ROM 的 ContentResolver / DocumentFile 返回内部 ID（如 9e20f478899dc29...），
 * 必须拦截并降级为友好名称。
 * v1.0.38 增强：新增策略4(PDF元数据/Title)、策略5(URI path更宽松匹配)；
 * 兜底名从时间戳改为基于文件类型的友好名称（不再显示数字ID）。
 */
private fun resolveDisplayName(context: android.content.Context, uri: Uri): String {
    // 辅助函数：检测字符串是否像 hash/UUID/内部 ID
    // v1.1.10 放宽判定：避免误伤真实长文件名（如学号文件名 "3343976213xxx.docx"）
    fun looksLikeHash(s: String): Boolean {
        val t = s.trim()
        if (t.length < 8) return false
        // 纯 hex 字符串（长度>=16，典型 UUID/hash）
        if (t.matches(Regex("^[a-fA-F0-9]{16,}$"))) return true
        if (t.matches(Regex("^[a-fA-F0-9]{8}-([a-fA-F0-9]{4}-){3}[a-fA-F0-9]{12}$"))) return true
        // wc_ 或 file_ 前缀 + 长数字（临时文件名模式）
        if (t.matches(Regex("^(wc_|file_)[0-9a-f]{10,}"))) return true
        // 带扩展名的 hash（如 ContentResolver 返回的 "9e20f478899dc29eb1xxx.pdf"）
        // 某些 ROM 的 SAF 用内部 ID+原始扩展名作为 DISPLAY_NAME
        val dotIdx = t.lastIndexOf('.')
        if (dotIdx > 0 && dotIdx < t.length - 1 && dotIdx <= 64) {
            val base = t.substring(0, dotIdx)
            val ext = t.substring(dotIdx + 1)
            // 扩展名是已知文件类型 且 basename 像 hash/长ID
            if (ext.lowercase() in setOf("pdf","doc","docx","xls","xlsx","ppt","pptx","txt","png","jpg","jpeg","bmp","gif","webp","tif","tiff","zip","rar","7z")
                && base.length >= 10
                && !base.any { it.code in 0x4E00..0x9FFF }
                && base.count { it.isLetterOrDigit() } > base.length * 0.85) {
                // 进一步确认 basename 不像有意义的文件名：
                // 要么纯 hex，要么字母全是小写且无元音/语义，要么超长(>36)
                // v1.1.10: 24→36，避免误伤 10~30 字符的数字编号文件名
                val hasVowel = base.any { it.lowercaseChar() in 'a'..'z' && it.lowercaseChar() in setOf('a','e','i','o','u') }
                val isPureHex = base.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
                val isMostlyDigits = base.count { it.isDigit() } > base.length * 0.6
                // v1.1.11 修复：纯 hex basename ≥16 字符即判定为 hash（之前 >36 导致 28~32 字符的 ContentResolver 内部 ID 漯网）
                // 非纯 hex 的数字编号文件名（如学号）才需要更长阈值
                val hasCjkPunct = base.any { it in setOf('(', ')', '（', '）', '[', ']', '【', '】', '_', '-', '#', '@') }
                if (isPureHex && base.length >= 16 && !hasCjkPunct) return true
                if (!isPureHex && !hasVowel && isMostlyDigits && !hasCjkPunct && base.length > 36) return true
            }
        }
        // 以数字和少量字母为主的长字符串（内部 ID 特征）：字母+数字混合，长度>20，无中文/无常见扩展名
        if (t.length > 20 && !t.contains(".") && !t.any { it.code in 0x4E00..0x9FFF } &&
            t.count { it.isLetterOrDigit() } > t.length * 0.9) return true
        return false
    }

    // 策略1（最可靠）: ContentResolver 查询 OpenableColumns.DISPLAY_NAME
    try {
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst() && cursor.columnCount > 0) {
                val name = cursor.getString(0)
                if (!name.isNullOrBlank() && !looksLikeHash(name)) {
                    Log.d("WordCount", "resolveDisplayName s1(ContentResolver OK): '$name'")
                    return name.trim()
                }
                Log.d("WordCount", "resolveDisplayName s1 被hash拦截: '$name'")
            }
        }
    } catch (_: Throwable) {}

    // 策略2: DocumentFile.fromSingleUri
    androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)?.name?.let { name ->
        if (name.isNotBlank() && !looksLikeHash(name)) {
            Log.d("WordCount", "resolveDisplayName s2(DocumentFile OK): '$name'")
            return name.trim()
        }
        Log.d("WordCount", "resolveDisplayName s2 被hash拦截/空: '$name'")
    }

    // 策略3: 从 URI path 提取文件名（SAF 编码路径解码）
    uri.lastPathSegment?.let { seg ->
        try {
            val decoded = java.net.URLDecoder.decode(seg, "UTF-8")
            var extracted = decoded.substringAfterLast('/')
                .substringAfterLast('\\')
                .substringAfterLast(':')
            // 去掉 SAF document ID 前缀（如 "primary:"）
            extracted = extracted.removePrefix("primary:")
                .removePrefix("home:")
                .removePrefix("document:")
            if (extracted.isNotBlank() && extracted.length < 250 && !looksLikeHash(extracted)) {
                Log.d("WordCount", "resolveDisplayName s3(URI path OK): '$extracted'")
                return extracted.trim()
            }
        } catch (_: Throwable) {}
    }

    // 策略4（v1.0.38 新增）: 对 PDF 文件，尝试从 PDF 元数据提取 /Title
    val ext = guessExt(context, uri)
    if (ext.lowercase() == "pdf") {
        try {
            // 先用 ContentResolver 拿到 InputStream 读取前 64KB（够覆盖大部分 PDF 的 trailer/Info 字典）
            context.contentResolver.openInputStream(uri)?.use { input ->
                val header = ByteArray(minOf(65536, input.available().coerceAtLeast(8192)))
                val totalRead = input.read(header)
                if (totalRead > 0) {
                    val title = extractPdfTitleFromBytes(header, totalRead)
                    if (!title.isNullOrBlank() && !looksLikeHash(title) && title.length <= 150) {
                        val clean = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
                        if (clean.isNotBlank()) {
                            Log.d("WordCount", "resolveDisplayName s4(PDF /Title): '$clean'")
                            return "$clean.pdf"
                        }
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    // 策略5（v1.0.38 新增）: URI path 更宽松匹配——有些 ROM 的 lastPathSegment
    // 包含 document ID 前缀但尾部有真实文件名（如 "primary:Documents/我的文件.pdf"）
    uri.lastPathSegment?.let { seg ->
        try {
            val decoded = java.net.URLDecoder.decode(seg, "UTF-8")
            // 从 decoded 路径中取最后一个路径段（去掉所有已知名前缀）
            var candidate = decoded.substringAfterLast('/')
                .substringAfterLast('\\')
                .substringAfterLast(':')
                .removePrefix("primary:")
                .removePrefix("home:")
                .removePrefix("document:")
                .removePrefix("msf:")
                .removePrefix("raw:")
            // 如果候选名包含 CJK 字符或明显不是纯数字ID，直接采用（跳过 looksLikeHash）
            if (candidate.isNotBlank() && candidate.length <= 200 &&
                (candidate.any { it.code in 0x4E00..0x9FFF } || looksLikeRealFilename(candidate))) {
                Log.d("WordCount", "resolveDisplayName s5(宽松路径): '$candidate'")
                return candidate.trim()
            }
        } catch (_: Throwable) {}
    }

    // 兜底：基于文件类型的友好名称（v1.0.38: 不再使用时间戳数字ID）
    val friendlyExt = if (ext.isNotBlank()) ".$ext" else ""
    val typeLabel = when (ext.lowercase()) {
        "pdf" -> "PDF文档"
        "doc", "docx" -> "Word文档"
        "xls", "xlsx" -> "Excel表格"
        "ppt", "pptx" -> "PPT演示"
        "txt" -> "文本文件"
        "png", "jpg", "jpeg", "bmp", "gif", "webp" -> "图片"
        "zip", "rar", "7z" -> "压缩包"
        "dwg", "dxf" -> "CAD图纸"
        else -> "文件"
    }
    val friendly = "$typeLabel${if (cachedFileCounter > 0) "_$cachedFileCounter" else ""}$friendlyExt"
    cachedFileCounter++
    Log.w("WordCount", "resolveDisplayName 全部策略失败 → 兜底: '$friendly' (uri=$uri)")
    return friendly
}

/** v1.0.38: 兜底命名计数器（避免同名冲突） */
private var cachedFileCounter = 0
/** v1.1.13: 安全网替换名计数器（确保每个被替换的文件有唯一序号） */
private var friendlyNameCounter = 0

/**
 * v1.0.38 新增：从原始 PDF 字节中提取 /Title 元数据。
 * 用轻量正则扫描，不依赖完整 PDF 解析库。
 * 支持 PDF 字符串格式：(literal text) 和 <hex-encoded>。
 * @return 标题文本；未找到或解析失败返回 null
 */
private fun extractPdfTitleFromBytes(data: ByteArray, length: Int): String? {
    try {
        val str = String(data, 0, length, Charsets.ISO_8859_1)
        // 匹配 /Title 后跟字符串值：(text) 或 <hex>
        // PDF 规范允许 /Title 出现在 Info dict 中，可能在文件后半部分（trailer 附近）
        val patterns = listOf(
            Regex("""/Title\s*\(([^)]*)\)"""),           // (literal text)
            Regex("""/Title\s*<([0-9a-fA-F]+)>""")       // <hex encoded>
        )
        for (pattern in patterns) {
            val match = pattern.find(str) ?: continue
            val value = match.groupValues[1]
            if (value.isEmpty()) continue
            // 判断是 hex 还是 literal
            if (match.value.contains('<') && match.value.contains('>')) {
                // Hex 编码：两字节一组 → UTF-8 或 Latin-1 解码
                return try {
                    val bytes = ByteArray(value.length / 2)
                    for (i in value.indices step 2) {
                        bytes[i / 2] = value.substring(i, i + 2).toInt(16).toByte()
                    }
                    String(bytes, Charsets.UTF_8).also { decoded ->
                        // 验证解码结果不含控制字符
                        if (decoded.any { it.code < 32 && it != '\n' && it != '\r' && it != '\t' }) null
                        else decoded.trim()
                    }
                } catch (_: Throwable) { null }
            } else {
                // Literal string：可能有 PDF 转义序列 \n \t \( \) \\
                val unescaped = value
                    .replace("\\n", "\n").replace("\\t", "\t")
                    .replace("\\(", "(").replace("\\)", ")")
                    .replace("\\\\", "\\")
                return unescaped.trim().takeIf { it.isNotEmpty() }
            }
        }
    } catch (_: Throwable) {}
    return null
}

/** v1.1.10: 独立 hash 检测函数（copyUriToCache 安全网用，不依赖 resolveDisplayName 内嵌版本）
 *  与 resolveDisplayName 内的 looksLikeHash 保持同步放宽判定。
 */
private fun looksLikeHashString(s: String): Boolean {
    val t = s.trim()
    if (t.length < 8) return false
    // 纯 hex（>=16字符）
    if (t.matches(Regex("^[a-fA-F0-9]{16,}$"))) return true
    // UUID 格式
    if (t.matches(Regex("^[a-fA-F0-9]{8}-([a-fA-F0-9]{4}-){3}[a-fA-F0-9]{12}$"))) return true
    // wc_/file_ 前缀
    if (t.matches(Regex("^(wc_|file_)[0-9a-f]{10,}"))) return true
    // 带扩展名的 hash
    val dotIdx = t.lastIndexOf('.')
    if (dotIdx > 0 && dotIdx < t.length - 1 && dotIdx <= 64) {
        val base = t.substring(0, dotIdx)
        val ext = t.substring(dotIdx + 1)
        if (ext.lowercase() in setOf("pdf","doc","docx","xls","xlsx","ppt","pptx","txt","png","jpg","jpeg","bmp","gif","webp")
            && base.length >= 10 && !base.any { it.code in 0x4E00..0x9FFF }
            && base.count { it.isLetterOrDigit() } > base.length * 0.85) {
            val hasVowel = base.any { it.lowercaseChar() in 'a'..'z' && it.lowercaseChar() in setOf('a','e','i','o','u') }
            val isPureHex = base.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
            val isMostlyDigits = base.count { it.isDigit() } > base.length * 0.6
            val hasCjkPunct = base.any { it in setOf('(', ')', '（', '）', '[', ']', '【', '】', '_', '-', '#', '@') }
            // v1.1.11: 纯 hex ≥16 即 hash；非纯 hex 编号文件名才需 >36
            if (isPureHex && base.length >= 16 && !hasCjkPunct) return true
            if (!isPureHex && !hasVowel && isMostlyDigits && !hasCjkPunct && base.length > 36) return true
        }
    }
    // 长数字字母混合无中文
    if (t.length > 20 && !t.contains(".") && !t.any { it.code in 0x4E00..0x9FFF }
        && t.count { it.isLetterOrDigit() } > t.length * 0.9) return true
    return false
}

/**
 * v1.1.12: 检测"可疑文件名"——不像人类命名的任何长字符串。
 * 作为 looksLikeHashString 的补充，捕获那些不完全匹配 hex/UUID 格式
 * 但明显是系统内部 ID（如 ContentResolver 返回的编码标识符）。
 *
 * 规则：
 * 1. 名字较长(>12)且无 CJK 字符
 * 2. 去扩展名后，包含连续 8+ 个十六进制字符的序列（hash 特征片段）
 * 3. 或者 basename 看起来像随机字母数字混合（高熵特征）
 */
private fun isSuspiciousFilename(s: String): Boolean {
    val t = s.trim()
    if (t.length < 8) return false
    // 有 CJK 字符 → 很可能是真实文件名
    if (t.any { it.code in 0x4E00..0x9FFF }) return false

    // 分离 basename 和扩展名
    val dotIdx = t.lastIndexOf('.')
    val base = if (dotIdx > 0 && dotIdx < t.length - 1) t.substring(0, dotIdx) else t

    // 规则1: basename 中有连续 12+ 个 hex 字符的子串 → 几乎可以确定是某种编码 ID
    if (base.contains(Regex("[a-fA-F0-9]{12,}"))) return true

    // 规则2: basename 以 "9e" 或类似 hex 前缀开头 + 长度>=16 + 无语义分隔符
    // （某些 ROM 的 ContentResolver 内部 ID 固定以 9e 开头）
    if (base.length >= 16 && Regex("^[a-fA-F0-9]{2,}[a-fA-F0-9]+").matches(base)) return true

    // 规则3: 超长basename(>24) + 字母数字占比>90% + 不含元音(或极少) + 无空格/常见标点
    if (base.length > 24) {
        val alnumRatio = base.count { it.isLetterOrDigit() }.toFloat() / base.length
        val vowelCount = base.count { it.lowercaseChar() in setOf('a','e','i','o','u') }
        val letterCount = base.count { it.isLetter() }
        val vowelRatio = if (letterCount > 0) vowelCount.toFloat() / letterCount else 1f
        // 正常英文文件名元音比例约 30-60%；hash/ID 通常 < 10%
        if (alnumRatio > 0.85f && vowelRatio < 0.15f && letterCount >= 8) return true
    }

    return false
}

/**
 * v1.1.26: 检测编号模式或通用名——这类名称不包含有意义的文件标识信息。
 *
 * 覆盖范围：
 *   - 编号模式："1-1"、"1-(1)"、"图1"、"Sheet1"、"Document (2)" 等
 *   - 短编号 + 扩展名："1.docx"、"2.pdf"、"a.txt" 等
 *   - 通用名前缀："Word文档"、"PDF文档" 等
 */
private fun isNumberedOrGenericName(s: String): Boolean {
    val t = s.trim()
    if (t.isEmpty()) return true

    // 已有 CJK 且长度>4 → 大概率是有意义的中文文件名，不拦截
    if (t.any { it.code in 0x4E00..0x9FFF } && t.length > 4) return false

    // 分离 basename 和扩展名
    val dotIdx = t.lastIndexOf('.')
    val base = if (dotIdx > 0 && dotIdx < t.length - 1) t.substring(0, dotIdx) else t

    // 通用名前缀（安全网生成的）
    if (base.startsWith("Word文档") || base.startsWith("PDF文档") ||
        base.startsWith("Excel表格") || base.startsWith("PPT演示") ||
        base.startsWith("文本文件") || base.startsWith("图片") ||
        base.startsWith("文档") || base.startsWith("压缩包") ||
        base.startsWith("CAD图纸")) return true

    // 编号模式1: "数字-数字" 如 "1-1"、"2-3"，可选尾括号如 "1-1)"
    if (base.matches(Regex("^\\d+[)-]\\d+\\)?$"))) return true

    // 编号模式1b: "数字-数字)(数字)" 如 "1-1)(1)"
    if (base.matches(Regex("^\\d+[)-]\\d+\\)\\(\\d+\\)$"))) return true

    // 编号模式3: 纯短编号（1~3个字符）如 "1"、"2"、"a"
    if (base.length <= 3 && base.all { it.isLetterOrDigit() }) return true

    // 编号模式4: "单词+数字" 的短模式如 "Sheet1"、"Doc2"、"图1"
    if (base.matches(Regex("^(Sheet|Doc|Document|File|Image|Pic|图|档|表|页)\\d*$", RegexOption.IGNORE_CASE))) return true

    // 编号模式5: "数字)" 或 "(数字)" 或纯数字
    // 拆分为多个简单条件避免复杂正则转义问题
    if (Regex("""^\d+\)$""").matches(base)) return true       // "123)"
    if (Regex("""^\(\d+\)?$""").matches(base)) return true     // "(123)" 或 "(123"
    if (base.all { it.isDigit() } && base.isNotEmpty()) return true  // 纯数字

    // 编号模式6: "No." / "NO." 开头后跟纯数字/短文本
    if (Regex("""^(?i)(no\.?|number#?)\s*\d*$""").matches(base)) return true

    return false
}

private fun copyUriToCache(context: android.content.Context, uri: Uri): CachedFile {
    val originalName = resolveDisplayName(context, uri)
    Log.d("WordCount", "copyUriToCache originalName='$originalName'")

    // v1.1.38 放宽检测：确保各种无意义文件名都走内部标题提取。
    // 检测范围：
    //   A. hash/UUID/内部ID（looksLikeHashString / isSuspiciousFilename）
    //   B. 编号模式："1-1"、"1-(1)"、"图1"、"Sheet1"、"1-1)." 等
    //   C. 通用名前缀（"Word文档"/"PDF文档" 等）
    //   D. 短名字（basename <= 8 字符，v1.1.38: 5→8，捕获更多编号模式）
    val baseName = originalName.substringBeforeLast('.').ifBlank { originalName }
    val isShortOrGeneric = baseName.length <= 8  // v1.1.38: 5→8，更宽松捕获编号模式
            || looksLikeHashString(originalName)
            || isSuspiciousFilename(originalName)
            || isNumberedOrGenericName(originalName)
            // v1.1.38 新增：明确捕获 "数字-数字)" 等带括号的短编号
            || Regex("""^\d+[)-]\d+\)?$""").matches(baseName)
            // v1.1.38 新增：纯 ASCII 短名且无元音（像编号/代码）
            || (baseName.length <= 10 && baseName.all { it.code in 0..127 } && !baseName.any { it.lowercaseChar() in 'a'..'z' && it.lowercaseChar() in setOf('a','e','i','o','u') })

    val displayName = if (isShortOrGeneric) {
        val ext = guessExt(context, uri)
        val typeLabel = when (ext.lowercase()) {
            "pdf" -> "PDF文档"
            "doc", "docx" -> "Word文档"
            "xls", "xlsx" -> "Excel表格"
            "ppt", "pptx" -> "PPT演示"
            "txt" -> "文本文件"
            "png", "jpg", "jpeg", "bmp", "gif", "webp" -> "图片"
            else -> "文档"
        }
        val safeExt = if (ext.isNotBlank()) ".$ext" else ""
        // v1.1.50: 用文件路径hash生成短后缀（4位hex），确保同名文件可区分
        val shortHash = absoluteHashCode(originalName).toString(16).takeLast(4).uppercase()
        val result = "${typeLabel}_${shortHash}${safeExt}"
        Log.w("WordCount", "copyUriToCache 安全网触发: '$originalName' → '$result' (baseName='$baseName' len=${baseName.length})")
        result
    } else {
        originalName
    }

    val safe = displayName.replace(Regex("[^\\w.\\-]"), "_")
    // 保留原始扩展名用于路由判断；如果 filename 没有扩展名则从 MIME type 推断后缀
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
    // v1.0.16 兜底：若缓存文件仍无有效扩展名，用 magic bytes 检测并重命名
    if (out.extension.isBlank() || out.extension.length > 6) {
        val magicExt = detectExtFromMagicBytes(out)
        if (magicExt.isNotBlank()) {
            val renamed = File(out.parentFile, "${out.name}.$magicExt")
            if (out.renameTo(renamed)) return CachedFile(renamed, displayName)
        }
    }

    // v1.1.13: 如果 displayName 是通用名（安全网替换的），尝试从文件内部元数据获取真实标题
    val finalDisplayName = tryExtractInternalTitle(out, displayName)

    return CachedFile(out, finalDisplayName)
}

/**
 * v1.1.26: 从已缓存的文件中提取有意义的显示名称，用于替换非理想名称。
 * 按优先级尝试：dc:title > PDF /Title > DOCX内容首行 > 原名
 *
 * 触发条件（v1.1.26 放宽）：不仅限于通用名前缀，
 * 对任何编号模式/无意义名称都尝试内部标题提取。
 */
private fun tryExtractInternalTitle(file: File, currentName: String): String {
    // 只在当前名称是非理想名称时才尝试（避免覆盖真实有意义的文件名）
    val isGenericName = currentName.startsWith("Word文档") ||
        currentName.startsWith("PDF文档") || currentName.startsWith("Excel表格") ||
        currentName.startsWith("PPT演示") || currentName.startsWith("文本文件") ||
        currentName.startsWith("图片") || currentName.startsWith("文档") ||
        currentName.startsWith("压缩包") || currentName.startsWith("CAD图纸")
    // v1.1.26 扩展：编号模式名称也需要尝试提取
    val isNumbered = isNumberedOrGenericName(currentName)
    if (!isGenericName && !isNumbered) return currentName

    val ext = file.extension.lowercase()
    return try {
        if (ext == "docx") {
            // 策略1: docProps/core.xml <dc:title>
            val zip = java.util.zip.ZipFile(file)
            try {
                val entry = zip.getEntry("docProps/core.xml")
                if (entry != null) {
                    val xml = zip.getInputStream(entry).bufferedReader().readText()
                    val titleRe = """<dc:title[^>]*>(.*?)</dc:title>""".toRegex()
                    val title = titleRe.find(xml)?.groupValues?.get(1)?.trim()
                    if (!title.isNullOrBlank() && title.length <= 150 &&
                        !title.any { it.code < 0x20 }) {
                        val clean = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
                        Log.d("WordCount", "DOCX内部标题: '$clean'")
                        return "$clean.docx"
                    }
                }
                // 策略2: DOCX 内容中有意义的文本（扫描所有 w:t，找第一个像标题的）
                // v1.1.19 修复：两轮扫描——优先选CJK标题(第一轮)，跳过编号/代码/标签(第二轮)
                val bodyEntry = zip.getEntry("word/document.xml")
                if (bodyEntry != null) {
                    val bodyXml = zip.getInputStream(bodyEntry).bufferedReader().readText()
                    val tRe = """<w:t[^>]*>(.*?)</w:t>""".toRegex(RegexOption.DOT_MATCHES_ALL)
                    // 常见英文标签模式（跳过这些）
                    val labelPattern = Regex("""^(?i)(no|to|from|date|name|subject|cc|bcc|ref|re|page|tel|fax|email|address|id|code|type|copy|total|amount|note|dear|sir|mr|ms|mrs|dr|prof)\s*[:.]?\s*$""")
                    val labelPattern2 = Regex("""^[A-Z][a-z]?[\.:]\s*$""")  // 单/双字母+冒号/点
                    // 编号/代码/ID模式（统一社会信用代码、身份证号、注册号等）
                    val codePattern = Regex("""^[A-Za-z]*[0-9]{6,}.*$""")   // 含6位以上连续数字
                    val codePattern2 = Regex("""^\w{10,}[\)\]】]?$""")       // 长字母数字串+可选右括号
                    var cjkCandidate: String? = null
                    var fallbackCandidate: String? = null
                    tRe.findAll(bodyXml).forEach { tMatch ->
                        val raw = tMatch.groupValues?.get(1)?.trim()
                            ?.replace(Regex("<[^>]+>"), "")?.trim() ?: return@forEach
                        if (raw.isBlank() || raw.length < 2) return@forEach
                        // 跳过纯英文标签
                        if (labelPattern.matches(raw) || labelPattern2.matches(raw)) return@forEach
                        // 跳过编号/代码类文本（身份证号、统一社会信用代码等）
                        if (codePattern.matches(raw) || codePattern2.matches(raw)) return@forEach
                        // 跳过纯数字或以数字开头的长串（页码、日期数字等）
                        if (raw.matches(Regex("""^\d{2,}.*$""")) && raw.length <= 20) return@forEach
                        // 含 CJK → 最佳候选（大概率是中文标题），继续扫描看有没有更长的
                        if (raw.any { it.code in 0x4E00..0x9FFF || it.code in 0x3400..0x4DBF ||
                                             it.code in 0xA960..0xA97C || it.code in 0xF900..0xFAFF ||
                                             it.code in 0x3000..0x303F }) {
                            val short = if (raw.length > 25) raw.substring(0, 25) + "…" else raw
                            val clean = short.replace(Regex("[\\\\/:*?\"<>|\n\r\t]"), "_").trim()
                            if (clean.isNotBlank()) {
                                val prevLen = cjkCandidate?.length ?: 0
                                if (raw.length > prevLen) {
                                    cjkCandidate = clean
                                }
                            }
                        } else if (fallbackCandidate == null) {
                            // 纯英文但够长（>=4字符且不含标点结尾的标签式写法）→ 兜底候选
                            if (raw.length >= 4 && !raw.endsWith(":") && !raw.endsWith(".")) {
                                val short = if (raw.length > 25) raw.substring(0, 25) + "…" else raw
                                val clean = short.replace(Regex("[\\\\/:*?\"<>|\n\r\t]"), "_").trim()
                                if (clean.isNotBlank()) fallbackCandidate = clean
                            }
                        }
                    }
                    // 优先用 CJK 标题，其次兜底
                    val chosen = cjkCandidate ?: fallbackCandidate
                    if (chosen != null) {
                        Log.d("WordCount", "DOCX有意义的标题文本: '$chosen' (CJK=${
                            cjkCandidate != null}, fallback=${fallbackCandidate != null})")
                        return "$chosen.docx"
                    }
                    Log.d("WordCount", "DOCX未找到有意义的标题文本，保留通用名")
                }
            } finally { zip.close() }
        }

        if (ext == "pdf") {
            // 策略3: PDF /Title 元数据
            val bytes = file.inputStream().use { ins ->
                val buf = ByteArray(minOf(65536, file.length().toInt()))
                val total = ins.read(buf)
                if (total == buf.size) buf else buf.copyOf(total)
            }
            extractPdfTitleFromBytes(bytes, bytes.size)?.let { title ->
                val clean = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
                if (clean.isNotBlank()) return "$clean.pdf"
            }
        }

        // 策略4: TXT 首行
        if (ext == "txt" || ext == "csv" || ext == "log") {
            file.inputStream().use { ins ->
                val header = ByteArray(minOf(4096, file.length().toInt()))
                val n = ins.read(header)
                if (n > 0) {
                    val firstLine = String(header, 0, n, Charsets.UTF_8)
                        .lineSequence().firstOrNull { it.isNotBlank() }?.trim()
                    if (!firstLine.isNullOrBlank() && firstLine.length >= 2) {
                        val short = if (firstLine.length > 25) firstLine.substring(0, 25) + "…" else firstLine
                        val clean = short.replace(Regex("[\\\\/:*?\"<>|\n\r\t]"), "_").trim()
                        if (clean.isNotBlank()) return "$clean.$ext"
                    }
                }
            }
        }

        currentName
    } catch (_: Throwable) {
        currentName
    }
    // v1.1.35: 如果提取后仍然是通用名（如 "PDF文档.pdf"），则生成带序号的友好名
    // 避免多个文件都显示 "PDF文档.pdf" 无法区分
    if (currentName.startsWith("PDF文档") || currentName.startsWith("Word文档") ||
        currentName.startsWith("Excel表格") || currentName.startsWith("PPT演示") ||
        currentName.startsWith("文本文件") || currentName.startsWith("图片") ||
        currentName.startsWith("文档") || currentName.startsWith("压缩包")) {
        // 用文件大小+修改时间的哈希生成短序号，保证同一文件始终同名、不同文件不同名
        val sig = (file.length() xor (file.lastModified() / 1000L)).toInt()
        val shortId = if (sig < 0) -sig else sig
        val base = currentName.substringBeforeLast('.')
        val e = currentName.substringAfterLast('.', "")
        val suffix = if (e.isNotBlank()) ".$e" else ""
        return "${base}_${shortId.toString(36).take(4)}$suffix"
    }
    return currentName
}

/**
 * v1.0.35 新增：将 PDF 每页渲染为 PNG 图片文件。
 * 用途：当 ML Kit OCR 不可用时，把 PDF 页面交给 Python RapidOCR（图片 OCR）识别。
 *
 * 使用 Android 系统 PdfRenderer（minSdk 26 已满足，无需额外依赖），
 * 与 PdfOcrEngine 共用同一渲染机制，区别在于输出到文件而非 Bitmap→MLKit。
 *
 * @return 渲染出的 PNG 文件列表（可能为空）；调用方负责事后清理临时文件
 */
private fun renderPdfPagesToPngs(pdfFile: File): List<File> {
    val pngs = mutableListOf<File>()
    val pfd = try {
        ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
    } catch (e: Throwable) {
        Log.w("WordCount", "renderPdfPagesToPngs 打开失败: ${e.message}")
        return emptyList()
    }
    val renderer = try {
        PdfRenderer(pfd)
    } catch (e: Throwable) {
        Log.w("WordCount", "renderPdfPagesToPngs 创建 Renderer 失败: ${e.message}")
        runCatching { pfd.close() }
        return emptyList()
    }
    try {
        val pageCount = renderer.pageCount
        val limit = minOf(pageCount, 40) // 最多 40 页，避免 OOM
        val tmpDir = pdfFile.parentFile ?: File(System.getProperty("java.io.tmpdir"))
        for (i in 0 until limit) {
            val page = try { renderer.openPage(i) } catch (_: Throwable) { continue }
            try {
                val w = page.width; val h = page.height
                if (w <= 0 || h <= 0) continue
                // 2.0x 缩放（与 Python 端 fitz.Matrix(2.5,2.5) 接近，兼顾速度和识别率）
                val scale = minOf(2048f / maxOf(w, h), 2f).coerceAtLeast(1f)
                val bw = maxOf(1, (w * scale).toInt())
                val bh = maxOf(1, (h * scale).toInt())
                val bmp = try { Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888) } catch (_: Throwable) { continue }
                try {
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val pngFile = File(tmpDir, "pdf_ocr_${pdfFile.nameWithoutExtension}_p${i + 1}.png")
                    FileOutputStream(pngFile).use { out ->
                        bmp.compress(Bitmap.CompressFormat.PNG, 90, out)
                    }
                    pngs.add(pngFile)
                } finally {
                    bmp.recycle()
                }
            } finally {
                page.close()
            }
        }
        Log.d("WordCount", "renderPdfPagesToPngs: ${pdfFile.name} → ${pngs.size}/$limit 页")
    } catch (e: Throwable) {
        Log.w("WordCount", "renderPdfPagesToPngs 异常: ${e.message}")
    } finally {
        runCatching { renderer.close() }
        runCatching { pfd.close() }
    }
    return pngs
}

/** 用 magic bytes 检测文件真实格式（用于 content URI 无扩展名时兜底）。 */
private fun detectExtFromMagicBytes(file: File): String {
    return try {
        val header = file.inputStream().use { it.readNBytes(8) }
        when {
            header.size >= 4 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
                    && header[2] == 0x03.toByte() && header[3] == 0x04.toByte() -> "zip"
            header.size >= 6 && header[0] == 0x52.toByte() && header[1] == 0x61.toByte()
                    && header[2] == 0x72.toByte() && header[3] == 0x21.toByte() -> "rar"
            header.size >= 2 && (header[0].toInt() and 0xFF) == 0x1F && (header[1].toInt() and 0xFF) == 0x8B -> "gz"
            header.size >= 4 && header[0] == 0x25.toByte() && header[1] == 0x50.toByte()
                    && header[2] == 0x44.toByte() && header[3] == 0x46.toByte() -> "pdf"
            header.size >= 8 && header[0] == 0x37.toByte() && header[1] == 0x7A.toByte()
                    && header[2] == 0xBC.toByte() && header[3] == 0xAF.toByte() -> "7z"
            else -> ""
        }
    } catch (_: Throwable) { "" }
}

private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "bmp", "tif", "tiff", "gif", "webp")
private val OLD_OFFICE_EXTS = setOf("doc", "xls", "ppt")
private val OOXML_EXTS = setOf("docx", "xlsx", "pptx")
private val PDF_EXTS = setOf("pdf")
private val DWG_EXTS = setOf("dwg")
private val ARCHIVE_EXTS = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz")
private val TXT_EXTS = setOf("txt")

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
        scope.launch(Dispatchers.Main) {
            busySet(true)
            cachedFileCounter = 0  // 重置兜底命名计数器
            try {
                val pyStartResult = runCatching { PythonEngine.start(context) }
                Log.d("WordCount", "PythonEngine.start: ${if (pyStartResult.isSuccess) "OK" else "FAIL: ${pyStartResult.exceptionOrNull()?.message}"}")
                val cachedFiles = uris.map { copyUriToCache(context, it) }
                val files = cachedFiles.map { it.file }
            val imageFiles = mutableListOf<CachedFile>()
            val oldOfficeFiles = mutableListOf<CachedFile>()
            val ooxmlFiles = mutableListOf<CachedFile>()       // v1.0.15: OoXmlEngine
            val pdfFiles = mutableListOf<CachedFile>()          // v1.0.15: PdfExtractor
            val dwgFiles = mutableListOf<CachedFile>()
            val archiveFiles = mutableListOf<CachedFile>()     // v1.0.15: ArchiveEngine
            val txtFiles = mutableListOf<CachedFile>()
            for (cf in cachedFiles) {
                val f = cf.file
                val ext = f.extension.lowercase().removePrefix(".")
                when {
                    ext in IMAGE_EXTS -> imageFiles.add(cf)
                    ext in OLD_OFFICE_EXTS -> oldOfficeFiles.add(cf)
                    ext in OOXML_EXTS -> ooxmlFiles.add(cf)
                    ext in PDF_EXTS -> pdfFiles.add(cf)
                    ext in DWG_EXTS -> dwgFiles.add(cf)
                    ext in ARCHIVE_EXTS -> archiveFiles.add(cf)
                    ext in TXT_EXTS || ext.isBlank() -> txtFiles.add(cf)
                    else -> txtFiles.add(cf)
                }
            }

            withContext(Dispatchers.IO) {
                // ════════════════════════════════════════
                // 全部纯 Java/Kotlin，不依赖 Chaquopy/Python
                // ════════════════════════════════════════

                // 压缩包 → 纯 Kotlin 递归统计内部文件（ZIP/GZ/TGZ/TAR）；rar/7z 提示不支持
                archiveFiles.forEachIndexed { i, cf ->
                    val f = cf.file
                    val dName = cf.displayName
                    try {
                        val res = ArchiveEngine.extract(f, context.cacheDir)
                        if (res == null) {
                            val ext = f.extension.lowercase()
                            val isSupported = ext in setOf("zip", "rar", "7z", "tar", "gz", "tgz")
                            val errMsg = if (isSupported) {
                            if (ext == "rar")
                                "RAR 格式不支持（仅支持 RAR4，RAR5 需先用电脑转为 ZIP）"
                            else
                                "压缩包解析失败（文件可能损坏或密码保护）"
                        } else
                            "暂不支持此格式（.$ext）。支持：ZIP / RAR4 / 7Z / TAR / GZ"
                            entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_arch", displayName = dName, cachePath = f.absolutePath,
                                error = errMsg))
                        } else {
                            val resMap = mapOf(
                                "name" to f.name, "ext" to ".${f.extension.lowercase()}",
                                "stats" to mapOf("words" to res.words, "fe" to res.fe, "nc" to res.nc, "chars" to res.chars),
                                "meta" to mapOf("inner" to res.inner.map { innerToMeta(it) }),
                                "is_archive" to true,
                                "pages" to res.inner.sumOf { it.pages ?: estimatePages(it.chars) }
                            )
                            val fr = toFileResult(resMap, f.absolutePath)
                            entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_arch", displayName = dName, cachePath = f.absolutePath, result = fr, rawResult = resMap))
                        }
                    } catch (e: Throwable) {
                        Log.w("WordCount", "压缩包解析失败 ${f.name}: ${e.message}")
                        entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_arch", displayName = dName, cachePath = f.absolutePath, error = "压缩包解析失败（${e.message}）"))
                    }
                }

                // OOXML (docx/xlsx/pptx) → 纯 Kotlin 解析（不再经过 Python，规避设备端 Chaquopy 失败）
                ooxmlFiles.forEachIndexed { i, cf ->
                    val f = cf.file
                    val dName = cf.displayName
                    try {
                        val res = OoXmlEngine.extract(f)
                        if (res == null) {
                            entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_oo", displayName = dName, cachePath = f.absolutePath, error = "无法解析此 OOXML 文件（可能损坏或非标准格式）"))
                        } else {
                            val stats = countTextKotlin(res.text)
                            val resMap = mapOf(
                                "name" to dName, "ext" to ".${f.extension.lowercase()}",
                                "stats" to mapOf("words" to stats.first, "fe" to stats.second, "nc" to stats.third, "chars" to stats.fourth),
                                "meta" to mapOf("sheets" to res.sheets),
                                "pages" to res.pages,
                                "pages_reason" to (if (res.pagesReason.isNotBlank()) res.pagesReason else null)
                            )
                            val fr = toFileResult(resMap, f.absolutePath)
                            entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_oo", displayName = dName, cachePath = f.absolutePath, result = fr, rawResult = resMap))
                        }
                    } catch (e: Throwable) {
                        Log.w("WordCount", "OOXML 解析失败 ${f.name}: ${e.message}")
                        entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_oo", displayName = dName, cachePath = f.absolutePath, error = "OOXML 解析失败（${e.message}）"))
                    }
                }

                // ═══════════════════ v1.0.41 重构：PDF 三级提取 + 智能优选 ═══════════════════
                //
                // 核心问题（v1.0.40 暴露）：
                //   • Kotlin PdfExtractor 对 ObjStm/现代 PDF 几乎完全失效（282KB的PDF只提取3字符）
                //   • PyMuPDF(fitz) 在 Chaqopy 下报 FileNotFoundError: AssetFinder/scripts，不可用
                //   · OCR 链路在文本提取失败后才触发，但 Python 后备排在 OCR 之后永远跑不到
                //
                // 新流程（三级提取 + 优选）：
                //   Level 1: PdfExtractor (Kotlin 原生，快速但弱) → 基线结果A
                //   Level 2: Python pdfminer (pdfminer.six，正确处理 ObjStm/ToUnicode/CMap) → 结果B
                //   Level 3: ML Kit OCR (PdfRenderer→Bitmap→识别 / PdfiumAndroid 后备) → 结果C
                //   最终: 选 chars 最多的有效结果；全失败则报错
                //
                pdfFiles.forEachIndexed { i, cf ->
                    val f = cf.file
                    val dName = cf.displayName
                    try {
                        // ── Level 1: Kotlin PdfExtractor（快速预筛）──
                        val ktRes = PdfExtractor.extract(f)
                        val ktStats = countTextKotlin(ktRes.text)
                        Log.d("WordCount", "PDF Level1(Kotlin) $dName: chars=${ktStats.fourth} words=${ktStats.first} reliable=${ktRes.reliable} pages=${ktRes.pages}")

                        // ── Level 2: Python pdfminer（文字型 PDF 的主力）──
                        var pyWords = 0; var pyFe = 0; var pyNc = 0; var pyChars = 0; var pyPages = 0
                        var pyOk = false
                        var pyError: String? = null
                        try {
                            val pyResults = PythonEngine.countFiles(context, listOf(f.absolutePath))
                            @Suppress("UNCHECKED_CAST")
                            val pyList = pyResults as? List<Map<String, Any?>>
                            Log.d("WordCount", "PDF Level2(Python) $dName: raw=$pyResults")
                            if (!pyList.isNullOrEmpty()) {
                                val py0 = pyList[0]
                                Log.d("WordCount", "PDF Level2 $dName: py0_ok=${py0["ok"]} keys=${py0.keys}")
                                if (py0["ok"] == true) {
                                    val pyData = py0["result"] as? Map<String, Any?>
                                    if (pyData != null) {
                                        val pyS = pyData["stats"] as? Map<String, Any?>
                                        pyWords = (pyS?.get("words") as? Number)?.toInt() ?: 0
                                        pyFe = (pyS?.get("fe") as? Number)?.toInt() ?: 0
                                        pyNc = (pyS?.get("nc") as? Number)?.toInt() ?: 0
                                        pyChars = (pyS?.get("chars") as? Number)?.toInt() ?: 0
                                        pyPages = (pyData["pages"] as? Number)?.toInt() ?: ktRes.pages
                                        pyOk = true
                                    } else {
                                        Log.w("WordCount", "PDF Level2 $dName: pyData为null, raw result=${py0["result"]}")
                                    }
                                } else {
                                    pyError = py0["error"]?.toString()
                                    Log.w("WordCount", "PDF Level2 $dName: Python返回ok=false, error=$pyError")
                                }
                            } else {
                                Log.w("WordCount", "PDF Level2 $dName: pyList为空或null")
                            }
                        } catch (e: Throwable) {
                            Log.w("WordCount", "PDF Python pdfminer 异常: $dName - ${e.javaClass.simpleName}: ${e.message}")
                        }

                        Log.d("WordCount", "PDF $dName → KT:${ktStats.fourth}ch PY:${pyChars}ch(pyOk=$pyOk) KT_rel=${ktRes.reliable}")

                        // ── 决策：选 Kolt 或 Python 的较好结果 ──
                        //   pdfminer 通常更准确（处理了 ToUnicode CMap / ObjStm 等）
                        //   但如果两者都很少字符，说明可能是图片型 PDF → 需要 OCR
                        val usePython = pyOk && pyChars > ktStats.fourth

                        val bestWords = if (usePython) pyWords else ktStats.first
                        val bestFe = if (usePython) pyFe else ktStats.second
                        val bestNc = if (usePython) pyNc else ktStats.third
                        val bestChars = if (usePython) pyChars else ktStats.fourth
                        val bestPages = if (usePython && pyPages > 0) pyPages else ktRes.pages
                        val bestTextReliable = if (usePython) true else ktRes.reliable

                        // 判定是否还需要尝试 OCR
                        val bestCjkRatio = if (bestChars > 0) bestFe.toDouble() / bestChars else 0.0
                        val hasControlChars = false // 已由 Python/Kotlin 内部处理
                        val looksLikeGarbage = bestChars > 200 && bestFe < 30 && bestCjkRatio < 0.15
                        val needOcr = bestChars < 10 || (!bestTextReliable && bestChars < 50) || looksLikeGarbage

                        if (!needOcr) {
                            // ★ 文本提取足够好 → 直接使用
                            val resMap = mapOf(
                                "name" to dName, "ext" to ".pdf",
                                "stats" to mapOf("words" to bestWords, "fe" to bestFe, "nc" to bestNc, "chars" to bestChars),
                                "meta" to emptyMap<String, Any?>(),
                                "pages" to bestPages
                            )
                            val fr = toFileResult(resMap, f.absolutePath)
                            entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_pdf_ok", displayName = dName, cachePath = f.absolutePath, result = fr, rawResult = resMap))
                        } else {
                            // ★ 文本太少 → 尝试 OCR
                            val ocrRes = PdfOcrEngine.extractText(context, f)

                            if (ocrRes != null) {
                                // OCR 成功
                                val ocrStats = countTextKotlin(ocrRes.text)
                                val resMap = mapOf(
                                    "name" to dName, "ext" to ".pdf",
                                    "stats" to mapOf("words" to ocrStats.first, "fe" to ocrStats.second, "nc" to ocrStats.third, "chars" to ocrStats.fourth),
                                    "meta" to emptyMap<String, Any?>(),
                                    "pages" to ocrRes.pages
                                )
                                val fr = toFileResult(resMap, f.absolutePath)
                                entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_pdf_ocr", displayName = dName, cachePath = f.absolutePath, result = fr, rawResult = resMap))
                            } else {
                                // 全部失败 → 显示最佳可用结果或错误
                                if (bestChars > 0) {
                                    // 有一些文本（虽然少）→ 降级使用
                                    Log.w("WordCount", "PDF 降级(文本少+OCR失败): $dName best=${bestChars}ch")
                                    val resMap = mapOf(
                                        "name" to dName, "ext" to ".pdf",
                                        "stats" to mapOf("words" to bestWords, "fe" to bestFe, "nc" to bestNc, "chars" to bestChars),
                                        "meta" to emptyMap<String, Any?>(),
                                        "pages" to bestPages
                                    )
                                    val fr = toFileResult(resMap, f.absolutePath)
                                    entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_pdf_fallback", displayName = dName, cachePath = f.absolutePath, result = fr, rawResult = resMap))
                                } else {
                                    // 完全没有文本 → 报错
                                    var pdfPageCount = if (bestPages > 1) bestPages else 1
                                    try {
                                        val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
                                        val renderer = PdfRenderer(pfd)
                                        pdfPageCount = renderer.pageCount
                                        renderer.close(); pfd.close()
                                    } catch (_: Throwable) {}
                                    val reason = PdfOcrEngine.lastFailReason
                                    val detail = PdfOcrEngine.lastFailDetail
                                    val errMsg = when (reason) {
                                        PdfOcrEngine.FailReason.OCR_DISABLED ->
                                            "此 PDF 为扫描件/图片型文件（$pdfPageCount 页），OCR 引擎未就绪。"
                                        PdfOcrEngine.FailReason.RENDER_FAILED,
                                        PdfOcrEngine.FailReason.PDFIUM_FAILED,
                                        PdfOcrEngine.FailReason.PDFIUM_UNAVAILABLE,
                                        PdfOcrEngine.FailReason.RENDER_BLANK,
                                        PdfOcrEngine.FailReason.PDFIUM_BLANK ->
                                            "此 PDF 为扫描件/图片型文件（$pdfPageCount 页），渲染引擎无法处理（可能为 JPEG2000/JBIG2 编码）。${if(detail.isNotBlank()) "($detail)" else ""}"
                                        PdfOcrEngine.FailReason.OCR_EMPTY,
                                        PdfOcrEngine.FailReason.NO_EMBEDDED_IMAGES ->
                                            "此 PDF 为扫描件/图片型文件（$pdfPageCount 页），OCR 未识别到有效文字。"
                                        PdfOcrEngine.FailReason.RENDER_PARTIAL ->
                                            "此 PDF 部分页面渲染异常（$pdfPageCount 页），OCR 结果不完整。"
                                        else -> "无法从该 PDF 提取文字（$pdfPageCount 页，可能为纯图片、加密或损坏文件）。${if(detail.isNotBlank()) "\n原因: $detail" else ""}"
                                    }
                                    entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_pdf_err", displayName = dName, cachePath = f.absolutePath, error = errMsg))
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        Log.w("WordCount", "PDF 解析失败 ${f.name}: ${e.message}")
                        entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_pdf", displayName = dName, cachePath = f.absolutePath, error = "PDF 解析失败（${e.message}）"))
                    }
                }

                // 老格式(.doc/.xls/.ppt)：POI scratchpad 抽文本 -> Kotlin 统计（不再经过 Python）
                oldOfficeFiles.forEachIndexed { i, cf ->
                    val f = cf.file
                    val dName = cf.displayName
                    try {
                        val extLower = f.extension.lowercase()
                        // v1.1.10: DOC 用 extractDocFull 获取完整文本+页数元数据
                        val text: String
                        var docPages: Int = 0  // 0 = 未知
                        if (extLower == "doc") {
                            val docRes = OldOfficeEngine.extractDocFull(f)
                            text = docRes.text
                            docPages = docRes.pages
                        } else {
                            text = OldOfficeEngine.extractText(f)
                        }
                        if (text.isBlank()) {
                            entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_o", displayName = dName, cachePath = f.absolutePath, error = "此老格式文件内容为空或无法读取"))
                        } else {
                            val stats = countTextKotlin(text)
                            val extDot = ".$extLower"
                            // 构造 pages：DOC 有元数据页数就用，否则留 null 让 toFileResult 走 estimatePages 兜底
                            val pagesValue = if (docPages > 0) docPages else null
                            val resMap = mutableMapOf<String, Any?>(
                                "name" to dName, "ext" to extDot,
                                "stats" to mapOf("words" to stats.first, "fe" to stats.second, "nc" to stats.third, "chars" to stats.fourth),
                                "meta" to emptyMap<String, Any?>()
                            )
                            if (pagesValue != null) {
                                resMap["pages"] = pagesValue
                                resMap["pages_reason"] = "doc_summary_info"
                            }
                            val fr = toFileResult(resMap.toMap(), f.absolutePath)
                            entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_o", displayName = dName, cachePath = f.absolutePath, result = fr, rawResult = resMap.toMap()))
                        }
                    } catch (e: Throwable) {
                        Log.w("WordCount", "老格式解析失败 ${f.name}: ${e.message}")
                        entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_o", displayName = dName, cachePath = f.absolutePath, error = "无法解析此老格式（${e.message}），建议另存为 .docx/.xlsx/.pptx"))
                    }
                }

                // DWG(CAD)：二进制扫描提取文字 -> Kotlin 统计（不再经过 Python）
                dwgFiles.forEachIndexed { i, cf ->
                    val f = cf.file
                    val dName = cf.displayName
                    try {
                        val text = DwgEngine.extractText(f)
                        if (text.isBlank()) {
                            entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_w", displayName = dName, cachePath = f.absolutePath, error = "DWG 文件未提取到文字（可能为纯图形/复杂编码），建议导出为 DXF 后统计"))
                        } else {
                            val stats = countTextKotlin(text)
                            val resMap = mapOf(
                                "name" to dName, "ext" to ".dwg",
                                "stats" to mapOf("words" to stats.first, "fe" to stats.second, "nc" to stats.third, "chars" to stats.fourth),
                                "meta" to emptyMap<String, Any?>(),
                                "pages" to 1
                            )
                            val fr = toFileResult(resMap, f.absolutePath)
                            entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_w", displayName = dName, cachePath = f.absolutePath, result = fr, rawResult = resMap))
                        }
                    } catch (e: Throwable) {
                        Log.w("WordCount", "DWG 解析失败 ${f.name}: ${e.message}")
                        entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_w", displayName = dName, cachePath = f.absolutePath, error = "DWG 解析失败（${e.message}）"))
                    }
                }

                // TXT 类：纯 Kotlin 处理
                txtFiles.forEachIndexed { i, cf ->
                    val f = cf.file
                    val dName = cf.displayName
                    try {
                        val text = f.readText(Charsets.UTF_8)
                        if (text.isBlank()) {
                            entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_t", displayName = dName, cachePath = f.absolutePath,
                                error = "文件内容为空"))
                        } else {
                            val stats = countTextKotlin(text)
                            val resMap = mapOf(
                                "name" to dName,
                                "ext" to ".txt",
                                "stats" to mapOf(
                                    "words" to stats.first,
                                    "fe" to stats.second,
                                    "nc" to stats.third,
                                    "chars" to stats.fourth
                                ),
                                "meta" to emptyMap<String, Any?>()
                            )
                            val fr = toFileResult(resMap, f.absolutePath)
                            entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_t", displayName = dName, cachePath = f.absolutePath, result = fr, rawResult = resMap))
                        }
                    } catch (e: Throwable) {
                        Log.w("WordCount", "TXT 读取失败 ${f.name}: ${e.javaClass.simpleName}: ${e.message}")
                        entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_t", displayName = dName, cachePath = f.absolutePath,
                            error = "读取失败（${e.message}）"))
                    }
                }
                // 图片类：OCR（v1.0.18 起使用 Google ML Kit，稳定不闪退）
                imageFiles.forEachIndexed { i, cf ->
                    val f = cf.file
                    val dName = cf.displayName
                    try {
                        val text = OcrEngine.recognize(context, f)
                        if (text.isBlank()) {
                            val err = if (OcrEngine.ocrFailed)
                                "图片识别失败（模型未就绪或设备不支持）"
                            else
                                "未识别到文字（纯图/手写/模糊不清）"
                            entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_i", displayName = dName, cachePath = f.absolutePath,
                                error = err))
                        } else {
                            val stats = countTextKotlin(text)
                            val resMap = mapOf(
                                "name" to dName, "ext" to ".img",
                                "stats" to mapOf("words" to stats.first, "fe" to stats.second, "nc" to stats.third, "chars" to stats.fourth),
                                "meta" to emptyMap<String, Any?>()
                            )
                            val fr = toFileResult(resMap, f.absolutePath)
                            entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_i", displayName = dName, cachePath = f.absolutePath, result = fr, rawResult = resMap))
                        }
                    } catch (e: OutOfMemoryError) {
                        Runtime.getRuntime().gc()
                        Log.w("WordCount", "图片过大 OOM ${f.name}")
                        entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_i", displayName = dName, cachePath = f.absolutePath, error = "图片过大，内存不足"))
                    } catch (e: Throwable) {
                        Log.w("WordCount", "OCR 失败 ${f.name}: ${e.javaClass.simpleName}: ${e.message}")
                        entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_i", displayName = dName, cachePath = f.absolutePath, error = "图片识别失败（${e.message}）"))
                    }
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

/** 文本类格式（无明确页概念）按字符量估算页数。
 *  v1.1.10 改进：中文文档平均 ~750 字符/页（适应大字号/大行距/表格多的场景）。
 *  纯英文文档约 ~1000 字符/页（Word 默认格式）。
 *  取保守值确保不会严重低估。
 */
fun estimatePages(chars: Int): Int = maxOf(1, (chars + 749) / 750)

/** 压缩包内层结果 → toFileResult 可回解析的 meta 结构。 */
private fun innerToMeta(r: InnerResult): Map<String, Any?> {
    return mapOf(
        "name" to r.name,
        "stats" to mapOf("words" to r.words, "fe" to r.fe, "nc" to r.nc, "chars" to r.chars),
        "meta" to mapOf("pages" to r.pages)
    )
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
    return FileResult(
        name = (m?.get("name") as? String) ?: "",
        ext = ext,
        isArchive = (m?.get("is_archive") as? Boolean) ?: false,
        words = (stats["words"] as? Number)?.toInt() ?: 0,
        fe = (stats["fe"] as? Number)?.toInt() ?: 0,
        nc = (stats["nc"] as? Number)?.toInt() ?: 0,
        chars = (stats["chars"] as? Number)?.toInt() ?: 0,
        pages = (m?.get("pages") as? Int) ?: estimatePages((stats["chars"] as? Number)?.toInt() ?: 0),
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

// ═══════════════════════════════════════════════════════════════════════════
// v1.1.1: 文档比较界面（仿 Word「审阅 → 比较」）
// ═══════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareScreen(
    context: android.content.Context,
    scope: CoroutineScope = rememberCoroutineScope(),
    snackbar: SnackbarHostState,
    availableFiles: List<FileEntry> = emptyList(),   // v1.1.2: 字数统计列表中的文件（供直接选用）
    modifier: Modifier = Modifier
) {
    var origCf by remember { mutableStateOf<CachedFile?>(null) }
    var revCf by remember { mutableStateOf<CachedFile?>(null) }
    var busy by remember { mutableStateOf(false) }
    var resultJson by remember { mutableStateOf<String?>(null) }
    var outPath by remember { mutableStateOf<String?>(null) }

    // 比较设置（对应 Word 比较对话框）
    var level by remember { mutableStateOf("word") }   // 'char' 字符级别 | 'word' 字词级别
    var optCase by remember { mutableStateOf(true) }   // 大小写更改
    var optWs by remember { mutableStateOf(false) }     // 空格
    var optTable by remember { mutableStateOf(true) }   // 表格
    var optHf by remember { mutableStateOf(true) }      // 页眉和页脚
    var optFn by remember { mutableStateOf(true) }      // 脚注和尾注
    var optTb by remember { mutableStateOf(true) }      // 文本框
    var optField by remember { mutableStateOf(true) }   // 域

    var pickSlot by remember { mutableStateOf(0) } // 0=原文档 1=修订文档
    var showListPicker by remember { mutableStateOf(0) } // 0=不显示 1=选原文档 2=选修订文档
    // v1.1.2: 从字数统计列表中过滤出 DOCX 文件供选用
    val docxFiles = remember(availableFiles) {
        availableFiles.filter { fe ->
            val p = fe.cachePath.lowercase()
            p.endsWith(".docx") || p.endsWith(".doc")
        }
    }
    val docPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val cf = copyUriToCache(context, uri)
                if (pickSlot == 0) origCf = cf else revCf = cf
            } catch (e: Throwable) {
                scope.launch { snackbar.showSnackbar("选择文件失败：${e.message}") }
            }
        }
    }
    fun pick(slot: Int) {
        pickSlot = slot
        docPicker.launch(arrayOf(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/msword"
        ))
    }

    fun doCompare() {
        val o = origCf ?: return
        val r = revCf ?: return
        busy = true
        resultJson = null
        outPath = null
        scope.launch(Dispatchers.IO) {
            try {
                val out = File(context.cacheDir, "compare_result_${System.currentTimeMillis()}.docx")
                // v1.1.36: 使用纯 Kotlin 实现（DocxComparator），不再经过 Python 引擎
                // 原因：Chaquopy lxml C 扩展在 Android 上触发 fatal 级 AssetFinder 崩溃，
                //       历经 v1.1.15~v1.35 共 20 个版本验证，Python 层拦截全部无效。
                val cmpOpts = org.json.JSONObject().apply {
                    put("level", level)
                    put("case", optCase)
                    put("whitespace", optWs)
                    put("table", optTable)
                    put("header_footer", optHf)
                    put("footnote", optFn)
                    put("textbox", optTb)
                    put("field", optField)
                }.toString()
                val res = DocxComparator.compare(context, o.file.absolutePath, r.file.absolutePath, out.absolutePath, cmpOpts)
                withContext(Dispatchers.Main) {
                    if (res.ok) {
                        val j = org.json.JSONObject().apply {
                            put("ok", true)
                            put("outputPath", res.outputPath)
                            put("modifiedChars", res.modifiedChars)
                            put("insCount", res.insCount)
                            put("delCount", res.delCount)
                            put("repCount", res.repCount)
                            put("summary", res.summary)
                        }
                        resultJson = j.toString()
                        outPath = res.outputPath
                    } else {
                        snackbar.showSnackbar("比较失败：${res.error ?: "未知错误"}")
                    }
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    val detail = e.message ?: e.javaClass.simpleName
                    snackbar.showSnackbar("比较异常 [${e.javaClass.simpleName}]：${detail.take(300)}")
                }
            } finally {
                withContext(Dispatchers.Main) { busy = false }
            }
        }
    }

    val docxMime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 原文档
        CompareFileCard("原文档", origCf?.displayName, "选择原文档", docxFiles.isNotEmpty(),
            onPick = { pick(0) }, onPickFromList = { showListPicker = 1 })
        // 修订文档
        CompareFileCard("修订文档", revCf?.displayName, "选择修订文档", docxFiles.isNotEmpty(),
            onPick = { pick(1) }, onPickFromList = { showListPicker = 2 })

        // 比较设置
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("比较设置", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                // 修订显示级别
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("修订显示级别：", modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { level = "char" }, modifier = Modifier.padding(end = 6.dp),
                        enabled = level != "char") { Text("字符级别") }
                    OutlinedButton(onClick = { level = "word" }, enabled = level != "word") { Text("字词级别") }
                }
                HorizontalDivider()
                CompareCheck("大小写更改", optCase) { optCase = it }
                CompareCheck("空格", optWs) { optWs = it }
                CompareCheck("表格", optTable) { optTable = it }
                CompareCheck("页眉和页脚", optHf) { optHf = it }
                CompareCheck("脚注和尾注", optFn) { optFn = it }
                CompareCheck("文本框", optTb) { optTb = it }
                CompareCheck("域", optField) { optField = it }
            }
        }

        // 开始比较
        Button(
            onClick = { doCompare() },
            modifier = Modifier.fillMaxWidth(),
            enabled = origCf != null && revCf != null && !busy
        ) { Text(if (busy) "比较中…" else "开始比较") }

        if (busy) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }

        // 结果
        resultJson?.let { rj ->
            val j = org.json.JSONObject(rj)
            val modChars = j.optInt("modifiedChars", 0)
            val ins = j.optInt("insCount", 0)
            val del = j.optInt("delCount", 0)
            val rep = j.optInt("repCount", 0)
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("比较完成", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text("修改涉及的句子总字数：$modChars 字", fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.titleLarge)
                    Text("插入 $ins 处 ｜ 删除 $del 处 ｜ 修改 $rep 处", color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            outPath?.let { openDocxFile(context, it, docxMime) }
                        }, modifier = Modifier.weight(1f)) { Text("打开结果") }
                        OutlinedButton(onClick = {
                            outPath?.let { shareDocxFile(context, it, docxMime) }
                        }, modifier = Modifier.weight(1f)) { Text("分享") }
                    }
                }
            }
        }
    }

    // v1.1.2: 从字数统计列表选择文档的对话框
    if (showListPicker > 0 && docxFiles.isNotEmpty()) {
        val slotLabel = if (showListPicker == 1) "原文档" else "修订文档"
        AlertDialog(
            onDismissRequest = { showListPicker = 0 },
            title = { Text("从字数统计列表选择$slotLabel") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                    items(docxFiles) { fe ->
                        TextButton(
                            onClick = {
                                val cf = CachedFile(File(fe.cachePath), fe.displayName)
                                if (showListPicker == 1) origCf = cf else revCf = cf
                                showListPicker = 0
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(fe.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showListPicker = 0 }) { Text("取消") } }
        )
    }
}

@Composable
private fun CompareFileCard(label: String, name: String?, btnText: String,
    hasListFiles: Boolean = false, onPick: () -> Unit, onPickFromList: () -> Unit = {}) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                Spacer(Modifier.padding(2.dp))
                Text(name ?: "未选择", fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (hasListFiles) {
                    TextButton(onClick = onPickFromList, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text("从列表", fontSize = 12.sp)
                    }
                }
                OutlinedButton(onClick = onPick) { Text(btnText) }
            }
        }
    }
}

@Composable
private fun CompareCheck(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onToggle)
        Text(label, modifier = Modifier.padding(start = 4.dp))
    }
}

private fun openDocxFile(context: android.content.Context, path: String, mime: String) {
    try {
        val file = File(path)
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "打开比对结果"))
    } catch (e: Throwable) {
        Log.w("WordCount", "打开比对结果失败: ${e.message}")
    }
}

private fun shareDocxFile(context: android.content.Context, path: String, mime: String) {
    try {
        val file = File(path)
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享比对结果"))
    } catch (e: Throwable) {
        Log.w("WordCount", "分享比对结果失败: ${e.message}")
    }
}
