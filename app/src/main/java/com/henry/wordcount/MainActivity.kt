package com.henry.wordcount

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.material3.TextField
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
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
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import java.util.zip.ZipFile
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    /** 外部可通过此引用向已有列表追加新文件（onNewIntent 时使用） */
    companion object {
        @Volatile var pendingUris: List<Uri>? = null
        // v1.5.55: 微信等分享传入时，Intent EXTRA_SUBJECT 常携带原文件名，
        // 但 ContentResolver.DISPLAY_NAME 只返回内部缓存 ID。这里临时保存 hint。
        @Volatile var pendingUriNames: MutableMap<Uri, String> = mutableMapOf()
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
        val nameHints = mutableMapOf<Uri, String>()
        // 微信/QQ 等分享时，原文件名可能放在这些 extras 里
        val candidateNames = listOfNotNull(
            intent.getStringExtra(Intent.EXTRA_SUBJECT),
            intent.getStringExtra(Intent.EXTRA_TITLE),
            intent.getStringExtra("android.intent.extra.SUBJECT"),
            intent.getStringExtra("title"),
            intent.getStringExtra("_display_name"),
            intent.getStringExtra(Intent.EXTRA_TEXT)
        ).map { it.trim() }.filter { it.isNotBlank() }
        return mutableListOf<Uri>().apply {
            fun recordHint(uri: Uri) {
                if (candidateNames.isNotEmpty()) {
                    // 多个文件共享一个 SUBJECT 时，所有文件都用同一个 hint；
                    // 实际微信通常一次只发一个文件，问题不大。
                    nameHints[uri] = candidateNames.first()
                }
            }
            when (intent.action) {
                Intent.ACTION_SEND -> {
                    (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { recordHint(it); add(it) }
                }
                Intent.ACTION_SEND_MULTIPLE -> {
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.forEach { uri ->
                        recordHint(uri); add(uri)
                    }
                }
                Intent.ACTION_VIEW -> { intent.data?.let { add(it) } }
            }
        }.also {
            pendingUriNames = nameHints
        }
    }
}

data class InnerResult(
    val name: String,
    val words: Int, val fe: Int, val nc: Int, val chars: Int,
    val pages: Int?
)

/** 单个工作表统计（含隐藏表）：名称 + 字数 */
data class SheetStat(
    val name: String,
    val words: Int, val fe: Int, val nc: Int, val chars: Int
)

/** v1.5.61: CAD 文字拆分（文字部分 / 纯编号部分），供展开后分别勾选汇总 */
data class CadPartStats(
    val textWords: Int, val textFe: Int, val textNc: Int, val textChars: Int,
    val codeWords: Int, val codeFe: Int, val codeNc: Int, val codeChars: Int,
    val textItems: Int, val codeItems: Int
)

data class FileResult(
    val name: String,
    val ext: String,
    val isArchive: Boolean,
    val words: Int, val fe: Int, val nc: Int, val chars: Int,
    val pages: Int?,
    val pagesReason: String?,
    val sheets: List<String>,
    // v1.3.3: 隐藏工作表列表（默认不计入文件字数与合计，由 UI 勾选后才并入）
    val hiddenSheets: List<SheetStat> = emptyList(),
    // v1.3.32: PPT 备注幻灯片列表（默认不计入文件字数与合计，由 UI 勾选后才并入）
    val notesSlides: List<SheetStat> = emptyList(),
    // v1.3.32: PPT 嵌入图片数量
    val imageCount: Int = 0,
    // v1.3.32: 文件内部标题（docProps/core.xml <dc:title>），用于修复 URI 无法获取真实文件名的问题
    val internalTitle: String = "",
    val inner: List<InnerResult>,
    val hasUnreliable: Boolean,
    // v1.3.64: PDF 诊断信息（Python 是否工作、错误、决策过程），显示到界面便于排查
    val diag: String? = null,
    // v1.5.66: PDF 的 OCR 状态摘要（直接显示在主界面，无需展开诊断），便于真机排查
    //   - "文本充分，未触发OCR" | "已OCR扫描X页" | "⚠️ OCR未成功，已用文本层降级"
    val ocrNote: String? = null,
    // v1.5.36: DWG 统计不准、需用户选文字型 PDF 来重新统计时置 true（驱动 UI 提示与弹窗选 PDF）
    val needsPdf: Boolean = false,
    // v1.5.61: CAD 文字/纯编号拆分（仅 DWG 文件可能非空）
    val cadParts: CadPartStats? = null,
)

data class FileEntry(
    val id: String,
    val displayName: String,
    val cachePath: String,
    var selected: Boolean = true,
    val result: FileResult? = null,
    val error: String? = null,
    val rawResult: Map<*, *>? = null,
    // v1.5.56: 用户手动重命名的文件名（优先于自动 displayName 显示）
    val userRenamedName: String? = null,
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
    // v1.5.36: 用户为「统计不准」的 DWG 点选文字型 PDF 时，记录当前正在选 PDF 的条目 id
    var pdfPickEntryId by remember { mutableStateOf<String?>(null) }
    // v1.5.56: 用户手动重命名文件条目
    var renameEntry by remember { mutableStateOf<FileEntry?>(null) }
    var renameText by remember { mutableStateOf("") }
    // v1.1.1: 文档比较模式开关
    var compareMode by remember { mutableStateOf(false) }

    // SAF 文件选择器（不需要任何存储权限——OpenMultipleDocuments 在所有 Android 版本上均无需授权即可使用）
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) addFiles(context, scope, snackbar, entries, busyRef = { busy }, busySet = { busy = it }, uris)
    }

    // v1.5.36: 文字型 PDF 选择器（仅 PDF）。用于 DWG 统计不准时，让用户手动选一份同图文字型 PDF 重新统计。
    //   注：Android 走 SAF 读文件，原 document URI 已丢失、缓存目录无法像桌面那样 find_sibling_pdf，
    //   故不能自动找同目录 PDF，必须由用户在手机上手动选。
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val entryId = pdfPickEntryId
        if (uri != null && entryId != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val cf = copyUriToCache(context, uri)
                    val rec = recomputeFromPdf(context, cf.file, cf.displayName)
                    withContext(Dispatchers.Main) {
                        val idx = entries.indexOfFirst { it.id == entryId }
                        if (idx < 0) return@withContext
                        val entry = entries[idx]
                        val old = entry.result
                        if (rec == null || old == null) {
                            Toast.makeText(context, "该 PDF 未提取到文字，请选「文字型」PDF（非扫描件）", Toast.LENGTH_LONG).show()
                            return@withContext
                        }
                        // 用 PDF 统计结果覆盖 DWG 的字数/页数，关闭 needsPdf 提示
                        val newResult = old.copy(
                            words = rec.words,
                            fe = rec.fe,
                            nc = rec.nc,
                            chars = rec.chars,
                            pages = rec.pages ?: old.pages,
                            pagesReason = "来自文字型PDF",
                            diag = rec.diag,
                            needsPdf = false
                        )
                        entries[idx] = entry.copy(result = newResult)
                        Toast.makeText(context, "已用文字型PDF重新统计：${rec.chars}字", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Throwable) {
                    Log.w("WordCount", "选PDF重统计异常 ${e.message}")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "选PDF重统计失败：${e.message?.take(120)}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        pdfPickEntryId = null
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

    // v1.3.3: 隐藏工作表的勾选状态（key = "${entry.id}::${sheetName}"）
    val hiddenSelected = remember { mutableStateMapOf<String, Boolean>() }

    val totals = run {
        val sel = entries.filter { it.selected && it.result != null }
        var w = 0; var fe = 0; var nc = 0; var ch = 0; var pg = 0; var pendingPdf = 0
        sel.forEach { r ->
            // v1.5.37/v1.5.62: 需要 PDF 来统计的 DWG 仍把已拿到的字数计入合计（与电脑版一致）。
            // 只有完全没拿到字数时才只计页数。
            val hasStats = r.result!!.words > 0 || r.result!!.fe > 0 || r.result!!.nc > 0
            if (r.result!!.needsPdf && !hasStats) {
                pg += r.result!!.pages ?: estimatePages(r.result!!.chars)
                pendingPdf += 1
            } else {
                // v1.5.61/62: DWG 有文字/纯编号拆分时，按展开勾选状态计入合计
                val cp = r.result!!.cadParts
                if (cp != null) {
                    val textChecked = hiddenSelected["${r.id}::cad::text"] != false
                    val codeChecked = hiddenSelected["${r.id}::cad::code"] != false
                    when {
                        textChecked && codeChecked -> { w += r.result!!.words; fe += r.result!!.fe; nc += r.result!!.nc; ch += r.result!!.chars }
                        textChecked -> { w += cp.textWords; fe += cp.textFe; nc += cp.textNc; ch += cp.textChars }
                        codeChecked -> { w += cp.codeWords; fe += cp.codeFe; nc += cp.codeNc; ch += cp.codeChars }
                    }
                } else {
                    w += r.result!!.words; fe += r.result!!.fe; nc += r.result!!.nc; ch += r.result!!.chars
                }
                pg += r.result!!.pages ?: estimatePages(r.result!!.chars)
            }
            // 勾选的隐藏工作表计入合计（页数不另计，沿用文件级页数）
            r.result!!.hiddenSheets.forEach { hs ->
                if (hiddenSelected["${r.id}::${hs.name}"] == true) {
                    w += hs.words; fe += hs.fe; nc += hs.nc; ch += hs.chars
                }
            }
            // v1.3.32/v1.3.34: 勾选的 PPT 备注汇总计入合计（一条汇总勾选控制全部备注）
            if (hiddenSelected["${r.id}::notes::_summary_"] == true) {
                r.result!!.notesSlides.forEach { ns ->
                    w += ns.words; fe += ns.fe; nc += ns.nc; ch += ns.chars
                }
            }
        }
        mapOf("words" to w, "fe" to fe, "nc" to nc, "chars" to ch, "pages" to pg, "pendingPdf" to pendingPdf)
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
                            // v1.5.37: 有 DWG 待 PDF 统计时，合计行与电脑 APP 一致：字数/中文/非中文处显示"-"，只显示页数
                            val pending = totals["pendingPdf"] ?: 0
                            val totalText = if (pending > 0) {
                                "字数 - ｜ 中文 - ｜ 非中文 - ｜ 页数 ${totals["pages"]}"
                            } else {
                                "字数 ${totals["words"]} ｜ 中文 ${totals["fe"]} ｜ 非中文 ${totals["nc"]} ｜ 页数 ${totals["pages"]}"
                            }
                            Text(totalText)
                        }
                        Spacer(Modifier.padding(4.dp))
                        var isExporting by remember { mutableStateOf(false) }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { pickWithPermission() }, modifier = Modifier.weight(1f)) { Text("选择文件") }
                            OutlinedButton(
                                onClick = { exportUnreliable(context, scope, snackbar, entries, onStateChange = { isExporting = it }) },
                                modifier = Modifier.weight(1f),
                                enabled = entries.any { it.selected && it.result?.hasUnreliable == true } && !isExporting
                            ) { Text(if (isExporting) "导出中…" else "导出未统计图片") }
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
                        // v1.3.4: 空状态说明改为 11.txt 内容；左对齐、黑色字体；新增说明时往 helpLines 追加（编号顺延）
                        val helpLines = listOf(
                            "1、导入文件：从千牛/微信→长按文件→用其他应用打开→选「字数统计」，或点下方「选择文件」从本机选取；",
                            "2、Word字数页数核对：手机安装Word后，doc/docx文件2页以上的可点文件名右侧「Word」直接在 Word 里打开，左下角选「页面视图」，上下滚动查看页数和总字数；",
                            "3、PPT/Excel/PDF 页数核对：手机安装Wps后，点击文件右侧「Wps」直接在Wps里打开，PPT直接下拉，Excel左下角选「逐页输出图片」，PDF下拉查看页数，右下角可看到页数。",
                            "4、\"导出未统计图片\"按钮功能：导出Word/Excel/PPT里的内嵌图片，这部分内容未统计字数。"
                        )
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            horizontalAlignment = Alignment.Start
                        ) {
                            helpLines.forEach { line ->
                                Text(line, color = Color.Black, modifier = Modifier.padding(vertical = 2.dp))
                            }
                        }
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
                            onOpen = { e -> openWithOtherApp(context, e) },
                            onOpenWord = { e -> openWithWord(context, e) },
                            onOpenWps = { e -> openWithWps(context, e) },
                            // v1.5.36: 点红色提示 → 记录条目 id 并启动 PDF 选择器（仅限文字型 PDF）
                            onPickPdf = { e ->
                                pdfPickEntryId = e.id
                                pdfPicker.launch(arrayOf("application/pdf"))
                            },
                            // v1.5.56: 长按文件名或点编辑图标 → 手动重命名（解决微信分享拿不到原文件名的问题）
                            onRename = { e ->
                                renameEntry = e
                                renameText = e.userRenamedName ?: e.displayName
                            },
                            hiddenSelected = hiddenSelected,
                            onToggleHidden = { id, name ->
                                val k = "$id::$name"
                                hiddenSelected[k] = !(hiddenSelected[k] ?: false)
                            }
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

    // v1.5.56: 手动重命名文件对话框（微信分享等场景系统不暴露原文件名时兜底）
    renameEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { renameEntry = null },
            title = { Text("重命名") },
            text = {
                TextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("新文件名") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val newName = renameText.trim()
                    if (newName.isNotBlank()) {
                        val i = entries.indexOfFirst { it.id == entry.id }
                        if (i >= 0) {
                            entries[i] = entry.copy(userRenamedName = newName)
                        }
                    }
                    renameEntry = null
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { renameEntry = null }) { Text("取消") }
            }
        )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileCard(
    entry: FileEntry,
    onToggle: (FileEntry) -> Unit,
    onDelete: (FileEntry) -> Unit,
    onOpen: (FileEntry) -> Unit,
    onOpenWord: (FileEntry) -> Unit,
    onOpenWps: (FileEntry) -> Unit,
    // v1.5.36: DWG 统计不准时，点击红色提示 → 弹窗选文字型 PDF 重新统计
    onPickPdf: (FileEntry) -> Unit,
    // v1.5.56: 长按文件名或点编辑图标可手动重命名
    onRename: (FileEntry) -> Unit,
    // v1.5.61: 改为 MutableMap, CAD 文字/纯编号勾选需要直接写入状态
    hiddenSelected: MutableMap<String, Boolean>,
    onToggleHidden: (String, String) -> Unit
) {
    val expanded = remember { mutableStateOf(false) }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(12.dp)) {
            // 第一行：文件名横跨全宽（方便显示长文件名和点击打开）
            // v1.3.34: 有备注/图片的 PPT 在文件名前加红"备"/"图"标记（和隐藏工作表的"隐"一样醒目）
            val r = entry.result
            val prefixTags = buildString {
                if (r?.notesSlides?.isNotEmpty() == true) append("备")
                if ((r?.imageCount ?: 0) > 0) append("图")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = entry.selected, onCheckedChange = { onToggle(entry) })
                // v1.3.36: 文件名行只显示纯文件名，不显示"备"/"图"/"隐"等前缀
                // v1.5.56: 优先显示用户重命名，长按或点编辑图标可改名
                Text(
                    entry.userRenamedName ?: entry.displayName,
                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .weight(1f)
                        .combinedClickable(
                            onClick = { onOpen(entry) },
                            onLongClick = { onRename(entry) }
                        )
                )
                // 编辑文件名按钮
                IconButton(onClick = { onRename(entry) }, modifier = Modifier.size(28.dp)) {
                    Icon(imageVector = Icons.Default.Edit, contentDescription = "重命名", tint = Color.Gray)
                }
                // 删除按钮
                IconButton(onClick = { onDelete(entry) }, modifier = Modifier.size(32.dp)) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "删除", tint = Color.Gray)
                }
            }
            // 第二行：左边统计信息（占更多空间）+ 右边文件类型/Word/Excel按钮（上下排列）
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    // r 已在文件名行前定义（用于 prefixTags 和此处共用）
                    if (r != null) {
                        val isEstimated = r.pagesReason?.contains("estimate") == true ||
                            r.pagesReason?.contains("layout") == true
                        val pageLabel = if (isEstimated) "页 ${r.pages ?: estimatePages(r.chars)}(估)"
                            else "页 ${r.pages ?: estimatePages(r.chars)}"
                        // v1.5.37/v1.5.62: 需要 PDF 统计的 DWG 仍显示当前已拿到的字数（与电脑版
                        // 一致），方便用户知道“已有统计”是多少；只有完全没拿到字数时才显示"-"。
                        val hasStats = r.words > 0 || r.fe > 0 || r.nc > 0
                        val statsText = if (r.needsPdf && !hasStats) {
                            "字数 - ｜ 中文 - ｜ 非中文 - ｜ $pageLabel" +
                                    (if (r.pagesReason != null && !isEstimated) " ｜ ${r.pagesReason}" else "")
                        } else {
                            "字数 ${r.words} ｜ 中文 ${r.fe} ｜ 非中文 ${r.nc} ｜ $pageLabel" +
                                    (if (r.pagesReason != null && !isEstimated) " ｜ ${r.pagesReason}" else "")
                        }
                        Text(
                            statsText,
                            style = MaterialTheme.typography.bodySmall, color = Color.Gray
                        )
                        // v1.5.66: PDF 的 OCR 状态摘要（直接显示，无需展开诊断）
                        if (r.ocrNote != null) {
                            val isWarn = r.ocrNote!!.startsWith("⚠️")
                            Text(
                                r.ocrNote!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isWarn) Color(0xFFB00020) else Color(0xFF2E7D32)
                            )
                        }
                        // v1.3.6: 明细折叠/展开切换（点击统计行展开）
                        // v1.3.34: 备注合并为一条汇总，所以 notesSlides 只算 1
                        val detailCount = (r.inner?.size ?: 0) + (r.sheets?.size ?: 0) +
                            (r.hiddenSheets?.size ?: 0) +
                            (if (r.notesSlides?.isNotEmpty() == true) 1 else 0) +
                            (if (r.cadParts != null) 2 else 0) +
                            if (r.imageCount > 0) 1 else 0
                        if (detailCount > 0) {
                            Text(
                                if (expanded.value) "▲ 收起明细" else "▶ 展开${detailCount}项明细",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .clickable { expanded.value = !expanded.value }
                            )
                        }
                        if (r.hasUnreliable) Text("含未统计图片（可导出）", style = MaterialTheme.typography.bodySmall, color = Color(0xFFB26A00))
                    } else if (entry.error != null) {
                        val shortErr = entry.error!!.substringBefore('\n').take(200)
                        Text("处理出错：$shortErr", style = MaterialTheme.typography.bodySmall, color = Color(0xFFB00020))
                    } else {
                        Text("统计中…", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                }
                // 右侧：文件类型标签 + 隐藏表/备注/图片标记 + Word/WPS 按钮（上下排列）
                Column(horizontalAlignment = Alignment.End) {
                    // v1.3.3: 有隐藏工作表的文件，在右列顶部显示红色小"隐"字
                    if (entry.result?.hiddenSheets?.isNotEmpty() == true) {
                        Text("隐", style = MaterialTheme.typography.labelSmall, color = Color(0xFFB00020),
                            modifier = Modifier.padding(bottom = 2.dp))
                    }
                    // v1.3.32/v1.3.34: 有备注的 PPT，显示红色小"备"字（和"隐"同色）
                    if (entry.result?.notesSlides?.isNotEmpty() == true) {
                        Text("备", style = MaterialTheme.typography.labelSmall, color = Color(0xFFB00020),
                            modifier = Modifier.padding(bottom = 2.dp))
                    }
                    // v1.3.32/v1.3.34: 有嵌入图片的 PPT，显示红色小"图"字（和"隐"同色）
                    if ((entry.result?.imageCount ?: 0) > 0) {
                        Text("图", style = MaterialTheme.typography.labelSmall, color = Color(0xFFB00020),
                            modifier = Modifier.padding(bottom = 2.dp))
                    }
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
                        Text(" ${entry.result?.ext?.uppercase() ?: "?"} ", Modifier.padding(6.dp, 2.dp), style = MaterialTheme.typography.labelSmall)
                    }
                    // v1.3.0: 用 Word 打开（仅 Word 文档/txt/rtf 显示）
                    val wordExts = setOf(".doc", ".docx", ".txt", ".rtf")
                    if (wordExts.contains((entry.result?.ext ?: "").lowercase())) {
                        Text("Word",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF2B579A),
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .clickable { onOpenWord(entry) })
                    }
                    // v1.3.4/v1.3.32: 用 Wps 打开（xls/xlsx + ppt/pptx + pdf，仅支持 WPS）
                    val wpsExts = setOf(".xls", ".xlsx", ".ppt", ".pptx", ".pdf")
                    if (wpsExts.contains((entry.result?.ext ?: "").lowercase())) {
                        Text("Wps",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF217346),
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .clickable { onOpenWps(entry) })
                    }
                    // v1.5.37/v1.5.63: DWG 统计不准（needsPdf）且确实没拿到任何字数时，
                    //   在右下角显示红色「用PDF统计」按钮，点此弹窗选一份文字型 PDF 来
                    //   重新统计。如果已经拿到字数（如 v1.5.62 水雾终极兜底成功），则不再
                    //   显示该按钮，避免用户困惑。
                    val result = entry.result
                    val hasAnyWords = (result?.words ?: 0) > 0 || (result?.fe ?: 0) > 0 || (result?.nc ?: 0) > 0
                    if (result?.needsPdf == true && !hasAnyWords) {
                        Text("用PDF统计",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFB00020),
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .clickable { onPickPdf(entry) })
                    }
                }
            }
            // v1.3.6: 明细区默认折叠，点击"展开N项明细"才显示
            if (expanded.value) {
            entry.result?.inner?.forEach { inner ->
                Row(Modifier.padding(start = 40.dp, top = 2.dp)) {
                    Text("└ ${inner.name}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("字 ${inner.words} 中 ${inner.fe} 非 ${inner.nc}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
            entry.result?.sheets?.forEach { s ->
                Text("▪ 工作表：$s", Modifier.padding(start = 40.dp, top = 2.dp), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            // v1.3.3: 隐藏工作表（红"隐" + 勾选框 + 名称 + 字数），勾选后并入合计
            entry.result?.hiddenSheets?.forEach { hs ->
                val checked = hiddenSelected["${entry.id}::${hs.name}"] ?: false
                Row(Modifier.padding(start = 32.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("隐", style = MaterialTheme.typography.labelSmall, color = Color(0xFFB00020))
                    Checkbox(checked = checked, onCheckedChange = { onToggleHidden(entry.id, hs.name) }, modifier = Modifier.size(24.dp))
                    Text(hs.name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("字 ${hs.words} 中 ${hs.fe} 非 ${hs.nc}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
            // v1.3.34: PPT 备注汇总（红"备" + 勾选框 + "所有备注(N张)" + 汇总字数），勾选后并入合计
            val notes = entry.result?.notesSlides
            if (!notes.isNullOrEmpty()) {
                // 汇总所有备注的字数
                val totalNotesWords = notes.sumOf { it.words }
                val totalNotesFe = notes.sumOf { it.fe }
                val totalNotesNc = notes.sumOf { it.nc }
                // 用第一条备注的 key 作为勾选状态 key（勾选=全部并入）
                val notesKey = "${entry.id}::notes::_summary_"
                val notesChecked = hiddenSelected[notesKey] ?: false
                Row(Modifier.padding(start = 32.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("备", style = MaterialTheme.typography.labelSmall, color = Color(0xFFB00020))
                    Checkbox(checked = notesChecked, onCheckedChange = { onToggleHidden(entry.id, "notes::_summary_") }, modifier = Modifier.size(24.dp))
                    Text("备注（${notes.size}）", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text("字 $totalNotesWords 中 $totalNotesFe 非 $totalNotesNc", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
            // v1.5.61: CAD 文字/纯编号拆分（默认都勾选，与父行总数一致；可取消勾选以汇总指定部分）
            val cadParts = entry.result?.cadParts
            if (cadParts != null) {
                val textKey = "${entry.id}::cad::text"
                val codeKey = "${entry.id}::cad::code"
                val textChecked = hiddenSelected[textKey] ?: true
                val codeChecked = hiddenSelected[codeKey] ?: true
                Row(Modifier.padding(start = 32.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("文", style = MaterialTheme.typography.labelSmall, color = Color(0xFF2B579A))
                    Checkbox(checked = textChecked, onCheckedChange = { hiddenSelected[textKey] = !(hiddenSelected[textKey] ?: true) }, modifier = Modifier.size(24.dp))
                    Text("文字部分（${cadParts.textItems}）", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text("字 ${cadParts.textWords} 中 ${cadParts.textFe} 非 ${cadParts.textNc}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                Row(Modifier.padding(start = 32.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("编", style = MaterialTheme.typography.labelSmall, color = Color(0xFF2B579A))
                    Checkbox(checked = codeChecked, onCheckedChange = { hiddenSelected[codeKey] = !(hiddenSelected[codeKey] ?: true) }, modifier = Modifier.size(24.dp))
                    Text("纯编号部分（${cadParts.codeItems}）", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text("字 ${cadParts.codeWords} 中 ${cadParts.codeFe} 非 ${cadParts.codeNc}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
            // v1.3.32/v1.3.34: PPT 嵌入图片（红"图" + 张数），页数列显示张数，字数列显示"—"
            if ((entry.result?.imageCount ?: 0) > 0) {
                Row(Modifier.padding(start = 32.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("图", style = MaterialTheme.typography.labelSmall, color = Color(0xFFB00020))
                    Text("嵌入图片", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text("—", style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                        modifier = Modifier.padding(end = 8.dp))
                    Text("${entry.result!!.imageCount} 张", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
            } // end expanded
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
/**
 * 纯 Kotlin 字数统计，严格对齐桌面版 wordcount.py 的 count_unit 算法（v1.2.6 回退修正）。
 *
 * 口径与桌面版 wordcount.py 完全一致（该算法已用 Word COM 校验）：
 *   fe（中文字符和朝鲜语单词）= 落在 FarEast Unicode 区间内的每个字符各算 1 个
 *   nc（非中文单词）           = 连续的「非空白、非 FarEast」字符串算 1 个词
 *   chars（字符数不计空格）    = 所有非空白字符总数
 *   words = fe + nc
 *
 * FarEast 区间定义（与桌面版 _FAR 完全一致 + v1.2.2 新增通用标点）：
 *   \u1100-\u11FF   Hangul Jamo
 *   \u2000-\u206F   通用标点（弯引号、破折号、省略号等，v1.2.2新增）
 *   \u3000-\u303F   CJK 符号与标点
 *   \u3130-\u318F   Hangul 兼容 Jamo
 *   \u3400-\u4DBF   CJK 扩展 A
 *   \u4E00-\u9FFF   CJK 基本平面
 *   \uA960-\uA97C   Hangul Jamo 扩展 A
 *   \uAC00-\uD7A3   Hangul 音节
 *   \uD7B0-\uD7FF   Hangul Jamo 扩展 B
 *   \uF900-\uFAFF   CJK 兼容
 *   \uFF00-\uFFEF   全角字符
 *
 * 重要：ASCII 标点（,.!?()等）不在 FarEast 区间内，会被归入 nc 连续段
 * （这是正确行为——与 Word COM 的 FarEastCharacters 定义一致）
 *
 * 返回 (words, fe, nc, chars)
 */
fun countTextKotlin(text: String): Quadruple<Int, Int, Int, Int> {
    // FarEast 正则：与桌面版 wordcount.py 的 _FAR 完全一致（v1.2.8: 去掉 v1.2.2 误加的 \u2000-\u206F）
    val farEastRegex = Regex("[\\u1100-\\u11FF\\u3000-\\u303F\\u3130-\\u318F\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uA960-\\uA97C\\uAC00-\\uD7A3\\uD7B0-\\uD7FF\\uF900-\\uFAFF\\uFF00-\\uFFEF]")
    // 非 CJK 词：连续的非空白、非 FarEast 字符串
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

/**
 * v1.5.61: 判断一条 CAD 文字条目是否属于『纯编号』（不需要翻译）。
 * 端口桌面版 wordcount.py is_cad_code_item，保守判定：
 *   - 含任何 CJK/假名/韩文          → 文字部分
 *   - 含任何长度 ≥2 的连续字母串    → 文字部分（DN100 / PE / Pump / Room）
 *   - 其余（纯数字、数字+下划线、单字母+数字、纯符号、空串）→ 纯编号部分
 */
private val CAD_CJK_RE = Regex("[\\u3400-\\u4dbf\\u4e00-\\u9fff\\uf900-\\ufaff\\u3040-\\u30ff\\uac00-\\ud7af]")
private val CAD_WORD_RE = Regex("[A-Za-z\\u00c0-\\u024f]{2,}")
fun isCadCodeItem(s: String): Boolean {
    val t = s.trim()
    if (t.isEmpty()) return true
    if (CAD_CJK_RE.containsMatchIn(t)) return false
    if (CAD_WORD_RE.containsMatchIn(t)) return false
    return true
}

/**
 * v1.5.61: 把 CAD 提取文本拆成文字部分 / 纯编号部分，并分别统计。
 * 父行总数不变；展开后两个子项可加选框控制是否并入底部合计。
 * 没有编号条目时返回 null（不必展开）。
 */
fun computeCadParts(text: String): CadPartStats? {
    if (text.isBlank()) return null
    val textLines = mutableListOf<String>()
    val codeLines = mutableListOf<String>()
    for (line in text.split("\n")) {
        val s = line.trim()
        if (s.isEmpty()) continue
        if (isCadCodeItem(s)) codeLines.add(s) else textLines.add(s)
    }
    if (codeLines.isEmpty()) return null
    val textStats = countTextKotlin(textLines.joinToString("\n"))
    val codeStats = countTextKotlin(codeLines.joinToString("\n"))
    return CadPartStats(
        textWords = textStats.first, textFe = textStats.second, textNc = textStats.third, textChars = textStats.fourth,
        codeWords = codeStats.first, codeFe = codeStats.second, codeNc = codeStats.third, codeChars = codeStats.fourth,
        textItems = textLines.size, codeItems = codeLines.size
    )
}

/**
 * v1.5.10: 直接扫描 DWG 二进制提取文字（移植自 port_dwg.py improved 模式）。
 * 不再依赖 DXF 中间格式，避免 DXF TEXT/MTEXT 含尺寸数值/坐标/格式码导致字数虚高。
 * 算法：扫描原始字节 → ASCII串 + UTF-16LE串 + CJK → stopwords/元音/数字比例过滤 → 去重
 */
private val DWG_STOPWORDS = setOf(
    "standard","bylayer","byblock","continuous","defpoints","model","layout",
    "paper","space","center","dashed","dot","divide","border","phantom",
    "hidden","dashdot","chain","zigzag",
    "txt","romans","romanc","italicc","italict","scripts","scriptc",
    "greeks","greeke","cyrillic","cyriltlc","monotxt","simplex",
    "complex","isoct","isocteur",
    "autocad","acad","entity","handle","object","dictionary",
    "linetype","layer","style","block","viewport","ucs","view",
    "table","id","type","owner","flags","count","index","name",
    "data","null","true","false","none","normal","color","width",
    "height","length","angle","point","line","circle","arc","text",
    "dimension","leader","hatch","solid","polyline","insert",
    "attrib","mtext","attdef","acdb","acds","acim","objects",
    "classes","handles","summaryinfo","preview","appinfo",
    "filedeps","security","revhistory","header","auxheader",
    "signature","template"
)

private fun isCjkChar(cp: Int): Boolean {
    return (cp in 0x4E00..0x9FFF) || (cp in 0x3400..0x4DBF) ||
           (cp in 0x3000..0x303F) || (cp in 0xFF00..0xFFEF) ||
           (cp in 0x2E80..0x2EFF) || (cp in 0xF900..0xFAFF)
}

/** 判断字符串是否像真实文字（非 CAD 元数据/坐标/二进制垃圾）*/
private fun looksLikeRealText(s: String, minRun: Int): Boolean {
    if (s.length < minRun) return false
    val low = s.lowercase()
    if (low in DWG_STOPWORDS) return false
    // 至少 3 个字母
    val letters = s.count { it.isLetter() }
    if (letters < 3) return false
    // 数字占比不超过 50%（排除坐标）
    val digits = s.count { it.isDigit() }
    if (s.isNotEmpty() && digits.toDouble() / s.length > 0.5) return false
    // 符号占比不超过 35%
    val alnum = letters + digits
    if (s.isNotEmpty() && (s.length - alnum).toDouble() / s.length > 0.35) return false
    // 纯大写短串（<=6字符）通常是缩写/代码
    if (s.all { it.isUpperCase() || !it.isLetter() } && s.length <= 6) return false
    // 需要元音或空格（多词短语）
    val hasVowel = s.any { it.lowercase() in "aeiou" }
    val hasSpace = ' ' in s
    return hasVowel || hasSpace
}

fun extractDxfText(dxfPath: String): String {
    // v1.5.10: 此函数保留签名兼容但不再用于 DWG 字数统计；
    // DWG 统计已改用 scanDwgRaw() 直接扫二进制。
    // 若传入的是 DXF 文件则走原有逻辑兜底。
    return if (dxfPath.endsWith(".dxf", ignoreCase = true)) {
        extractDxfTextCompat(dxfPath)
    } else {
        scanDwgRaw(dxfPath)
    }
}

/** 原有 DXF 解析逻辑（仅作 .dxf 文件兜底）*/
private fun extractDxfTextCompat(dxfPath: String): String {
    val sb = StringBuilder()
    try {
        val lines = File(dxfPath).readLines()
        var i = 0
        var currentEntity = ""
        while (i < lines.size - 1) {
            val code = lines[i].trim()
            val value = if (i + 1 < lines.size) lines[i + 1].trim() else ""
            when (code) {
                "0" -> currentEntity = value.uppercase()
                "1", "3" -> {
                    if (value.isNotEmpty() && (currentEntity == "TEXT" || currentEntity == "MTEXT" || currentEntity == "ATTDEF")) {
                        if (sb.isNotEmpty()) sb.append('\n')
                        sb.append(value)
                    }
                }
            }
            i += 2
        }
    } catch (e: Exception) {
        Log.w("WordCount", "DXF 兼容提取异常: ${e.message}")
    }
    return sb.toString()
}

/**
 * 核心：直接扫描 DWG 二进制字节提取文字。
 * 移植自 port_dwg.py improved 模式——与电脑端完全一致的过滤逻辑。
 */
fun scanDwgRaw(dwgPath: String): String {
    val out = mutableListOf<String>()
    val seen = HashSet<String>(500)
    val asciiBuf = StringBuilder()
    val cjkBuf = StringBuilder()

    fun flushAscii() {
        if (asciiBuf.isEmpty()) return
        val s = asciiBuf.toString()
        asciiBuf.clear()
        if (looksLikeRealText(s, 4) && s !in seen) {
            seen.add(s); out.add(s)
        }
    }

    fun flushCjk() {
        if (cjkBuf.isEmpty()) return
        val s = cjkBuf.toString()
        cjkBuf.clear()
        if (s.length >= 3 && s !in seen) {
            seen.add(s); out.add(s)
        }
    }

    try {
        val data = File(dwgPath).readBytes()
        val n = data.size
        var i = 0
        while (i < n && out.size < 50000) {  // 上限防 OOM
            val b = data[i].toInt() and 0xFF
            when {
                // ASCII run (not part of UTF-16LE)
                b in 0x20..0x7E && (i + 1 >= n || data[i + 1] != 0x00.toByte()) -> {
                    asciiBuf.append(b.toChar()); i++
                }
                // UTF-16LE string
                b in 0x20..0x7E && i + 1 < n && data[i + 1] == 0x00.toByte() -> {
                    while (i + 1 < n) {
                        val c = data[i].toInt() and 0xFF
                        val nx = data[i + 1]
                        if (c in 0x20..0x7E && nx == 0x00.toByte()) {
                            asciiBuf.append(c.toChar()); i += 2
                        } else break
                    }
                }
                // CJK (UTF-8 3-byte sequence)
                b in 0xE0..0xEF && i + 2 < n -> {
                    val b2 = data[i + 1].toInt() and 0xFF
                    val b3 = data[i + 2].toInt() and 0xFF
                    if (b2 in 0x80..0xBF && b3 in 0x80..0xBF) {
                        val cp = ((b and 0x0F) shl 12) or ((b2 and 0x3F) shl 6) or (b3 and 0x3F)
                        if (isCjkChar(cp)) {
                            cjkBuf.append(cp.toChar())
                        } else { flushCjk(); flushAscii() }
                        i += 3
                    } else { flushCjk(); flushAscii(); i++ }
                }
                else -> { flushAscii(); flushCjk(); i++ }
            }
        }
        flushAscii(); flushCjk()
    } catch (e: Exception) {
        Log.w("WordCount", "DWG 二进制扫描异常: ${e.message}")
    }

    return out.joinToString("\n")
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

/** v1.3.0: 直接拉起手机 Microsoft Word 打开文件（用于核对 Word 显示的页数和字数）。
 *  若未安装 Word 或打开失败，退回系统选择器。 */
private fun openWithWord(context: android.content.Context, entry: FileEntry) {
    try {
        val file = File(entry.cachePath)
        if (!file.exists()) {
            Log.w("WordCount", "用Word打开失败：缓存文件不存在 ${entry.displayName}")
            return
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val mime = mimeForExt(entry.result?.ext ?: "")
        // 直接指定 Word 包名，不经过 resolveActivity 检查（该检查在某些设备上误判）
        try {
            val wordIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                `package` = "com.microsoft.office.word"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(wordIntent)
        } catch (e: android.content.ActivityNotFoundException) {
            // 未安装 Word，退回系统选择器
            val fallback = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(fallback, "用其他应用打开"))
        }
    } catch (e: Throwable) {
        Log.w("WordCount", "用Word打开失败 ${entry.displayName}: ${e.message}")
    }
}

/** v1.3.3: 直接拉起手机 Excel/WPS 打开文件（用于核对 Excel 显示的页数和字数）。
 *  优先级：WPS → Microsoft Excel → 系统选择器。WPS 的「逐页输出图片」导出动作无文件级
 *  页数元数据，无法自动读取，故提供此按钮让用户手动核对。 */
private fun openWithWps(context: android.content.Context, entry: FileEntry) {
    try {
        val file = File(entry.cachePath)
        if (!file.exists()) {
            Log.w("WordCount", "用Wps打开失败：缓存文件不存在 ${entry.displayName}")
            return
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val mime = mimeForExt(entry.result?.ext ?: "")
        // v1.3.4: 仅支持用 WPS 打开（不再尝试 Microsoft Excel / 系统选择器）
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                `package` = "cn.wps.moffice_eng"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            // 未安装 WPS
            android.widget.Toast.makeText(
                context, "未安装 WPS，请先安装 WPS 后再打开", android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    } catch (e: Throwable) {
        Log.w("WordCount", "用Wps打开失败 ${entry.displayName}: ${e.message}")
    }
}

/**
 * v1.5.36: 用户为「统计不准」的 DWG 选定一份文字型 PDF 后，用该 PDF 重新统计该文件。
 *   复用 PDF 提取 Level1(Kotlin PdfExtractor)+Level2(Python pdfminer)，取 chars 较多者，
 *   返回新的字数/页数；若都提取不到文字则返回 null（调用方保留原 DWG 结果并提示）。
 *   仅用于「文字型」PDF（非扫描件）；扫描件/图片型 PDF 抽不到字，会返回 null。
 *   取代旧版 exportDwgToPdf（LibreDWG 自渲染的 PDF 文字层是栅格化图像，抽不到字、没用）。
 */
private data class RecomputedPdf(val words: Int, val fe: Int, val nc: Int, val chars: Int, val pages: Int?, val diag: String?)

private suspend fun recomputeFromPdf(context: android.content.Context, pdfFile: File, dName: String): RecomputedPdf? {
    return withContext(Dispatchers.IO) {
        try {
            // ── Level 1: Kotlin PdfExtractor（快速预筛）──
            val ktRes = PdfExtractor.extract(pdfFile)
            val ktStats = countTextKotlin(ktRes.text)
            var bestWords = ktStats.first; var bestFe = ktStats.second; var bestNc = ktStats.third; var bestChars = ktStats.fourth
            var bestPages: Int? = ktRes.pages
            var bestDiag = "Kotlin提取(${bestChars}字)"
            Log.d("WordCount", "recomputeFromPdf L1 $dName: chars=$bestChars words=$bestWords pages=$bestPages")
            // ── Level 2: Python pdfminer（文字型 PDF 主力）──
            try {
                val pyResults = PythonEngine.countFiles(context, listOf(pdfFile.absolutePath))
                @Suppress("UNCHECKED_CAST")
                val pyList = pyResults as? List<Map<String, Any?>>
                if (!pyList.isNullOrEmpty()) {
                    val py0 = pyList[0]
                    if (py0["ok"] == true) {
                        val pyData = py0["result"] as? Map<String, Any?>
                        val pyS = pyData?.get("stats") as? Map<String, Any?>
                        val pyWords = (pyS?.get("words") as? Number)?.toInt() ?: 0
                        val pyFe = (pyS?.get("fe") as? Number)?.toInt() ?: 0
                        val pyNc = (pyS?.get("nc") as? Number)?.toInt() ?: 0
                        val pyChars = (pyS?.get("chars") as? Number)?.toInt() ?: 0
                        val pyPages = (pyData?.get("pages") as? Number)?.toInt()
                        if (pyChars > bestChars) {
                            bestWords = pyWords; bestFe = pyFe; bestNc = pyNc; bestChars = pyChars
                            bestPages = pyPages ?: bestPages
                            bestDiag = "Python提取(${bestChars}字)"
                        }
                        Log.d("WordCount", "recomputeFromPdf L2 $dName: chars=$pyChars pages=$pyPages")
                    } else {
                        Log.w("WordCount", "recomputeFromPdf L2 ok=false $dName: ${py0["error"]}")
                    }
                }
            } catch (e: Throwable) {
                Log.w("WordCount", "recomputeFromPdf Python异常 $dName: ${e.javaClass.simpleName}: ${e.message}")
            }
            if (bestChars <= 0) {
                Log.w("WordCount", "recomputeFromPdf 未提取到文字 $dName")
                null
            } else {
                RecomputedPdf(bestWords, bestFe, bestNc, bestChars, bestPages, bestDiag)
            }
        } catch (e: Throwable) {
            Log.w("WordCount", "recomputeFromPdf 异常 $dName: ${e.message}")
            null
        }
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
 * v1.5.55: 扫描 URI 的所有可见部分（path segments / query / fragment / lastPathSegment），
 * 尝试找回被 ContentResolver 隐藏的真实文件名。微信/QQ 等应用分享文件时，
 * DISPLAY_NAME 经常返回内部缓存 ID，但原文件名可能仍保留在 URI 的某个片段里。
 */
private fun scanUriForRealName(uri: Uri): String? {
    val knownExts = setOf(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt",
        "png", "jpg", "jpeg", "bmp", "gif", "webp", "tif", "tiff",
        "zip", "rar", "7z", "dwg", "dxf"
    )
    val rawSources = listOfNotNull(
        uri.toString(),
        uri.encodedPath,
        uri.path,
        uri.query,
        uri.fragment,
        uri.lastPathSegment
    )
    val parts = mutableListOf<String>()
    rawSources.forEach { raw ->
        // 有些路径会被整体 URL 编码一次甚至两次，逐级解码
        var current = raw
        repeat(3) {
            val decoded = try { java.net.URLDecoder.decode(current, "UTF-8") } catch (_: Throwable) { current }
            if (decoded == current) return@repeat
            parts.addAll(decoded.split('/', '\\', '?', '&', '=', '#', ':', ';'))
            current = decoded
        }
        // 最后也把未解码的源本身按分隔符拆开（防止解码失败时遗漏）
        parts.addAll(raw.split('/', '\\', '?', '&', '=', '#', ':', ';'))
    }

    return parts.asSequence()
        .map { it.trim() }
        .filter { it.length in 5..250 }
        .filter { cand ->
            val dot = cand.lastIndexOf('.')
            if (dot <= 0 || dot >= cand.length - 1) return@filter false
            cand.substring(dot + 1).lowercase() in knownExts
        }
        .filterNot { looksLikeHashString(it) }
        .sortedWith(
            compareByDescending<String> { it.any { c -> c.code in 0x4E00..0x9FFF } }
                .thenByDescending { it.length }
                .thenByDescending { it.count { c -> c.isLetterOrDigit() } }
        )
        .firstOrNull()
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

    // 策略0（v1.5.56）: 部分 ROM / 微信分享 ContentProvider 在 _data 列暴露真实文件路径，
    //   从路径里取到的文件名通常比 DISPLAY_NAME 可靠。
    try {
        context.contentResolver.query(
            uri,
            arrayOf("_data"),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst() && cursor.columnCount > 0) {
                val path = cursor.getString(0)
                if (!path.isNullOrBlank()) {
                    val name = path.trim().substringAfterLast('/').substringAfterLast('\\')
                    if (name.isNotBlank() && name.contains('.') && !looksLikeHash(name)) {
                        Log.d("WordCount", "resolveDisplayName s0(_data path OK): '$name'")
                        return name.trim()
                    }
                    Log.d("WordCount", "resolveDisplayName s0 _data 被hash拦截/无扩展名: '$name'")
                }
            }
        }
    } catch (_: Throwable) {}

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

    // 策略2.5 (v1.5.55): 扫描 URI 全部片段（path/query/fragment）。
    // 微信/QQ 等分享时，ContentResolver 只返回内部缓存 ID，但原文件名可能还藏在 URI 里。
    scanUriForRealName(uri)?.let { name ->
        if (name.isNotBlank()) {
            Log.d("WordCount", "resolveDisplayName s2.5(URI scan OK): '$name'")
            return name.trim()
        }
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
    // v1.5.55: 微信/QQ 等分享传入时，Intent extras(EXTRA_SUBJECT/TITLE/_display_name)
    // 常携带原文件名，比 ContentResolver.DISPLAY_NAME 更可靠。
    val intentNameHint = MainActivity.pendingUriNames[uri]?.trim()
    val resolvedName = resolveDisplayName(context, uri)
    val originalName = when {
        intentNameHint.isNullOrBlank() -> resolvedName
        looksLikeHashString(intentNameHint) || isSuspiciousFilename(intentNameHint)
            || isNumberedOrGenericName(intentNameHint) -> resolvedName
        else -> intentNameHint
    }
    Log.d("WordCount", "copyUriToCache originalName='$originalName' hint='${intentNameHint ?: ""}' resolved='$resolvedName'")

    // v1.5.53 修正：basename <= 8 会误伤正常短文件名（如 Tenova.dwg、图.dwg），
    //   把它们全换成 "文档_<hash>.dwg" 导致用户无法识别。改为只拦截真正无意义的：
    //   A. hash/UUID/内部ID（looksLikeHashString / isSuspiciousFilename）
    //   B. 编号模式："1-1"、"1-(1)"、"图1"、"Sheet1"、纯数字、"No.1" 等
    //   C. 通用名前缀（"Word文档"/"PDF文档" 等，含安全网自身生成的前缀）
    //   D. 明确 "数字-数字)" 等带括号的短编号
    val baseName = originalName.substringBeforeLast('.').ifBlank { originalName }
    val isShortOrGeneric = looksLikeHashString(originalName)
            || isSuspiciousFilename(originalName)
            || isNumberedOrGenericName(originalName)
            // 明确捕获 "数字-数字)" 等带括号的短编号
            || Regex("""^\d+[)-]\d+\)?$""").matches(baseName)

    val displayName = if (isShortOrGeneric) {
        val ext = guessExt(context, uri)
        val safeExt = if (ext.isNotBlank()) ".$ext" else ""
        // v1.5.55: 对内部 ID/编号等无意义名，优先直接显示清理后的原始 ID，
        // 比 "文档_xxxx.dwg" 更简洁，也保留更多识别信息。
        val cleanedOriginal = originalName.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        val directName = if (cleanedOriginal.contains('.') && cleanedOriginal.length in 5..128) cleanedOriginal else ""
        val isSafeNetPrefix = directName.startsWith("Word文档") || directName.startsWith("PDF文档")
            || directName.startsWith("Excel表格") || directName.startsWith("PPT演示")
            || directName.startsWith("文本文件") || directName.startsWith("图片")
            || directName.startsWith("文档") || directName.startsWith("压缩包")
            || directName.startsWith("CAD图纸")
        if (directName.isNotBlank() && !isSafeNetPrefix) {
            Log.w("WordCount", "copyUriToCache 安全网直接显示内部ID: '$originalName' → '$directName'")
            directName
        } else {
            val typeLabel = when (ext.lowercase()) {
                "pdf" -> "PDF文档"
                "doc", "docx" -> "Word文档"
                "xls", "xlsx" -> "Excel表格"
                "ppt", "pptx" -> "PPT演示"
                "txt" -> "文本文件"
                "png", "jpg", "jpeg", "bmp", "gif", "webp" -> "图片"
                else -> "文档"
            }
            // v1.1.50: 用文件路径hash生成短后缀（4位hex），确保同名文件可区分
            val shortHash = absoluteHashCode(originalName).toString(16).takeLast(4).uppercase()
            val result = "${typeLabel}_${shortHash}${safeExt}"
            Log.w("WordCount", "copyUriToCache 安全网触发: '$originalName' → '$result' (baseName='$baseName' len=${baseName.length})")
            result
        }
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
                // 文件名 hint 已使用，清空避免影响后续通过 SAF 选择的文件
                MainActivity.pendingUriNames.clear()
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
                        val res = ArchiveEngine.extract(f, context.cacheDir, context)
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
                            // v1.3.89 metaWords 安全网（修复 VML 文本框双写导致翻倍的反案例）：
                            //   v1.3.4 因调查问卷元数据(Words=1089)过期决定一律现算、不用 metaWords。
                            //   但营业执照类 WPS 文件每个文本框同时存 DrawingML + VML 两份，
                            //   现算把 v:textbox 内嵌的 p/r/t 也提取了 → 690 词 vs Word 真值 175。
                            //   策略：metaWords > 0 且 现算 > 1.5×metaWords 时，判定为重复/膨胀，
                            //         优先用 metaWords（Word/WPS 自带统计最权威），fe/nc 按比例分配。
                            //   否则保持现算（覆盖 v1.3.4 的元数据过期场景）。
                            val rawWords = stats.first
                            val rawFe = stats.second
                            val rawNc = stats.third
                            val rawChars = stats.fourth
                            // v1.3.98: 恢复 metaWords 安全网（智能模式）。
                            // 背景：v1.3.93 因"含 VML 文本框的中文营业执照 metaWords 偏低"而一刀切废弃。
                            // 但对无 VML 的普通 docx（如纯英文翻译件），metaWords 与 Word 对话框完全一致，
                            // 弃用后现算值因 fallback 补充扫描/子串去重不完美导致偏多（175→439）。
                            // 策略：
                            //   ① 无 VML 且 metaWords > 0 → 直接用 metaWords（= Word 真值）
                            //   ② 有 VML 但现算值 > 1.5×metaWords → 用 metaWords（判定为膨胀）
                            //   ③ 其他情况 → 保持现算（覆盖元数据过期/文本框额外内容场景）
                            val outWords: Int
                            val outFe: Int
                            val outNc: Int
                            val outChars: Int
                            if (res.metaWords > 0 && !res.hasVml) {
                                // 无 VML 文本框：metaWords = Word 对话框字数，最权威
                                outWords = res.metaWords
                                val ratio = if (rawWords > 0) rawWords.toDouble() / res.metaWords else 1.0
                                outFe = (rawFe / ratio).toInt().coerceAtLeast(0)
                                outNc = (rawNc / ratio).toInt().coerceAtLeast(0)
                                outChars = (rawChars / ratio).toInt().coerceAtLeast(0)
                                Log.d("WordCount", "docx: 使用 metaWords=${res.metaWords}(无VML权威值) 现算=$rawWords")
                            } else if (res.metaWords > 0 && rawWords > (res.metaWords * 1.5).toInt()) {
                                // 有 VML 但现算明显膨胀：回退到 metaWords
                                outWords = res.metaWords
                                val ratio = rawWords.toDouble() / res.metaWords
                                outFe = (rawFe / ratio).toInt().coerceAtLeast(0)
                                outNc = (rawNc / ratio).toInt().coerceAtLeast(0)
                                outChars = (rawChars / ratio).toInt().coerceAtLeast(0)
                                Log.d("WordCount", "docx: 回退 metaWords=${res.metaWords}(现算${rawWords}膨胀>1.5x)")
                            } else {
                                // 默认：用现算值
                                outWords = rawWords
                                outFe = rawFe
                                outNc = rawNc
                                outChars = rawChars
                                Log.d("WordCount", "docx: 现算=($rawWords,$rawFe,$rawNc,$rawChars) metaWords=${res.metaWords}")
                            }
                            val outPages = if (res.metaPages > 0) res.metaPages else res.pages
                            val outReason = if (res.pagesReason.isNotBlank()) res.pagesReason else null
                            Log.d("WordCount", "docx: 现算=($rawWords,$rawFe,$rawNc,$rawChars) metaWords=${res.metaWords}(不使用) 输出=($outWords,$outFe,$outNc,$outChars) pages=$outPages")
                            // v1.3.3: 隐藏工作表单独统计（默认不计入合计，UI 勾选后才并入）
                            val hiddenStats = res.hiddenSheets.map { (n, t) ->
                                val s = countTextKotlin(t)
                                SheetStat(n, s.first, s.second, s.third, s.fourth)
                            }
                            // v1.3.32: PPT 备注幻灯片单独统计（默认不计入合计，UI 勾选后才并入）
                            val notesStats = res.notesSlides.map { (n, t) ->
                                val s = countTextKotlin(t)
                                SheetStat(n, s.first, s.second, s.third, s.fourth)
                            }
                            val resMap = mapOf(
                                "name" to dName, "ext" to ".${f.extension.lowercase()}",
                                "stats" to mapOf("words" to outWords, "fe" to outFe, "nc" to outNc, "chars" to outChars),
                                "meta" to mapOf("sheets" to res.sheets, "hidden_sheets" to hiddenStats,
                                    "notes_slides" to notesStats, "image_count" to res.imageCount,
                                    "internal_title" to res.internalTitle),
                                "pages" to outPages,
                                "pages_reason" to outReason
                            )
                            val fr = toFileResult(resMap, f.absolutePath)
                            // v1.3.32: 如果 URI 无法获取真实文件名（显示为"PPT演示_A04D.pptx"等生成名），
                            //   尝试用 OOXML 内部标题（docProps/core.xml <dc:title>）替换
                            val finalDisplayName = if (res.internalTitle.isNotBlank() &&
                                (dName.startsWith("PPT演示_") || dName.startsWith("Word文档_") ||
                                 dName.startsWith("Excel表格_") || dName.startsWith("PDF文档_") ||
                                 dName.startsWith("文档_") || dName.startsWith("文本文件_") ||
                                 looksLikeHashString(dName) || isSuspiciousFilename(dName))) {
                                val ext = f.extension.lowercase()
                                "${res.internalTitle}.${if (ext.isNotBlank()) ext else "file"}"
                            } else dName
                            entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_oo", displayName = finalDisplayName, cachePath = f.absolutePath, result = fr, rawResult = resMap))
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
                        // v1.5.66: 用系统 PdfRenderer 取可靠页数（Kotlin 的 countPagesSafe 对压缩流 PDF 会误判成 1 页）
                        val realPages = reliablePdfPageCount(f)
                        Log.d("WordCount", "PDF 可靠页数 $dName: realPages=$realPages (ktPages=${ktRes.pages})")

                        // ── Level 2: Python pdfminer（文字型 PDF 的主力）──
                        var pyWords = 0; var pyFe = 0; var pyNc = 0; var pyChars = 0; var pyPages = 0
                        var pyOk = false
                        var pyError: String? = null
                        // v1.3.63: 先测试 Python 引擎是否正常工作
                        // v1.3.64: 将诊断结果存到变量，最终拼进界面显示
                        var pyDiag: String? = null
                        try {
                            pyDiag = PythonEngine.testPython(context)
                            Log.d("WordCount", "PDF Python诊断 $dName: $pyDiag")
                        } catch (e: Throwable) {
                            pyDiag = "Python诊断异常: ${e.javaClass.simpleName}: ${e.message}"
                            Log.w("WordCount", "PDF Python诊断失败 $dName: $pyDiag")
                        }
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

                        Log.d("WordCount", "PDF $dName → KT:${ktStats.fourth}ch(fe=${ktStats.second}) PY:${pyChars}ch(fe=$pyFe)(pyOk=$pyOk) KT_rel=${ktRes.reliable}")

                        // ── 决策：选 Kotlin 或 Python 的较好结果 ──
                        //   pdfminer 通常更准确（处理了 ToUnicode CMap / ObjStm 等）
                        //
                        //   v1.3.53 修复：Kotlin PdfExtractor 对 Identity-H / 多字节 CID 编码的中文 PDF
                        //   会产生大量 Latin-1 乱码（fe=0 但 char 数虚高）。此时不能单纯比较 char 数，
                        //   而应检测 Kotlin 结果是否为"假阳性"（fe=0 + 大量 nc）。
                        //
                        //   选择逻辑：
                        //     a) Kotlin 结果像 CID 乱码（fe=0 且 chars>100）→ 只要 Python 成功就用 Python
                        //     b) 否则 → Python chars 更多时用 Python（原有逻辑）
                        val ktLooksLikeCidGarbage = ktStats.fourth > 100 && ktStats.second == 0
                                                && ktStats.third > ktStats.fourth * 0.5
                        val usePython = pyOk && (pyChars > ktStats.fourth || ktLooksLikeCidGarbage)
                        Log.d("WordCount", "PDF决策 $dName: pyOk=$pyOk pyChars=$pyChars ktChars=${ktStats.fourth} usePython=$usePython cidGarbage=$ktLooksLikeCidGarbage pyError=$pyError")

                        // v1.3.66: 拼出可直接显示到界面的诊断信息（含 PdfExtractor 内部诊断）
                        var pdfDiag = buildString {
                            appendLine("【PDF诊断】")
                            appendLine("Python测试: ${pyDiag ?: "(未执行)"}")
                            appendLine("Kotlin提取: ${ktStats.fourth}字(fe=${ktStats.second},可靠=${ktRes.reliable})")
                            if (ktRes.diag.isNotEmpty()) appendLine("KT内部: ${ktRes.diag}")
                            appendLine("Python提取: ${if (pyOk) "${pyChars}字(fe=$pyFe)" else "失败"}")
                            if (!pyOk && pyError != null) appendLine("Python错误: $pyError")
                            appendLine("决策: ${if (usePython) "用Python" else "用Kotlin"}(pyOk=$pyOk)")
                        }.trimEnd()

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
                        // v1.3.92: 有字符但零中文 → CID/ToUnicode 解码失败的中文 PDF（如 Word 导出 PDF）
                        // 此类 PDF 的中文以 CID 编码存储，Kotlin 无法解码成 PUA/乱码被过滤后只剩英文碎片
                        val isFailedChinesePdf = bestChars > 20 && bestFe == 0 && bestChars < 500
                        // v1.5.68: 对齐桌面 extract_pdf 的 whole_poisoned 逻辑 —— 低字数密度（图片型/扫描件 PDF）
                        //   即使 pdfminer/PdfExtractor 已抽到少量文字，也必须强制全页 OCR。
                        //   桌面判定 avg_chars < 800 即 whole_poisoned。
                        //   注意：PdfExtractor 可能抽出大量 PDF 结构/CID 垃圾字符，导致 bestChars 虚高而
                        //   有效字数(bestWords) 极少，因此密度判断必须同时看有效字数，并使用可靠页数 realPages。
                        //   例：AH+.pdf 纯图片型 avg≈29 < 800；正确 27 页文件有效字数 315/27≈12 < 200。
                        val avgCharsPerPage = bestChars.toDouble() / maxOf(1, realPages)
                        val avgWordsPerPage = bestWords.toDouble() / maxOf(1, realPages)
                        val lowDensity = avgCharsPerPage < 800.0 || avgWordsPerPage < 200.0
                        val needOcr = bestChars < 10 || (!bestTextReliable && bestChars < 50) || looksLikeGarbage || isFailedChinesePdf || lowDensity
                        Log.d("WordCount", "PDF OCR决策 $dName: bestChars=$bestChars bestFe=$bestFe bestPages=$bestPages realPages=$realPages avgChars/p=$avgCharsPerPage avgWords/p=$avgWordsPerPage lowDensity=$lowDensity needOcr=$needOcr (garbage=$looksLikeGarbage failedCn=$isFailedChinesePdf)")
                        if (lowDensity) pdfDiag += "\nOCR触发: 低字数密度(avg ${"%.0f".format(avgWordsPerPage)}字/页<200)→按桌面口径强制全页OCR"

                        if (!needOcr) {
                            // ★ 文本提取足够好 → 直接使用
                            val resMap = mapOf(
                                "name" to dName, "ext" to ".pdf",
                                "stats" to mapOf("words" to bestWords, "fe" to bestFe, "nc" to bestNc, "chars" to bestChars),
                                "meta" to emptyMap<String, Any?>(),
                                "pages" to (if (realPages > 1) realPages else bestPages),
                                "diag" to pdfDiag,
                                "ocrNote" to "文本提取充分，未触发OCR"
                            )
                            val fr = toFileResult(resMap, f.absolutePath)
                            entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_pdf_ok", displayName = dName, cachePath = f.absolutePath, result = fr, rawResult = resMap))
                        } else {
                            // ★ 文本太少 → 尝试 OCR
                            // v1.3.81: 对"glyph-ID编码垃圾"(ktLooksLikeCidGarbage)使用PRINT模式+2x分辨率渲染
                            // 提升中文 PDF 的 OCR 识别率（普通 DISPLAY 模式对文字偏小的 PDF 渲染质量不足）
                            // v1.3.93: isFailedChinesePdf（Word 导出中文 PDF，CID 解码失败）也用 PRINT 高分辨率
                            // v1.5.66: lowDensity(图片/扫描型PDF) 不再强制 PRINT 模式——
                            //   部分 PDF 在 RENDER_MODE_FOR_PRINT 下会渲染成空白(isBlankBitmap 把整页
                            //   跳过→OCR返回空→降级到 Kotlin 的错结果/错误页数)。改用 DISPLAY 模式
                            //   (更兼容, 文本/图片 PDF 均可靠渲染)，仅保留 looksLikeGarbage/isFailedChinesePdf
                            //   的 PRINT 高分辨率(这两类确需更清晰渲染)。
                            val ocrForPrintMode = looksLikeGarbage || isFailedChinesePdf
                            val ocrRes = PdfOcrEngine.extractText(context, f, forPrintMode = ocrForPrintMode)

                            if (ocrRes != null) {
                                // OCR 成功
                                val ocrStats = countTextKotlin(ocrRes.text)
                                val resMap = mapOf(
                                    "name" to dName, "ext" to ".pdf",
                                    "stats" to mapOf("words" to ocrStats.first, "fe" to ocrStats.second, "nc" to ocrStats.third, "chars" to ocrStats.fourth),
                                    "meta" to emptyMap<String, Any?>(),
                                    "pages" to ocrRes.pages,
                                    "diag" to "$pdfDiag\n(OCR补充)",
                                    "ocrNote" to "已OCR扫描${ocrRes.pages}页"
                                )
                                val fr = toFileResult(resMap, f.absolutePath)
                                entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_pdf_ocr", displayName = dName, cachePath = f.absolutePath, result = fr, rawResult = resMap))
                            } else {
                                // 全部失败 → 显示最佳可用结果或错误
                                if (bestChars > 0) {
                                    // 有一些文本（虽然少）→ 降级使用
                                    val ocrDiag = PdfOcrEngine.lastDiag
                                    Log.w("WordCount", "PDF 降级(文本少+OCR失败): $dName best=${bestChars}ch ocrDiag=$ocrDiag")
                                    val resMap = mapOf(
                                        "name" to dName, "ext" to ".pdf",
                                        "stats" to mapOf("words" to bestWords, "fe" to bestFe, "nc" to bestNc, "chars" to bestChars),
                                        "meta" to emptyMap<String, Any?>(),
                                        "pages" to (if (realPages > 1) realPages else bestPages),
                                        "diag" to "$pdfDiag\n(降级:文本少+OCR失败)\nOCR详情: ${if (ocrDiag.isNotEmpty()) ocrDiag else "无"}",
                                        "ocrNote" to "⚠️ OCR未成功，已用文本层降级(详见诊断)"
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
                        // v1.2.3: extractDocFull 额外返回 SummaryInformation 的 words/chars 权威统计
                        val text: String
                        var docPages: Int = 0  // 0 = 未知
                        var docWords: Int = 0  // 0 = 无元数据
                        var docChars: Int = 0  // 0 = 无元数据
                        var hiddenText: List<Pair<String, String>> = emptyList() // v1.3.3: .xls 隐藏表
                        var xlsVisible: List<String> = emptyList() // v1.3.4: .xls 可见表名（明细展示用）
                        var pptNotes: List<SheetStat> = emptyList()  // v1.3.34: .ppt 备注列表
                        var pptImages: Int = 0                       // v1.3.34: .ppt 嵌入图片数
                        var xlsImages: Int = 0                       // v1.3.40: .xls 嵌入图片数
                        if (extLower == "doc") {
                            val docRes = OldOfficeEngine.extractDocFull(f)
                            text = docRes.text
                            docPages = docRes.pages
                            docWords = docRes.words
                            docChars = docRes.chars
                        } else if (extLower == "xls") {
                            // v1.3.3: .xls 逐表抽取，隐藏表单独返回
                            val xlsRes = OldOfficeEngine.extractXlsDetailed(f)
                            text = xlsRes.text
                            hiddenText = xlsRes.hiddenSheets
                            xlsVisible = xlsRes.visibleNames
                            xlsImages = xlsRes.imageCount  // v1.3.40: .xls 嵌入图片计数
                        } else {
                            // v1.3.34: .ppt 用 extractPptFull 获取文本+备注+图片（与 .pptx 对齐）
                            val pptRes = OldOfficeEngine.extractPptFull(f)
                            text = pptRes.text
                            docPages = pptRes.pages
                            // v1.3.36 修复：直接赋值给外层 var，不能用 val（否则遮蔽外层变量，
                            // 导致 resMap 写入的是初始空值——与 v1.3.33 imageCount bug 同因）
                            pptNotes = pptRes.notesSlides
                            pptImages = pptRes.imageCount
                        }
                        if (text.isBlank()) {
                            entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_o", displayName = dName, cachePath = f.absolutePath, error = "此老格式文件内容为空或无法读取"))
                        } else {
                            val stats = countTextKotlin(text)
                            val extDot = ".$extLower"
                            // 构造 pages：DOC 有元数据页数就用，否则留 null 让 toFileResult 走 estimatePages 兜底
                            val pagesValue = if (docPages > 0) docPages else null
                            // v1.2.3: 优先用 SummaryInformation 的权威统计（与 Word 完全一致）
                            val outWords: Int
                            val outFe: Int
                            val outNc: Int
                            val outChars: Int
                            if (docWords > 0) {
                                outNc = stats.third
                                outFe = maxOf(0, docWords - outNc)
                                outWords = docWords
                                outChars = if (docChars > 0) docChars else stats.fourth
                                Log.d("WordCount", "doc 用元数据: words=$docWords chars=$docChars pages=$docPages")
                            } else {
                                outWords = stats.first
                                outFe = stats.second
                                outNc = stats.third
                                outChars = stats.fourth
                            }
                            // v1.3.3: 隐藏工作表单独统计（默认不计入合计，UI 勾选后才并入）
                            val hiddenStats = hiddenText.map { (n, t) ->
                                val s = countTextKotlin(t)
                                SheetStat(n, s.first, s.second, s.third, s.fourth)
                            }
                            val resMap = mutableMapOf<String, Any?>(
                                "name" to dName, "ext" to extDot,
                                "stats" to mapOf("words" to outWords, "fe" to outFe, "nc" to outNc, "chars" to outChars),
                                "meta" to mapOf(
                                    "sheets" to xlsVisible, "hidden_sheets" to hiddenStats,
                                    "notes_slides" to pptNotes, "image_count" to (pptImages + xlsImages)
                                )
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

                // DWG(CAD)：v1.5.11 原始二进制扫描为主路径 + "CAD 转 PDF 统计"回退分支
                //   主路径：scanDwgRaw() 直接扫字节（移植自 port_dwg.py improved 模式）
                //   回退：电脑端 wordcount 已验证——某些 CAD 文字被压缩/特殊编码/代理对象，
                //         raw scan 拿不到正确字数，必须先导出 PDF 再从 PDF 文本层提取。
                //         LibreDWG 导出的 PDF 文字是可选中文本层（BT/Tj 操作符），提取即可统计，
                //         图形乱不影响字数统计。全程免费、Kotlin 原生（PdfExtractor），无 Python 依赖。
                dwgFiles.forEachIndexed { i, cf ->
                    val f = cf.file
                    val dName = cf.displayName
                    try {
                        // ── 兜底层：原始二进制扫描（保留为回退） ──
                        val rawText = scanDwgRaw(f.absolutePath)
                        var finalStats = countTextKotlin(rawText)
                        var finalText = rawText
                        val rawChars = finalStats.fourth
                        val rawFeRatio = if (rawChars > 0) finalStats.second.toDouble() / rawChars else 0.0

                        // ── 主路径（v1.5.16）：dwg→dxf 结构化文字抽取 + 图框页数 ──
                        //   端口桌面 extract_text_custom + count_cad_frames：dwg2dxf 由隔离进程执行
                        //   （native 崩溃只杀隔离进程，不闪退主 app），dxf 由 Kotlin 直接解析
                        //   TEXT/ATTDEF/MTEXT/MULTILEADER 实体（无需 ezdxf）。
                        var dxfPages: Int? = null
                        var dxfPagesReason: String? = null
                        var dxfMojibake = false
                        var dxfText = ""
                        // v1.5.40: 捕获 DXF 编码诊断，供结果透传
                        var dxfDiag = ""
                        // v1.5.33: 是否已按「出图口径」统计（对齐桌面 meta["printed_scope"]）
                        var printedScope = false
                        val dxfPath = "${f.parent}/${f.nameWithoutExtension}.dxf"
                        val dxfRes = DwgIsolatedRunner.convertToDxf(context, f.absolutePath, dxfPath)
                        if (dxfRes.path != null) {
                            val dxfFile = File(dxfPath)
                            if (dxfFile.exists() && dxfFile.length() > 0) {
                                val analysis = DwgDxfParser.analyze(dxfPath)
                                dxfText = analysis.text
                                dxfDiag = analysis.diag
                                var dxfStats = countTextKotlin(dxfText)
                                dxfPages = analysis.frames
                                dxfPagesReason = analysis.framesReason

                                // ── v1.5.33: 出图口径裁剪（端口桌面 _extract_dwg_via_ezdxf_printed）──
                                //   桌面版在「字数密度异常高」时改用 ezdxf 只读模型空间+各布局
                                //   实际画出的文字，排除未被任何 INSERT 引用的块定义
                                //   （图库残留、不出图的结构设计说明模板）。
                                //   巴布亚桩基：全量 59597 字 / 11 页 = 5418 字/页（远超正常
                                //   1500-3000），改用出图口径后 23932 字，桌面基准 23960。
                                //   触发条件与桌面 extract_cad:3466 的 density 判据一致，
                                //   给排水(1063)/水雾(278)/Tenova(512) 密度正常，完全不受影响。
                                if (analysis.printedText.isNotEmpty() && dxfPages != null && dxfPages!! > 0) {
                                    val allWords = dxfStats.second + dxfStats.third
                                    val allDensity = allWords.toDouble() / dxfPages!!
                                    if (allDensity > 3000.0 && allWords > dxfPages!! * 1000) {
                                        var pCjk = 0; var pCommon = 0
                                        val pSet = HashSet<Char>()
                                        for (ch in analysis.printedText) {
                                            val cp = ch.code
                                            if (cp in 0x4E00..0x9FFF) {
                                                pCjk++; pSet.add(ch)
                                                if (cp in DwgRawCjkScanner.COMMON_CJK_CHARS) pCommon++
                                            }
                                        }
                                        val pCr = if (pCjk > 0) pCommon.toDouble() / pCjk else 0.0
                                        val pDv = if (pCjk > 0) pSet.size.toDouble() / pCjk else 1.0
                                        val pStats = countTextKotlin(analysis.printedText)
                                        val pWords = pStats.second + pStats.third
                                        // 质量门（对齐桌面 3517-3545）：足量中文 + 常用字占比够高
                                        // + 多样性够低（排除「二进制巧合」式假中文），且确实是裁剪而非放大
                                        if (pCjk >= 200 && pCr >= 0.30 && pDv < 0.60 && pWords in 1 until allWords) {
                                            dxfText = analysis.printedText
                                            dxfStats = pStats
                                            printedScope = true
                                            dxfPagesReason = "${dxfPagesReason ?: ""}·出图口径统计"
                                            Log.d("WordCount", "DWG 出图口径 $dName: 全量=$allWords(密度${"%.0f".format(allDensity)}) → 出图=$pWords cjk=$pCjk cr=${"%.2f".format(pCr)} dv=${"%.2f".format(pDv)}")
                                        } else {
                                            Log.d("WordCount", "DWG 出图口径 REJECTED $dName: pWords=$pWords all=$allWords cjk=$pCjk cr=${"%.2f".format(pCr)} dv=${"%.2f".format(pDv)}")
                                        }
                                    }
                                }
                                // ── v1.5.24: DXF mojibake 检测（巴布亚桩基场景）──
                                //   LibreDWG 把 GBK 中文误作 Latin-1 解码 → DXF 文本含大量
                                //   随机 CJK（mojibake）。判据：DXF 含大量 CJK 但常用字占比极低
                                //   + 密度远超正常 DWG（>2500 字/页）。命中则弃用 DXF 结果，
                                //   改用 raw 扫描作为基线并触发后续 PDF/GBK 恢复。
                                var dxfCjkCount = 0; var dxfRealCjk = 0
                                for (ch in dxfText) {
                                    val cp = ch.code
                                    if (cp in 0x4E00..0x9FFF) { dxfCjkCount++; if (cp in DwgRawCjkScanner.COMMON_CJK_CHARS) dxfRealCjk++ }
                                }
                                val dxfCommonRatio = if (dxfCjkCount > 0) dxfRealCjk.toDouble() / dxfCjkCount else 0.0
                                val dxfDensity = dxfStats.fourth.toDouble() / maxOf(dxfPages ?: 1, 1)
                                dxfMojibake = (dxfCjkCount >= 200) && (dxfCommonRatio < 0.05) && (dxfDensity > 2500)
                                // 仅当 DXF 非 mojibake 且字数更丰富时才采用 DXF 结果
                                // v1.5.33: 出图口径是「主动裁剪」，字符数必然少于 raw 扫描，
                                //   不能被 `>= rawChars` 这道门挡掉（桌面版同样是直接替换 items）。
                                // v1.5.51: raw 扫描会包含二进制里的 ASCII 垃圾，字符数虽多但质量差；
                                //   当 DXF 抽到足量且常用字占比正常的中文时，应优先采用 DXF，
                                //   避免高质量 DXF 被 raw 字数门错误地挡掉（给排水_t3 实测）。
                                // v1.5.52: 降低高质量 DXF 判定阈值。v1.5.51 的 >=500 中文把
                                //   Tenova 这类单页小图（真机约 239 CJK）误挡在 PDF 提示里；
                                //   桌面版 Tenova 本就直接出数。改为 >=50 中文且常用字占比 >=30%
                                //   即视为可信任的 DXF 文本，直接采用。
                                val dxfQualityGood = (dxfCjkCount >= 50) && (dxfCommonRatio >= 0.30) && (dxfCommonRatio < 0.98)
                                if (printedScope || (!dxfMojibake && (dxfStats.fourth >= rawChars || dxfQualityGood))) {
                                    finalStats = dxfStats
                                    finalText = dxfText
                                }
                                Log.d("WordCount", "DWG dxf $dName: enc=${analysis.decodeMode} raw=$rawChars dxf=${dxfStats.fourth} cjk=$dxfCjkCount cr=${"%.3f".format(dxfCommonRatio)} den=${"%.0f".format(dxfDensity)} moji=$dxfMojibake pages=$dxfPages($dxfPagesReason)")
                            }
                        }

                        // ── v1.5.29: 编码丢失检测 + GBK/UTF-16 原始字节 CJK 恢复 ──
                        //   【关键修正】对齐桌面版 wordcount.py:3432 的 `isinstance(frames, int)` 守卫：
                        //     桌面版：frames 不是 int 时（图框检测失败），整个编码丢失+恢复块被跳过，
                        //            直接使用 DXF 结果。Android 旧代码用 `dxfPages ?: 1` 把 null 当 1，
                        //            导致 density=14874/1=14874>>3000，错误触发恢复路径。
                        //     修复：仅当 dxfPages 为已知正整数时才计算 density 触发恢复；
                        //            dxfMojibake 始终触发（与 frames 无关）。
                        val hasValidFrames = (dxfPages != null) && (dxfPages!! > 0)
                        val framesForDensity = if (hasValidFrames) dxfPages!! else 1  // 仅用于密度计算
                        var curTotal = finalStats.second + finalStats.third  // fe + nc
                        val density = curTotal.toDouble() / maxOf(framesForDensity, 1)
                        // 统计当前结果中的 CJK 质量指标
                        var itemsCjk = 0; var realCjk = 0
                        val textForCjkCheck = if (dxfText.isNotEmpty()) dxfText else rawText
                        for (ch in textForCjkCheck) { val cp = ch.code; if (cp in 0x4E00..0x9FFF) { itemsCjk++; if (cp in DwgRawCjkScanner.COMMON_CJK_CHARS) realCjk++ } }
                        val garbled = (itemsCjk >= 50) && (realCjk.toDouble() / maxOf(itemsCjk, 1) < 0.05)
                        val curCjkRatio = if (curTotal > 0) itemsCjk.toDouble() / curTotal else 0.0
                        val zeroCjkLoss = (curTotal >= 500) && (itemsCjk <= 5) && (curCjkRatio < 0.01)
                        var sparse = false
                        var cjkInRaw = 0
                        try {
                            val dwgRawBytes = File(f.absolutePath).readBytes()
                            try {
                                val decoded = String(dwgRawBytes, charset("GB18030"))
                                for (c in decoded) { if (c.code in 0x4E00..0x9FFF) cjkInRaw++ }
                            } catch (_: Exception) {}
                            sparse = (cjkInRaw > 50000) && (realCjk.toDouble() / maxOf(framesForDensity, 1) < 50.0)
                        } catch (_: Exception) {}
                        val encodingLoss = zeroCjkLoss || garbled || sparse
                        // v1.5.29: density 条件仅在 hasValidFrames 时生效（对齐桌面 isinstance(frames,int)）
                        //           mojibake 和 encodingLoss 始终生效（与 frames 无关的独立判定）
                        val densityTrigger = hasValidFrames && (density > 3000.0 && curTotal > framesForDensity * 1000)
                        val needsRecovery = densityTrigger || encodingLoss || dxfMojibake
                        var recoverySucceeded = false  // v1.5.26: 追踪恢复是否成功
                        if (needsRecovery) {
                            val recovered = DwgRawCjkScanner.scanRawDwg(f.absolutePath)
                            Log.d("WordCount", "DWG CJK recovery $dName: method=${recovered.method} cjk=${recovered.cjkTotal} div=${"%.3f".format(recovered.cjkDiversity)} cr=${"%.3f".format(recovered.commonRatio)}")
                            // v1.5.21 安全门：防止字节扫描器覆盖已合理的 DXF 结果（桌面:3552 逻辑）
                            // v1.5.26: 当 dxfMojibake 时放松安全门（DXF 已确认不可信，恢复结果更可信）
                            val mayReplace = dxfMojibake ||
                                    DwgRawCjkScanner.shouldReplaceDxfResult(finalStats.fourth, itemsCjk, recovered)
                            if (mayReplace && recovered.text.isNotEmpty()) {
                                val recStats = countTextKotlin(recovered.text)
                                // v1.5.26: 当 dxfMojibake 时放宽膨胀限制（从 3.5x → 8x），因为 DXF 基线本身不可信
                                val effectiveMaxRatio = if (dxfMojibake) 8.0 else DwgRawCjkScanner.MAX_REPLACE_RATIO
                                // v1.5.39: 当 DXF 抽到的字符数极少（<=50，基本是空结果）时，膨胀检查
                                //   不应以 0 为基数（会让任何恢复都被拒）。改用恢复结果自身为基数，
                                //   此时是否采用完全由 recovered 质量门（mayReplace/shouldReplaceDxfResult）决定。
                                val base = if (finalStats.fourth <= 50) recStats.fourth else finalStats.fourth
                                val limit = (base * effectiveMaxRatio).toInt().coerceAtLeast(100)
                                if (recStats.fourth <= limit) {
                                    finalStats = recStats
                                    finalText = recovered.text
                                    dxfPagesReason = "${recovered.method}字节扫描恢复"
                                    recoverySucceeded = true
                                    // v1.5.26: 恢复成功后更新 curTotal 用于后续 invalid 判定
                                    curTotal = finalStats.second + finalStats.third
                                    Log.d("WordCount", "DWG CJK recovery APPLIED $dName: now=${recStats.fourth} fe=${recStats.second}")
                                } else {
                                    Log.w("WordCount", "DWG CJK recovery REJECTED (oversize) $dName")
                                }
                            } else {
                                Log.d("WordCount", "DWG CJK recovery SKIPPED (safety gate) $dName")
                            }
                        }

                        // v1.5.61: 终极兜底——直接对原始 DWG 字节做 GBK/UTF-16LE 扫描。
                        // 水雾电气图-7区在真机上出现「DXF 编码丢失 + 常规 recovery 未触发」
                        // 导致字数显示为 0 的情况；但原始 DWG 字节里 UTF-16LE 中文完整存在。
                        // 当现有结果中文极少（<=5）时直接尝试 DwgRawCjkScanner，质量可信就采用。
                        // v1.5.62: 放宽采用门槛，不再要求 commonRatio（CAD 专业图常用字占比低）。
                        if (!recoverySucceeded && finalStats.second <= 5) {
                            val rawScanner = DwgRawCjkScanner.scanRawDwg(f.absolutePath)
                            if (rawScanner.cjkTotal >= 200 && rawScanner.cjkDiversity < 0.6 &&
                                rawScanner.text.isNotEmpty()) {
                                val rs = countTextKotlin(rawScanner.text)
                                finalStats = rs
                                finalText = rawScanner.text
                                dxfPagesReason = "${rawScanner.method}原始字节扫描"
                                recoverySucceeded = true
                                curTotal = finalStats.second + finalStats.third
                                Log.d("WordCount", "DWG raw scanner APPLIED $dName: method=${rawScanner.method} cjk=${rawScanner.cjkTotal} common=${"%.2f".format(rawScanner.commonRatio)} words=${rs.first}")
                            } else {
                                Log.d("WordCount", "DWG raw scanner SKIPPED/REJECTED $dName: cjk=${rawScanner.cjkTotal} common=${"%.2f".format(rawScanner.commonRatio)} div=${"%.2f".format(rawScanner.cjkDiversity)}")
                            }
                        }

                        // ── 回退：raw+dxf+recovery 都疑似无效时，转 PDF 再从 PDF 文本层提取 ──
                        // v1.5.31: 对齐桌面 extract_cad:3796 的 PDF 兜底触发条件：
                        //   当 recovery 未成功，且总字数 < 图框数×1000 时，尝试把 DWG 自己渲染成 PDF
                        //   再走 PDF 文字层提取。这样给排水_t3 这类「矢量文字被抽空/栅格化」文件
                        //   能拿到 PDF 里的真实字数，而不是停留在 5140/0。
                        //   同时保留 v1.5.28 的保险：CJK 恢复成功后完全跳过 PDF 兜底，避免覆盖好结果。
                        //   v1.5.32: 补齐桌面 `isinstance(frames, int) and frames >= 1` 守卫——
                        //   页数未知时（dxfPages == null）桌面根本不进这个兜底块，
                        //   旧代码用 `dxfPages ?: 1` 把 null 当 1 页，会把「页数都数不出来」
                        //   的文件也一律拖去渲染 PDF。
                        val framesKnown = (dxfPages != null) && (dxfPages!! >= 1)
                        val framesVal4 = if (framesKnown) dxfPages!! else 1
                        val finalWords4 = finalStats.second + finalStats.third
                        val charsNow = finalStats.fourth
                        val feRatioNow = if (charsNow > 0) finalStats.second.toDouble() / charsNow else 0.0
                        val cjkNow = finalStats.second
                        //   v1.5.35: 移除 v1.5.33 的 hasTrustedChinese 硬守卫。
                        //     桌面版 extract_cad:3788-3799 的 PDF 兜底触发条件只有
                        //     `frames>=1 && not printed_scope && not encoder_garbled && words < frames*1000`，
                        //     并不检查当前是否已有可信中文。Android 若因为 DXF 已抽到中文就跳过
                        //     PDF 兜底，会错失 dwg2pdf 能渲染出的栅格化文字（如水雾电气图）。
                        //     是否采用 PDF 结果仍由下面的 pdfBetter 门把守：PDF 中文占比必须
                        //     明显更高、或当前接近零中文、或当前字符极稀疏，才会替换。
                        val rasterizedTrigger = framesKnown
                                && !recoverySucceeded
                                && !printedScope
                                && (finalWords4 < framesVal4 * 1000)
                                // v1.5.38: 仅多页图纸（>=3 页）才按字数/页框比判断栅格化。
                                //   单/双页 DWG（如 Tenova 1页512字）本就字数少，按 words<frames*1000
                                //   会误报；全局编码修复后 Tenova 应能直接出 512/295，不应提示转 PDF。
                                && (framesVal4 >= 3)
                        // v1.5.38: 中文全部丢失的保险触发——只要最终中文≤5 且非中文≥100，并且
                        //   原始 DWG 按 GB18030 扫描能找到中文（cjkInRaw>=100），就说明该文件
                        //   其实有中文、是 DXF 编码路径把中文弄丢了，需要文字型 PDF 重新统计。
                        //   全局编码修复生效后给排水_t3 会直接出 14879/13698（中文不丢），不会触发。
                        val cjkLostTrigger = framesKnown
                                && !recoverySucceeded
                                && (finalStats.second <= 5)
                                && (finalStats.third >= 100)
                                && (cjkInRaw >= 100)
                        // v1.5.36: 取消自动 dwg2pdf 自渲染兜底——
                        //   LibreDWG 渲染出的 PDF 文字层是栅格化图像，PdfExtractor 抽不到多少字，
                        //   对统计无益且用户反馈"取不全完全没有用"。改为：当 DWG 统计明显偏少
                        //   （栅格化/稀疏）时置 needsPdf，由 UI 提示用户在手机上选一份文字型 PDF
                        //   来重新统计（见 onPickPdf / pdfPicker），不再自动渲染无用的 PDF。
                        val needsPdf = rasterizedTrigger || cjkLostTrigger || (charsNow < 50)
                        // ── v1.5.31: 栅格化检测仍然记录日志（PDF 兜底已按桌面条件触发）──
                        val rasterized4 = framesKnown && (finalWords4 < framesVal4 * 1000)
                        if (rasterized4) {
                            Log.d("WordCount", "DWG rasterized $dName: words=$finalWords4 frames=$framesVal4 recovery=$recoverySucceeded")
                        }

                        val pages = dxfPages ?: estimatePages(finalStats.fourth)
                        // v1.5.61/62: 对 DWG 最终文字拆分文字部分 / 纯编号部分，供展开后勾选汇总。
                        // 即使 needsPdf=true，只要已经拿到文字也拆分，方便用户查看“已有统计”。
                        val cadParts = if (finalText.isNotBlank()) computeCadParts(finalText) else null
                        val cadPartsMeta = cadParts?.let { mapOf(
                            "text_words" to it.textWords, "text_fe" to it.textFe, "text_nc" to it.textNc, "text_chars" to it.textChars,
                            "code_words" to it.codeWords, "code_fe" to it.codeFe, "code_nc" to it.codeNc, "code_chars" to it.codeChars,
                            "text_items" to it.textItems, "code_items" to it.codeItems
                        ) }
                        val resMap = mapOf(
                            "name" to dName, "ext" to ".dwg",
                            "stats" to mapOf("words" to finalStats.first, "fe" to finalStats.second, "nc" to finalStats.third, "chars" to finalStats.fourth),
                            "meta" to mapOf<String, Any?>("pages_reason" to (dxfPagesReason ?: ""), "needs_pdf" to needsPdf,
                                "cad_parts" to cadPartsMeta),
                            "pages" to pages,
                            // v1.5.40: 把 DXF 编码诊断透传出去，便于定位真机仍 0 中文的原因
                            "diag" to dxfDiag
                        )
                        val fr = toFileResult(resMap, f.absolutePath)
                        entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_w", displayName = dName, cachePath = f.absolutePath, result = fr, rawResult = resMap))
                    } catch (e: Throwable) {
                        Log.w("WordCount", "DWG 扫描失败 ${f.name}: ${e.message}")
                        entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_w", displayName = dName, cachePath = f.absolutePath, error = "无法统计.dwg文件（${e.message}）"))
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

// v1.5.66: 可靠的 PDF 页数（绕过 Kotlin PdfExtractor.countPagesSafe 对 ObjStm 压缩流
//   PDF 误判成 1 页的 bug）。直接用系统 PdfRenderer.pageCount（与 PdfOcrEngine OCR 分支
//   同源的可靠页数来源），失败返回 0 由上层回退 bestPages。
private fun reliablePdfPageCount(file: File): Int {
    return try {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val r = PdfRenderer(pfd)
        try { r.pageCount } finally { r.close(); runCatching { pfd.close() } }
    } catch (_: Throwable) { 0 }
}

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
    // v1.3.33: OOXML 嵌入图片数量（来自 meta["image_count"]），供 hasUnreliable 判断
    val imageCount = (meta["image_count"] as? Number)?.toInt() ?: 0
    // v1.5.61: CAD 文字/纯编号拆分
    val cadPartsMap = meta["cad_parts"] as? Map<*, *>
    val cadParts = cadPartsMap?.let {
        CadPartStats(
            textWords = (it["text_words"] as? Number)?.toInt() ?: 0,
            textFe = (it["text_fe"] as? Number)?.toInt() ?: 0,
            textNc = (it["text_nc"] as? Number)?.toInt() ?: 0,
            textChars = (it["text_chars"] as? Number)?.toInt() ?: 0,
            codeWords = (it["code_words"] as? Number)?.toInt() ?: 0,
            codeFe = (it["code_fe"] as? Number)?.toInt() ?: 0,
            codeNc = (it["code_nc"] as? Number)?.toInt() ?: 0,
            codeChars = (it["code_chars"] as? Number)?.toInt() ?: 0,
            textItems = (it["text_items"] as? Number)?.toInt() ?: 0,
            codeItems = (it["code_items"] as? Number)?.toInt() ?: 0
        )
    }
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
        hiddenSheets = (meta["hidden_sheets"] as? List<*>)?.mapNotNull { it as? SheetStat } ?: emptyList(),
        // v1.3.32: PPT 备注幻灯片
        notesSlides = (meta["notes_slides"] as? List<*>)?.mapNotNull { it as? SheetStat } ?: emptyList(),
        // v1.3.32: PPT 嵌入图片数量
        imageCount = imageCount,
        // v1.3.32: 文件内部标题
        internalTitle = (meta["internal_title"] as? String) ?: "",
        inner = inner,
        // v1.3.39: 仅 Office 文档（pptx/ppt/docx/xlsx/xls）含嵌入图片时才显示"导出未统计图片"按钮
        // 图片型 PDF 不再触发导出按钮（PDF 无法像 OOXML 那样解压提取内嵌图片）
        hasUnreliable = imageCount > 0,
        // v1.3.64: PDF 诊断信息（来自 resMap["diag"]）
        diag = m?.get("diag") as? String,
        // v1.5.66: PDF 的 OCR 状态摘要（来自 resMap["ocrNote"]）
        ocrNote = m?.get("ocrNote") as? String,
        // v1.5.36: DWG 统计不准、需文字型 PDF 重新统计时由扫描分支置 true
        needsPdf = (meta["needs_pdf"] as? Boolean) ?: false,
        // v1.5.61: CAD 文字/纯编号拆分
        cadParts = cadParts
    )
}

private fun exportUnreliable(
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    snackbar: SnackbarHostState,
    entries: List<FileEntry>,
    onStateChange: (Boolean) -> Unit = {}
) {
    scope.launch(Dispatchers.Main) {
        onStateChange(true)
        try {
            val sel = entries.filter { it.selected && it.result?.hasUnreliable == true && it.cachePath.isNotBlank() }
            if (sel.isEmpty()) { snackbar.showSnackbar("没有可导出的未统计图片"); return@launch }

            val out = File(context.getExternalFilesDir(null), "无法准确统计内容_${System.currentTimeMillis()}.pdf")
            var totalCount = 0
            withContext(Dispatchers.IO) {
                // v1.3.35: 纯 Kotlin 实现（不依赖 Python/Chaquopy，彻底避免 AssetFinder 报错）
                totalCount = buildExportPdfKotlin(sel, out.absolutePath)
            }
            if (totalCount > 0) {
                val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", out)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/pdf")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "打开导出的 PDF"))
            } else {
                snackbar.showSnackbar("无可导出内容")
                out.delete()
            }
        } catch (e: Exception) {
            snackbar.showSnackbar("导出失败：${e.message}")
        } finally {
            onStateChange(false)
        }
    }
}

/** v1.3.35: 纯 Kotlin 实现 PDF 导出——从 OOXML 包抽取嵌入图片，每张图片一页 PDF。
 *  返回成功写入的页数。 */
private fun buildExportPdfKotlin(entries: List<FileEntry>, outPath: String): Int {
    val pdf = PdfDocument()
    var totalPages = 0
    val pageW = 595
    val pageH = 842
    val maxImgW = pageW - 40
    val headerH = 36f
    val headerPaint = android.graphics.Paint().apply {
        textSize = 12f; isAntiAlias = true; color = android.graphics.Color.BLACK
    }
    val mediaExts = setOf(".png", ".jpg", ".jpeg", ".gif", ".bmp", ".tif", ".tiff", ".webp")

    for (entry in entries) {
        val srcPath = entry.cachePath
        val ext = entry.result?.ext ?: ""
        val name = entry.result?.name ?: entry.displayName
        val words = entry.result?.words ?: 0
        val fe = entry.result?.fe ?: 0
        val nc = entry.result?.nc ?: 0
        val imageCount = entry.result?.imageCount ?: 0

        if (imageCount <= 0 && ext != ".pdf") continue

        val imgEntries = mutableListOf<Pair<String, String>>()

        // v1.3.38/v1.3.40: 分三种格式——OOXML(ZIP)、OLE2(.ppt)、OLE2(.xls)
        // .xls 也是 OLE2 格式（非 ZIP），ZipFile 会静默失败，需用 POI HSSF 提取
        if (ext in setOf(".pptx", ".docx", ".xlsx")) {
            try {
                val zip = ZipFile(srcPath)
                val zipEntries = java.util.Collections.list(zip.entries())
                    .filter { ze ->
                        val nm = ze.name.lowercase()
                        (("/media/" in nm || nm.startsWith("media/")) &&
                                !nm.endsWith("/") && mediaExts.any { nm.endsWith(it) })
                    }
                    .sortedBy { it.name }
                var n = 0
                for (ze in zipEntries) {
                    n++
                    val tmp = File(
                        System.getProperty("java.io.tmpdir"),
                        "wc_export_${System.currentTimeMillis()}_$n${ze.name.substringAfterLast('.')}"
                    )
                    try {
                        zip.getInputStream(ze).use { input ->
                            tmp.outputStream().use { output -> input.copyTo(output) }
                        }
                        imgEntries.add(tmp.absolutePath to "$name - 图片 $n")
                    } catch (_: Exception) {}
                }
                zip.close()
            } catch (_: Exception) {}
        } else if (ext == ".ppt") {
            // .ppt 是 OLE2 格式，需用 POI HSLF 提取嵌入图片（与 extractPptFull 统计 imageCount 对应）
            try {
                val fis = java.io.FileInputStream(srcPath)
                val ppt = org.apache.poi.hslf.usermodel.HSLFSlideShow(fis)
                var n = 0
                for (pd in ppt.pictureData) {
                    n++
                    // v1.3.38: 用图片数据魔数(magic number)判断真实格式，不依赖 POI 枚举命名
                    // JPEG: FF D8 FF ; PNG: 89 50 4E 47 ; 其余(EMF/WMF 矢量)跳过无法在 PDF 显示
                    val data = pd.data ?: continue
                    val ext2 = when {
                        data.size >= 3 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() && data[2] == 0xFF.toByte() -> "jpg"
                        data.size >= 4 && data[0] == 0x89.toByte() && data[1] == 0x50.toByte() && data[2] == 0x4E.toByte() && data[3] == 0x47.toByte() -> "png"
                        else -> null
                    } ?: continue
                    val tmp = File(
                        System.getProperty("java.io.tmpdir"),
                        "wc_export_${System.currentTimeMillis()}_$n.$ext2"
                    )
                    try {
                        tmp.outputStream().use { it.write(data) }
                        imgEntries.add(tmp.absolutePath to "$name - 图片 $n")
                    } catch (_: Exception) {}
                }
                ppt.close()
                fis.close()
            } catch (_: Exception) {}
        } else if (ext == ".xls") {
            // v1.3.44: .xls 用工作簿级 getAllPictures() 提取（覆盖全部嵌入图片，不限于绘图层）
            try {
                val fis = java.io.FileInputStream(srcPath)
                val wb = org.apache.poi.hssf.usermodel.HSSFWorkbook(fis)
                var n = 0
                for (picData in wb.allPictures) {
                    val data = picData.data
                    n++
                    val ext2 = when {
                        data.size >= 3 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() && data[2] == 0xFF.toByte() -> "jpg"
                        data.size >= 4 && data[0] == 0x89.toByte() && data[1] == 0x50.toByte() && data[2] == 0x4E.toByte() && data[3] == 0x47.toByte() -> "png"
                        else -> null
                    } ?: continue
                    val tmp = File(
                        System.getProperty("java.io.tmpdir"),
                        "wc_export_${System.currentTimeMillis()}_$n.$ext2"
                    )
                    try {
                        tmp.outputStream().use { it.write(data) }
                        imgEntries.add(tmp.absolutePath to "$name - 图片 $n")
                    } catch (_: Exception) {}
                }
                wb.close()
                fis.close()
            } catch (_: Exception) {}
        }

        for ((imgPath, label) in imgEntries) {
            val opts = BitmapFactory.Options()
            opts.inJustDecodeBounds = true
            BitmapFactory.decodeFile(imgPath, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                try { File(imgPath).delete() } catch (_: Exception) {}
                continue
            }

            val scale = minOf(maxImgW.toFloat() / opts.outWidth, 1f)
            val dw = (opts.outWidth * scale).toInt()
            val dh = (opts.outHeight * scale).toInt()
            val canvasH = (headerH + dh + 4).toInt()

            val pageInfo = PdfDocument.PageInfo.Builder(pageW, maxOf(pageH, canvasH), totalPages + 1).create()
            val page = pdf.startPage(pageInfo)
            val canvas = page.canvas

            val headerText = "$name  |  $label  |  字数$words 中文$fe 非中文$nc"
            canvas.drawText(headerText, 10f, 20f, headerPaint)

            val bmpOpts = BitmapFactory.Options()
            bmpOpts.inSampleSize = if (scale < 0.5f) 2 else 1
            val bmp = BitmapFactory.decodeFile(imgPath, bmpOpts)
            if (bmp != null) {
                val left = ((pageW - dw) / 2f).coerceAtLeast(0f)
                canvas.drawBitmap(bmp, null, android.graphics.RectF(left, headerH + 2, left + dw, headerH + 2 + dh), null)
                bmp.recycle()
            }

            pdf.finishPage(page)
            totalPages++
            try { File(imgPath).delete() } catch (_: Exception) {}
        }
    }

    if (totalPages > 0) {
        pdf.writeTo(FileOutputStream(outPath))
    }
    pdf.close()
    return totalPages
}

// ═══════════════════════════════════════════════════════════════════════════
// v1.1.1: 文档比较界面（仿 Word「审阅 → 比较」）
// ═══════════════════════════════════════════════════════════════════════════

// ═══════════════════════════════════════════════════════════════════════════
// v1.1.1: 文档比较界面（仿 Word「审阅 → 比较」）
// ═══════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    var imgPath by remember { mutableStateOf<String?>(null) }   // 长图 PNG 路径（与 outPath 对应）
    var rendering by remember { mutableStateOf(false) }          // 正生成/打开长图

    // 比较设置（对应 Word 比较对话框）
    var optCase by remember { mutableStateOf(true) }   // 大小写更改
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
                    put("level", "word")   // v1.3.9: 固��字词级别（字符级别已移除，两者结果一致）
                    put("case", optCase)
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
                        imgPath = res.outputPath?.removeSuffix(".docx")?.plus(".png")
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
                HorizontalDivider()
                // v1.3.14: 固定2列网格布局，保证选择框纵向完美对齐
                // （FlowRow+IntrinsicSize.Max在v1.3.13仍无法对齐，改用显式Row网格）
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CompareCheck("大小写更改", optCase) { optCase = it }
                        CompareCheck("表格", optTable) { optTable = it }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CompareCheck("页眉和页脚", optHf) { optHf = it }
                        CompareCheck("脚注和尾注", optFn) { optFn = it }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CompareCheck("文本框", optTb) { optTb = it }
                        CompareCheck("域", optField) { optField = it }
                    }
                }
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("修改涉及的句子总字数：", fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.titleMedium)
                        Text("$modChars", fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.titleLarge)
                        Text(" 字/词", fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.titleMedium)
                    }
                    Text("插入 $ins 处 ｜ 删除 $del 处 ｜ 修改 $rep 处", color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val docx = outPath
                            val png = imgPath
                            if (docx != null && png != null && !rendering) {
                                rendering = true
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) {
                                        DocxImageRenderer.render(docx, png)
                                    }
                                    withContext(Dispatchers.Main) {
                                        rendering = false
                                        if (ok) openImageFile(context, png)
                                        else snackbar.showSnackbar("生成长图失败，可改用「分享Word」")
                                    }
                                }
                            }
                        }, modifier = Modifier.weight(1f), enabled = !rendering) {
                            Text(if (rendering) "生成中…" else "打开结果")
                        }
                        OutlinedButton(onClick = {
                            outPath?.let { shareDocxFile(context, it, docxMime) }
                        }, modifier = Modifier.weight(1f)) { Text("分享Word") }
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
    Row(verticalAlignment = Alignment.CenterVertically) {
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

/** 打开比对结果的长图 PNG（把 WORD 截成长图后用图片查看器打开）。 */
private fun openImageFile(context: android.content.Context, path: String) {
    try {
        val file = File(path)
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "image/png")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "打开长图"))
    } catch (e: Throwable) {
        Log.w("WordCount", "打开长图失败: ${e.message}")
    }
}

/** 分享比对结果 WORD（仍分享 .docx，内容不变）。 */
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
