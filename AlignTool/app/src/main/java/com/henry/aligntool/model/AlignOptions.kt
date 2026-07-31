package com.henry.aligntool.model

/**
 * 手机版 AlignTool 选项，与桌面版 GUI（align_core.run_align :1456 的推导）对齐。
 *
 * - exportBy：以哪一份文件作为「骨架」（原文 / 译文）。
 * - otherFirst：插入的「对方语言」文本放在骨架之上还是之下。
 * - markSource：是否标记原文（加粗 / 黄底高亮）。手机版 MVP 以「高亮插入的译文」实现可视区分。
 */
enum class ExportBy { SOURCE, TARGET }

enum class MarkMode { NONE, BOLD, HIGHLIGHT }

data class AlignOptions(
    val exportBy: ExportBy = ExportBy.SOURCE,
    val otherFirst: Boolean = false,
    val markSource: MarkMode = MarkMode.NONE
)
