# -*- coding: utf-8 -*-
"""cad_core.py — WordCount Android CAD 统计核心（自生产版 wordcount.py 提取，与桌面同源）。"""

import os, re, io, struct, glob, json, subprocess, zipfile
from collections import defaultdict


def _hide_window_kwargs():
    """返回 subprocess.run/Popen 用的 kwargs 字典，在 Windows 上隐藏控制台黑框。"""
    kwargs = {}
    if sys.platform.startswith("win"):
        si = subprocess.STARTUPINFO()
        si.dwFlags = subprocess.STARTF_USESHOWWINDOW
        si.wShowWindow = 0  # SW_HIDE
        kwargs["startupinfo"] = si
    return kwargs

_FAR = (
    r"\u1100-\u11FF"   # Hangul Jamo
    r"\u3000-\u303F"   # CJK 符号与标点
    r"\u3130-\u318F"   # Hangul 兼容 Jamo
    r"\u3400-\u4DBF"   # CJK 扩展 A
    r"\u4E00-\u9FFF"   # CJK 基本平面
    r"\uA960-\uA97C"   # Hangul Jamo 扩展 A
    r"\uAC00-\uD7A3"   # Hangul 音节
    r"\uD7B0-\uD7FF"   # Hangul Jamo 扩展 B
    r"\uF900-\uFAFF"   # CJK 兼容
    r"\uFF00-\uFFEF"   # 全角字符
)

FAR_EAST = re.compile("[" + _FAR + "]")

NON_CJK = re.compile("[^" + r"\s" + _FAR + "]+")

def count_unit(s):
    """Return (fe, nc, chars) for a single text unit (paragraph or cell)."""
    fe = len(FAR_EAST.findall(s))
    nc = len(NON_CJK.findall(s))
    ch = len(re.sub(r"\s", "", s))
    return fe, nc, ch

def count_items(items):
    """items: list where each element is either a str (paragraph) or
    ("table", rows) where rows is list[list[str]].

    逐段落 / 逐单元格独立计词 -> 段落、单元格、形状之间的边界都是词分隔，与 Word 一致。
    """
    total_fe = total_nc = total_ch = 0
    for it in items:
        if isinstance(it, str):
            fe, nc, ch = count_unit(it)
            total_fe += fe
            total_nc += nc
            total_ch += ch
        else:  # ("table", rows)
            rows = it[1] if len(it) > 1 else []
            for row in rows:
                for cell in row:
                    fe, nc, ch = count_unit(cell if cell is not None else "")
                    total_fe += fe
                    total_nc += nc
                    total_ch += ch
    return {"fe": total_fe, "nc": total_nc, "chars": total_ch,
            "words": total_fe + total_nc}

BINARY_GROUPS = {b"310", b"311", b"312", b"313", b"314", b"315",
                 b"316", b"317", b"318", b"319"}

def _sanitize_core(dxf_path, deep):
    """共用的 sanitize 实现。deep=False 仅修复 name=None + 二进制补00（轻量，对大多数文件正确）；
    deep=True 额外做空行处理 + OBJECTS 段截断（对 LibreDWG 深度损坏文件如 31013）。"""
    out = dxf_path + ("._deep.dxf" if deep else "._sanitized.dxf")
    try:
        with open(dxf_path, "rb") as f:
            raw = f.read()
    except Exception:
        return dxf_path
    lines = raw.split(b"\n")

    BIN = {b"310", b"311", b"312", b"313", b"314", b"315",
           b"316", b"317", b"318", b"319"}

    # deep：截断 OBJECTS 段（及其后的 THUMBNAILIMAGE）
    if deep:
        cut = None
        for i in range(len(lines) - 1):
            if (lines[i].strip() == b"0" and lines[i + 1].strip() == b"SECTION"
                    and i + 3 < len(lines) and lines[i + 2].strip() == b"2"
                    and lines[i + 3].strip() == b"OBJECTS"):
                cut = i
                break
        if cut is not None:
            lines = lines[:cut]

    def _is_code(t):
        try:
            int(t)
            return True
        except Exception:
            return False

    out_lines = []
    i, n = 0, len(lines)
    prev_code = None
    uniq = 0
    while i < n:
        ln = lines[i]
        s = ln.strip()
        if s == b"":
            # deep：组码行后空行补空格（空值）；值行后空行删除
            # 轻量：原样保留空行（ezdxf 对大多数文件的空行是能容忍的）
            if deep:
                if prev_code is not None and prev_code not in BIN:
                    out_lines.append(b" ")
            else:
                out_lines.append(ln)
            i += 1
            continue
        if s in BIN:
            # 二进制组码：数据替换为 "00"（丢弃 XRECORD 等元数据二进制，对文字无意义）；
            # deep 且空二进制 → 整条删除
            if i + 1 < n and lines[i + 1].strip() != b"":
                out_lines.append(ln)
                out_lines.append(b"00")
                prev_code = s
            i += 2
            continue
        out_lines.append(ln)
        if _is_code(s):
            prev_code = s
        # name=None 修复：BLOCK_RECORD 值行（上一行是组码 0）
        if (s == b"BLOCK_RECORD" and i >= 1
                and lines[i - 1].strip() == b"0"):
            j = i + 1
            has_name = False
            while j < n:
                c = lines[j].strip()
                if c == b"0":
                    break
                if c == b"2":
                    has_name = True
                    break
                j += 2
            if not has_name:
                out_lines.append(b"  2")
                out_lines.append(b"*U" + str(uniq).encode("ascii"))
                uniq += 1
        i += 1
    out_lines.append(b"0")
    out_lines.append(b"EOF")
    with open(out, "wb") as f:
        f.write(b"\n".join(out_lines))
    return out


def sanitize_dxf(dxf_path):
    """轻量修复（name=None + 二进制补00），对大多数 LibreDWG 输出已足够。"""
    return _sanitize_core(dxf_path, deep=False)


def sanitize_dxf_deep(dxf_path):
    """深度修复（+ 空行处理 + OBJECTS 段截断），用于 LibreDWG 深度损坏（如 XRECORD JSON 错乱）。"""
    return _sanitize_core(dxf_path, deep=True)


_UPLUS_RE = re.compile(r"\\U\+([0-9A-Fa-f]{4})")

_MPLUS_RE = re.compile(r"\\M\+([1-5])([0-9A-Fa-f]{4})")

_MPLUS_CP = {"1": "cp932", "2": "cp950", "3": "cp936", "4": "cp949", "5": "cp1361"}

def decode_cad_unicode(s):
    """还原 AutoCAD 的 Unicode 转义序列。

    v1.6.51 关键修复：dwg2dxf 转出的 DXF 里，非 ASCII 文字常写成
    ``\\U+8B66`` 这种 7 位 ASCII 转义（典型：水雾电气图-7区.dwg 全篇 7497 处），
    旧逻辑把反斜杠一删就变成字面量 ``U+8B66``，中文被整段吞掉——
    单个文件就少算 7000+ 中文字符。必须在去格式码之前先解码。

    同时兼容 ``\\M+3XXXX`` 多字节转义（N=代码页索引）。
    """
    if not s or "\\" not in s:
        return s
    if "\\U+" in s or "\\u+" in s:
        s = _UPLUS_RE.sub(lambda m: chr(int(m.group(1), 16)), s)
    if "\\M+" in s:
        def _m(m):
            try:
                cp = _MPLUS_CP.get(m.group(1), "cp936")
                code = int(m.group(2), 16)
                return bytes([code >> 8, code & 0xFF]).decode(cp)
            except Exception:
                return ""
        s = _MPLUS_RE.sub(_m, s)
    return s

def clean_mtext(s):
    s = decode_cad_unicode(s)
    s = s.replace("\\P", "\n").replace("\\p", "\n")
    s = s.replace("\\~", " ").replace("\\^I", " ").replace("\\^J", " ")
    s = re.sub(r"\\[A-Za-z][^;{}]*;", "", s)
    s = s.replace("\\\\", "\\").replace("{", "").replace("}", "").replace("\\", "")
    return s

def extract_text_custom(dxf_path):
    san = sanitize_dxf(dxf_path)
    try:
        with open(san, "rb") as f:
            raw = f.read()
        try:
            text = raw.decode("utf-8")
        except UnicodeDecodeError:
            text = raw.decode("gb18030", errors="replace")
    except Exception:
        return ""
    lines = text.split("\n")
    entities = []
    cur = None
    i, n = 0, len(lines)
    while i < n - 1:
        code = lines[i].strip()
        val = lines[i + 1]
        i += 2
        if code == "0":
            cur = {"_t": val.strip(), "_g": {}}
            entities.append(cur)
        elif cur is not None:
            cur["_g"].setdefault(code, []).append(val)
    collected = []
    for e in entities:
        t, g = e["_t"], e["_g"]
        if t in ("TEXT", "ATTDEF", "ATTRIB"):
            vs = g.get("1")
            if vs and vs[0].strip():
                collected.append(decode_cad_unicode(vs[0]).strip())
        elif t == "MTEXT":
            s = "".join(g.get("1", []) + g.get("3", []))
            s = clean_mtext(s)
            if s.strip():
                collected.append(s.strip())
        elif t == "MULTILEADER":
            s = clean_mtext("".join(g.get("304", []) + g.get("302", [])))
            if s.strip():
                collected.append(s.strip())
    # v1.6.51：取消全局去重（CAD 中同一串文字在多张图上重复出现是合法的，
    # 按翻译计费口径须逐次计数）。详见 `_collect_dxf_texts` 文档。
    return "\n".join(collected)

def _dxf_text_of(entity):
    """取单个 DXF 实体的文字内容（TEXT/ATTDEF/ATTRIB/MTEXT/MULTILEADER）。

    ⚠️ MTEXT 必须清理格式控制码。ezdxf 的 MText 恒有 `.text` 属性，直接取会
    带回 `\\T1.1;`(字间距)、`\\fSimSun|b0;`(字体)、`\\P`(换行) 等排版指令，
    它们会被 count_items 当成正文英文/符号计入 → 字数虚高。
    实测 Tenova 图：`'\\T1.1;8#槽钢'` 中 `\\T1.1;` 6 个字符全被计费。
    统一走 clean_mtext（与 extract_text_custom 口径一致）。
    """
    t = entity.dxftype()
    try:
        if t in ("TEXT", "ATTDEF", "ATTRIB"):
            return clean_mtext(entity.dxf.text or "")
        if t == "MTEXT":
            raw = entity.text if hasattr(entity, "text") else ""
            if not raw:
                try:
                    raw = entity.plain_text() or ""
                except Exception:
                    raw = ""
            return clean_mtext(raw)
        if t == "MULTILEADER":
            try:
                return clean_mtext(entity.plain_text() or "")
            except Exception:
                return clean_mtext(getattr(entity, "text", "") or "")
        if t == "DIMENSION":
            # v1.8.28：补充尺寸标注文字。优先取用户覆盖文字，否则取测量值；
            # 这样 DWG 统计才能覆盖 PDF/OCR 中能看到的尺寸数字/标注。
            txt = ""
            try:
                txt = entity.dxf.text or ""
            except Exception:
                txt = ""
            if not txt:
                try:
                    m = entity.get_measurement()
                    if m is not None:
                        txt = str(m)
                except Exception:
                    pass
            return clean_mtext(txt)
    except Exception:
        return ""
    return ""

def _collect_dxf_texts(doc, max_depth=6):
    """按『图纸渲染所见』收集 DXF 全部文字段落。

    v1.6.51 重写，修复长期存在的两个严重低估（水雾电气图-7区实测）：

    1) ❌ 旧逻辑对 collected 做了【全局去重】。
       CAD 图纸里同一串文字在 32 张图上各出现一次是完全合法的（标题栏的
       "设计/审核/日期/公司名称"、每页图例、材料表表头…），按翻译计费口径
       必须逐次计数。实测 水雾BASE：总条数 11349/中文 8890 → 去重后仅剩
       1552 条/2552 中文，**72% 的中文被吃掉**。
    2) ❌ 旧逻辑把 BLOCKS 段里每个块定义【只数一次】，不按 INSERT 引用次数
       展开；且只取 ATTDEF 模板、完全没抓 INSERT 携带的 ATTRIB 实例值
       （水雾BASE 的 INSERT.ATTRIB 含 2050 个中文全部丢失）。

    另修正一个被去重掩盖的重复计数：旧代码先遍历 `doc.modelspace()`，
    随后又遍历 `doc.layouts`——而 `doc.layouts` 本身就包含 Model 布局，
    模型空间被数了两遍。去重去掉后若不排除 Model 会直接翻倍，此处已排除。

    展开策略：布局(模型空间 + 各图纸空间)直接文字实体 + 每个 INSERT 递归
    展开其块定义内文字（按引用次数重复计入）+ INSERT 自带的 ATTRIB 实例值。
    `cache` 先占位再回填，避免块循环引用造成无限递归。

    3) ❌ 旧逻辑没有还原 ``\\U+XXXX`` 转义（见 `decode_cad_unicode`），
       水雾BASE 全篇 7497 处转义中文被整段吞掉。

    4) v1.8.16 新增：过滤**已关闭/冻结图层**上的文字。DWG 中常把备用表格、
       隐藏明细、历史标注等放在关闭/冻结图层里；这些文字在图纸渲染/PDF
       输出中不可见，但 dwg2dxf 会原样写出，导致字数虚高（全铜SS11-2500
       -35-0.4外形图.dwg 因此从 914 字降到约 200 字，与同图 PDF 的 203
       字吻合）。块内实体若在 AutoCAD 0 层，则继承 INSERT 的有效图层判断。

    回归实测（words 口径）：
      * 水雾BASE : 2552 → 6218 → **13552** 中文，总字数 25071
      * 水雾EN   : 4526 → **13552** 中文，总字数 33879
    BASE 与 EN 是同一套图（EN 为"原文+译文"版），两者中文数完全相等
    (13552 == 13552) 可作为提取正确性的交叉校验锚点。
    """
    cache = {}

    # v1.8.33：含 OLE2FRAME 的图纸（如 L01 系列封面/目录/说明页）通常把标题栏
    # 也作为可见内容，PDF 字数会包含它；纯矢量图（如 Tenova）的标题栏则按传统
    # 口径不计入。用此标志让下方 _is_xref_block 做差异化处理。
    _has_ole2frame = False
    try:
        for _sp in [doc.modelspace()] + list(doc.layouts):
            for _e in _sp:
                if _e.dxftype() == "OLE2FRAME":
                    _has_ole2frame = True
                    break
            if _has_ole2frame:
                break
    except Exception:
        pass

    def _layer_visible(layer_name):
        """判断图层是否打开、未冻结、且可打印（plot）。"""
        if not layer_name:
            return True
        try:
            layer = doc.layers.get(layer_name)
            # v1.8.30：non-plot 图层（如 Defpoints / REV CLAUDE）在出图/PDF 中不显示，
            # 应剔除，避免把图纸上实际不打印的内容计入字数。
            if not getattr(layer.dxf, "plot", True):
                return False
            return layer.is_on() and not layer.is_frozen()
        except Exception:
            return True

    def _is_visible_entity(e):
        """判断单个实体是否可见（排除 invisible 标志 / ATTRIB 隐藏属性）。"""
        try:
            if getattr(e.dxf, "invisible", 0) == 1:
                return False
        except Exception:
            pass
        if e.dxftype() == "ATTRIB":
            try:
                if getattr(e.dxf, "flags", 0) & 1:
                    return False
            except Exception:
                pass
        return True

    def _effective_layer(entity_layer, parent_layer):
        """AutoCAD 约定：块内实体图层为 "0" 时继承 INSERT 所在图层。"""
        if entity_layer in (None, "0"):
            return parent_layer
        return entity_layer

    def _is_xref_block(block_name):
        """判断块是否为外部参照（XREF），避免把整套参照图的文字计入当前图纸。

        v1.8.29：DWG 常把整套参考图纸作为 XREF 绑定进来（如幕墙详图集
        SLIDNG AND BALUSTRADE XREF），其内部文字量是当前单张图的数倍，
        展开后导致字数严重虚高。对 XREF 块只取其直接属性文字，不再递归
        展开内部图元。

        v1.8.33：L01 这类含 OLE2FRAME 的封面/目录/说明页，其标题栏（Title
        Block）带 ``xref_path`` 但 PDF 字数包含它，需正常展开；而纯矢量图
        （如 Tenova）的标题栏按传统口径不计入。因此：块名显式含 XREF 的一律
        过滤；其余带 xref_path 的块仅在图纸含 OLE2FRAME 且块名像标题栏时才
        允许展开。"""
        if not block_name or not isinstance(block_name, str):
            return False
        bn = block_name.upper()
        if "XREF" in bn:
            return True
        if _has_ole2frame and any(k in bn for k in (
                "TITLE BLOCK", "TITLEBLOCK", "A0_", "A1_", "A2_", "A3_",
                "A4_")):
            return False
        try:
            blk = doc.blocks.get(block_name)
            if blk and getattr(blk.block.dxf, "xref_path", ""):
                return True
        except Exception:
            pass
        return False

    def block_texts(name, parent_layer, depth=0):
        if depth > max_depth:
            return []
        # v1.6.54：块名可能为空/非字符串（引用了损坏或不存在的块定义），
        # 直接 doc.blocks.get(None) 会抛 DXFTypeError，需先过滤。
        if not name or not isinstance(name, str):
            return []
        # v1.8.29：跳过外部参照（XREF）块，避免参照图文字混入当前图纸字数。
        if _is_xref_block(name):
            return []
        key = (name, parent_layer)
        if key in cache:
            return cache[key]
        cache[key] = []              # 先占位，防块循环引用死递归
        res = []
        try:
            blk = doc.blocks.get(name)
        except Exception:
            blk = None
        if blk is None:
            return []
        for e in blk:
            ent_layer = getattr(e.dxf, "layer", None)
            eff_layer = _effective_layer(ent_layer, parent_layer)
            if not _layer_visible(eff_layer):
                continue
            if not _is_visible_entity(e):
                continue
            if e.dxftype() == "INSERT":
                try:
                    res.extend(block_texts(e.dxf.name, eff_layer, depth + 1))
                except Exception:
                    pass
                try:
                    for a in e.attribs:
                        a_layer = getattr(a.dxf, "layer", None)
                        a_eff = _effective_layer(a_layer, eff_layer)
                        if not _layer_visible(a_eff):
                            continue
                        if not _is_visible_entity(a):
                            continue
                        s = (a.dxf.text or "").strip()
                        if s:
                            res.append(s)
                except Exception:
                    pass
            else:
                s = _dxf_text_of(e).strip()
                if s:
                    res.append(s)
        cache[key] = res
        return res

    spaces = [doc.modelspace()]
    try:
        for lay in doc.layouts:
            if str(getattr(lay, "name", "")).strip().lower() != "model":
                spaces.append(lay)
    except Exception:
        pass

    out = []
    for sp in spaces:
        try:
            entities = list(sp)
        except Exception:
            continue
        for e in entities:
            ent_layer = getattr(e.dxf, "layer", None)
            if not _layer_visible(ent_layer):
                continue
            if not _is_visible_entity(e):
                continue
            if e.dxftype() == "INSERT":
                try:
                    out.extend(block_texts(e.dxf.name, ent_layer))
                except Exception:
                    pass
                try:
                    for a in e.attribs:
                        a_layer = getattr(a.dxf, "layer", None)
                        a_eff = _effective_layer(a_layer, ent_layer)
                        if not _layer_visible(a_eff):
                            continue
                        if not _is_visible_entity(a):
                            continue
                        s = (a.dxf.text or "").strip()
                        if s:
                            out.append(s)
                except Exception:
                    pass
            else:
                s = _dxf_text_of(e).strip()
                if s:
                    out.append(s)
    return out

def extract_text_from_dxf(dxf_path, converter=None):
    """提取 DXF 全部文字。

    v1.6.51 起【优先走 ezdxf 结构化解析】(`_collect_dxf_texts`)，因为只有
    结构化解析才能按 INSERT 引用次数展开块、并读到 ATTRIB 实例值。
    `extract_text_custom`（裸文本扫组码）无法还原块引用关系，仅在 ezdxf
    读取失败（DXF 结构损坏）时作为兜底。
    """
    import ezdxf
    from ezdxf import DXFError

    def _read(path):
        return ezdxf.readfile(path)

    doc = None
    try:
        doc = _read(dxf_path)
    except DXFError:
        # DXFStructureError / DXFTypeError / DXFValueError 等结构校验异常都在此捕获，
        # 统一走 sanitize + recover 兜底（如某些 DWG 转出的 DXF 在 TABLES 里有 None
        # 命名的表项，ezdxf 严格校验会抛 DXFTypeError，recover 模式可忽略并继续读）。
        san = sanitize_dxf(dxf_path)
        try:
            doc = _read(san)
        except DXFError:
            # 轻量 sanitize 仍失败（LibreDWG 深度损坏：空行/XRECORD JSON 错乱）→ 深度 sanitize
            san2 = sanitize_dxf_deep(dxf_path)
            try:
                doc = _read(san2)
            except DXFError:
                try:
                    from ezdxf.recover import readfile as recover_readfile
                    doc, _auditor = recover_readfile(san2)
                except Exception:
                    doc = None
    if doc is None and converter and os.path.exists(converter):
        alt = os.path.splitext(dxf_path)[0] + "._min.dxf"
        try:
            subprocess.run([converter, "-m", "-y", "-o", alt, dxf_path],
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=180,
                           **_hide_window_kwargs())
            if os.path.exists(alt):
                doc = ezdxf.readfile(alt)
        except Exception:
            doc = None
    if doc is None:
        # ezdxf 全线失败（DXF 结构损坏）→ 退回裸文本扫描
        try:
            return extract_text_custom(dxf_path)
        except Exception:
            return ""
    collected = _collect_dxf_texts(doc)
    if not collected:
        try:
            return extract_text_custom(dxf_path)
        except Exception:
            return ""
    return "\n".join(collected)

def _detect_frame_rectangles(doc, want_areas=False):
    """从模型空间/图纸空间的『长直线段』重建图框矩形，统计张数（可选面积列表）。

    适用场景：图框是用 LINE 几何画线构成的 CAD（没有 INSERT 标题块、
    或 dwg2dxf 转换后图纸空间内容丢失、标题块落到模型空间的情况）。
    思路：
      * 收集所有较长的水平/竖直 LINE，按坐标量化后分组；
      * 用水平线段(上下边) + 竖直线段(左右边)两两配对，找出闭合矩形；
      * 仅保留"极大矩形"(不被其它矩形包含的)，排除框内小框(如标题栏)；
      * 若一个超大矩形包含 >=3 个小矩形，则小矩形才是真正的图框(整卷外框)；
      * 最后过滤出"像图纸"的矩形(足够大、宽高不极端)，其数量即页数。

    返回:
      want_areas=False -> int (>=0)
      want_areas=True  -> (int, float, list[float])  (count, total_area, [area_i, ...])
                          面积单位为 dwg 原始单位²（坐标 SNAP=2 已还原）。
    """
    try:
        from collections import defaultdict
    except Exception:
        return (0, 0.0, []) if want_areas else 0
    SNAP = 2

    def sn(v):
        try:
            return int(round(float(v) / SNAP))
        except Exception:
            return 0

    Hy = defaultdict(list)   # y -> [(xmin, xmax), ...]  水平长线
    Vx = defaultdict(list)   # x -> [(ymin, ymax), ...]  竖直长线

    def collect(entities):
        for e in entities:
            if e.dxftype() != "LINE":
                continue
            try:
                x1 = e.dxf.start.x; y1 = e.dxf.start.y
                x2 = e.dxf.end.x;   y2 = e.dxf.end.y
            except Exception:
                continue
            if abs(y1 - y2) < 1e-3:            # 水平线
                L = abs(x2 - x1)
                if L >= 150:
                    Hy[sn(y1)].append((sn(min(x1, x2)), sn(max(x1, x2))))
            elif abs(x1 - x2) < 1e-3:          # 竖直线
                L = abs(y2 - y1)
                if L >= 150:
                    Vx[sn(x1)].append((sn(min(y1, y2)), sn(max(y1, y2))))

    try:
        collect(doc.modelspace())
    except Exception:
        pass
    try:
        for layout in doc.layouts:
            collect(layout)
    except Exception:
        pass
    try:
        for bname in ("*Paper_Space", "*Paper_Space0",
                      "*Paper_Space1", "*Paper_Space2"):
            try:
                collect(doc.blocks[bname])
            except Exception:
                pass
    except Exception:
        pass

    if not Hy or not Vx:
        return (0, 0.0, []) if want_areas else 0

    def merge(iv):
        iv.sort()
        out = []
        for a, b in iv:
            if out and a <= out[-1][1] + 1:
                out[-1] = (out[-1][0], max(out[-1][1], b))
            else:
                out.append((a, b))
        return out

    for y in Hy:
        Hy[y] = merge(Hy[y])
    for x in Vx:
        Vx[x] = merge(Vx[x])

    def v_span(x, ylo, yhi):
        for a, b in Vx.get(x, []):
            if a - 1 <= ylo and b + 1 >= yhi:
                return True
        return False

    rects = []
    ylist = sorted(Hy)
    n = len(ylist)
    for i in range(n):
        ya = ylist[i]
        for j in range(i + 1, n):
            yb = ylist[j]
            if yb - ya < 100:
                continue
            for a1, b1 in Hy[ya]:
                for a2, b2 in Hy[yb]:
                    lo = max(a1, a2)
                    hi = min(b1, b2)
                    if hi - lo < 100:
                        continue
                    if v_span(lo, ya, yb) and v_span(hi, ya, yb):
                        rects.append((lo * SNAP, ya * SNAP,
                                      hi * SNAP, yb * SNAP))

    def area(r):
        return (r[2] - r[0]) * (r[3] - r[1])

    def contains(big, o):
        return (big[0] - 5 <= o[0] and big[1] - 5 <= o[1]
                and o[2] <= big[2] + 5 and o[3] <= big[3] + 5)

    # 仅保留极大矩形（去除被包含的）
    rects.sort(key=lambda r: -area(r))
    maximal = []
    for r in rects:
        if any(contains(f, r) for f in maximal):
            continue
        maximal.append(r)
    if not maximal:
        return (0, 0.0, []) if want_areas else 0

    # 若某个超大矩形包含 >=3 个其它矩形，则小矩形才是真正的图框
    containers = [r for r in maximal
                  if sum(1 for o in maximal if contains(r, o)) >= 3]
    if containers:
        big = max(containers, key=area)
        frames = [o for o in maximal if contains(big, o)]
    else:
        frames = maximal

    def sheet_like(r):
        w = r[2] - r[0]
        h = r[3] - r[1]
        if w < 150 or h < 150:
            return False
        if area(r) < 40000:
            return False
        ar = max(w, h) / min(w, h)
        return ar <= 10     # 排除极端细长的误判线条

    kept = [r for r in frames if sheet_like(r)]
    if not kept:
        return (0, 0.0, []) if want_areas else 0
    # 「同图合并」：仅当存在一个『主导大框』（最大框面积 >= 3× 次大框）时，
    # 才认为其余框是该大框内的详图/分区子框，报 1 张图；否则（多个等尺寸框
    # 并排，如哈萨克斯坦/ПРО 的 9/11 张同尺寸 A3 图框）视为独立图纸全部保留。
    # 实测：
    #   * Tenova Contour Foundation DWG: 5 框，最大 9543168 / 次大 2880000 = 3.3×
    #     → 合并为 1 张 ✓
    #   * 哈萨克斯坦 DWG: 9 框全 ≈125k（最大/次大=1.005×）→ 不合并 → 9 ✓
    #   * ПРО DWG: 11 框全 ≈125k → 不合并 → 11 ✓
    #   * 巴布亚桩基 DWG: 5 框 y 跨度很大、无主导 → 不合并 → 5 ✓
    if len(kept) >= 2:
        _areas_sorted = sorted((area(r) for r in kept), reverse=True)
        if _areas_sorted[0] >= 3.0 * _areas_sorted[1]:
            # 主导大框 + 内部子框：报 1 张
            return (1, sum(float(area(r)) for r in kept),
                    [float(area(r)) for r in kept]) if want_areas else 1
    if not want_areas:
        return len(kept)
    areas = [float(area(r)) for r in kept]
    return (len(kept), sum(areas), areas)

def _norm_sheet_code(val):
    """从图号/页码属性值中规整出『图纸代码』，用于去重统计张数。

    统一取 4~6 位连续数字；忽略前导字母(如 T/A)与后缀，
    使 'T03004' 与 '03004'、'T12001' 与 '12001' 归并为同一张图纸。
    """
    if not val:
        return None
    val = val.strip().upper()
    m = re.search(r"\d{4,6}", val)
    if m:
        return m.group(0)
    return None

def _distinct_sheet_numbers(doc):
    """从标题块属性提取不重复的图纸张数。

    规则：
    * 主标题块：带有图号/页码类属性，且带图名/标题属性、或块名像标题栏、
      或带强图号属性(图号/页号/SHEET_NUMBER/…) → 取其规整后的图号值计为一张图纸。
    * 局部标题块(大样/节点/详图…)：其 DWGNO/所在图 指向『所属图纸』，
      也并入图纸集合(去重后即该图纸张数)。
    * 匿名且仅带 DWGNO/NO 的块(多为详图/截面标记，NO 为细部编号)不计为独立图纸，
      避免把『详图』当『图纸』重复计数(旧版 181 页即此类误判)。
    * 幕墙/构件等(不带图号属性)天然被过滤(旧版因块名含 BORDER 误判
      'Left Border Mullion' 的问题不再出现)。
    返回去重后的图纸代码数量。
    """
    sheet_tag = re.compile(
        r"(SHEET_NUMBER|图号|页号|DWGNO|NO|PAGE|SHEET|DRAWING_NO|图纸编号|图纸|"
        r"SOUZAITU|SUOZAI|所在图)",
        re.I)
    strong_sheet = re.compile(
        r"(SHEET_NUMBER|图号|页号|DRAWING_NO|图纸编号)", re.I)
    title_tag = re.compile(r"(DRAWING_TITLE|图名|标题|TITLE|NAME)", re.I)
    title_name = re.compile(
        r"(图框|边框|幅面|图纸|标题栏|会签栏|签名栏|FRAME|FRM|BORDER|BORD|"
        r"TITLE|TBAR|TB|SHEET|FORMAT|GB|国标)", re.I)
    detail_name = re.compile(
        r"(大样|节点|DETAIL|CALL|CALLOUT|NOTE|局部|NOSING|标高|型材|SECTION|DET|"
        r"局部放大|索引|放大|详图|节点详图|大样图|详图索引)", re.I)
    sheets = set()
    for layout in doc.layouts:
        for e in layout.query("INSERT"):
            try:
                nm = e.dxf.name
            except Exception:
                continue
            is_detail = bool(detail_name.search(nm))
            vals = []
            has_title = False
            any_tag = []
            for attr in e.attribs:
                try:
                    tag = attr.dxf.tag
                    txt = (attr.dxf.text or "").strip()
                except Exception:
                    continue
                any_tag.append(tag)
                if title_tag.search(tag):
                    has_title = True
                if sheet_tag.search(tag) and txt:
                    vals.append(txt)
            if not vals:
                continue
            if is_detail:
                # 局部标题：其图号指向所属图纸
                for v in vals:
                    c = _norm_sheet_code(v)
                    if c:
                        sheets.add(c)
                continue
            # 主标题块：需带图名/标题属性，或块名像标题栏，或带强图号属性
            if has_title or title_name.search(nm) or strong_sheet.search(" ".join(any_tag)):
                for v in vals:
                    c = _norm_sheet_code(v)
                    if c:
                        sheets.add(c)
    return len(sheets)

def _raw_closed_polylines(dxf_path):
    """从原始 DXF 文本解析闭合 LWPOLYLINE/POLYLINE 矩形的外接框。

    用于 ezdxf 无法解析的损坏 DXF 的兜底页数估算。
    """
    try:
        with open(dxf_path, "rb") as f:
            raw = f.read()
    except Exception:
        return []
    try:
        lines = raw.decode("utf-8").split("\n")
    except Exception:
        lines = raw.decode("gb18030", "ignore").split("\n")
    n = len(lines)
    rects = []
    i = 0
    while i < n - 1:
        code = lines[i].strip()
        val = lines[i + 1].strip() if i + 1 < n else ""
        if code == "0" and val in ("LWPOLYLINE", "POLYLINE"):
            etype = val
            pts = []
            closed = False
            j = i + 2
            in_vertex = False
            while j < n - 1:
                c2 = lines[j].strip()
                v2 = lines[j + 1].strip() if j + 1 < n else ""
                if c2 == "0":
                    if etype == "POLYLINE":
                        if v2 == "VERTEX":
                            in_vertex = True
                            j += 2
                            continue
                        else:
                            break
                    else:
                        break
                if etype == "LWPOLYLINE":
                    if c2 == "70":
                        try:
                            if int(float(v2)) & 1:
                                closed = True
                        except Exception:
                            pass
                    elif c2 == "10":
                        try:
                            x = float(v2)
                            if j + 3 < n and lines[j + 2].strip() == "20":
                                y = float(lines[j + 3].strip())
                                pts.append((x, y))
                                j += 2
                        except Exception:
                            pass
                else:
                    if in_vertex:
                        if c2 == "70":
                            try:
                                if int(float(v2)) & 1:
                                    closed = True
                            except Exception:
                                pass
                        elif c2 == "10":
                            try:
                                x = float(v2)
                                if j + 3 < n and lines[j + 2].strip() == "20":
                                    y = float(lines[j + 3].strip())
                                    pts.append((x, y))
                                    j += 2
                            except Exception:
                                pass
                j += 1
            if closed and len(pts) >= 4:
                xs = [p[0] for p in pts]; ys = [p[1] for p in pts]
                rects.append((min(xs), min(ys), max(xs), max(ys)))
            i = j
        else:
            i += 1
    return rects

def _count_geom_frames(rects, min_side=150, min_area=40000, max_ar=10):
    """从矩形列表统计图纸张数：过滤过小/过扁，去掉单一整体外框，
    取互不重叠包含的最大矩形个数，并剔除明显偏小的标题栏/详图框。

    v1.6.50 修复『多图拼板 + 混合比例尺』漏计（水雾电气图-7区：17 → 32）：
      1) 候选去重：把「几乎重合」（互相包含）的重复矩形折叠为一个，
         避免同一条框线被 dwg2dxf 输出成两个近似矩形而干扰后续判定。
      2) 容器展开：CAD 常把多张小图拼进一个大外框（如 4955x1865 内并排
         8 张 532x376）。旧逻辑只在『最大框 > 4× 次大框』时丢弃外框，
         该拼板外框仅 1.87× 次大框，于是外框被保留、内部 8 张真图被
         `maximal` 吞掉 → 8 张记成 1 张。现改为：某框若直接包含 >=2 个
         『尺寸相近且互不重叠』的子框，即判定为拼板容器，用子框替代它。
         尺寸相近(面积极差<=1.5x)+互不重叠 两个约束用于排除图框内那些
         成对重合的标注/装饰小矩形被误当成子图。
      3) 发生容器展开时跳过『面积断层裁剪 + 1% 绝对面积阈值』：拼板图纸
         天然混合比例尺（1209x855 与 A4 210x297 相差 16.6 倍），绝对面积
         阈值会把小比例尺的真实图纸整批误杀（实测误删 6 张 A4）。
         未展开时完全沿用旧逻辑，保证既有文件零回归。

    回归实测（LWPOLYLINE 口径）：
      * 水雾电气图-7区 DWG : 17 → 32 ✓（与手机端一致）
      * 巴布亚桩基 DWG     : 11 → 11 ✓（未展开，走旧逻辑）
      * 马尔代夫给排水 DWG : 14 → 14 ✓（子框重叠，展开被抑制）
      * Tenova Contour DWG :  0 →  0 ✓（走布局计数，不受影响）
    """
    cand = []
    for (a, b, c, d) in rects:
        w = c - a; h = d - b
        if w < min_side or h < min_side:
            continue
        area = w * h
        if area < min_area:
            continue
        ar = max(w, h) / max(min(w, h), 1)
        if ar > max_ar:
            continue
        cand.append((a, b, c, d, area))
    if not cand:
        return 0
    cand.sort(key=lambda r: -r[4])
    # 去掉单一整体外框：若最大者明显大于次大者
    if len(cand) >= 2 and cand[0][4] > cand[1][4] * 4:
        cand = cand[1:]
    if not cand:
        return 0

    def contains(big, o):
        return (big[0] - 5 <= o[0] and big[1] - 5 <= o[1]
                and o[2] <= big[2] + 5 and o[3] <= big[3] + 5)

    def strict_in(inner, outer):
        """inner 真包含于 outer（排除两者几乎重合的情形）。"""
        return contains(outer, inner) and not contains(inner, outer)

    def overlap(p, q):
        return not (p[2] <= q[0] or q[2] <= p[0]
                    or p[3] <= q[1] or q[3] <= p[1])

    # 1) 折叠「几乎重合」的重复矩形（互相包含视为同一个框）
    dedup = []
    for r in cand:
        if any(contains(f, r) and contains(r, f) for f in dedup):
            continue
        dedup.append(r)
    cand = dedup

    # 取互不包含的最大矩形（过滤框内小框）
    cand.sort(key=lambda r: -(r[4]))
    maximal = []
    for r in cand:
        if any(contains(f, r) for f in maximal):
            continue
        maximal.append(r)
    if not maximal:
        return 0

    # 2) 容器展开：拼板外框 -> 内部并排的真实图框
    expanded = []
    did_expand = False
    for r in maximal:
        kids = [o for o in cand if strict_in(o, r)]
        direct = [k for k in kids if not any(strict_in(k, m) for m in kids)]
        ok = len(direct) >= 2
        if ok:
            areas = [k[4] for k in direct]
            if max(areas) > 1.5 * min(areas):
                ok = False          # 子框尺寸不一致 -> 不是拼板
        if ok:
            for i in range(len(direct)):
                for j in range(i + 1, len(direct)):
                    if overlap(direct[i], direct[j]):
                        ok = False  # 子框互相重叠 -> 是标注/装饰框
                        break
                if not ok:
                    break
        if ok:
            expanded.extend(direct)
            did_expand = True
        else:
            expanded.append(r)
    _seen = set()
    maximal = []
    for r in expanded:
        k = (round(r[0], 3), round(r[1], 3), round(r[2], 3), round(r[3], 3))
        if k in _seen:
            continue
        _seen.add(k)
        maximal.append(r)

    maximal.sort(key=lambda r: -r[4])

    # 3) 未发生拼板展开时，沿用旧的断层裁剪 + 绝对面积阈值
    if not did_expand:
        # 按面积自然聚类：最大断层以上的矩形视为真正图框，
        # 避免把标题栏/详图小框/装饰矩形等统计成图纸。
        if len(maximal) >= 2:
            ratios = [maximal[i][4] / max(maximal[i+1][4], 1)
                      for i in range(len(maximal) - 1)]
            max_gap_idx = max(range(len(ratios)), key=lambda i: ratios[i])
            if ratios[max_gap_idx] >= 5:
                maximal = maximal[:max_gap_idx + 1]

        # 再按面积阈值去掉残余极小框
        max_area = max(r[4] for r in maximal)
        thr = max(min_area, max_area * 0.01)
        maximal = [r for r in maximal if r[4] >= thr]

    return len(maximal)

def _sheet_like_ratio(w, h, lo=1.30, hi=1.55):
    """宽高比是否符合标准图纸（ISO A 系列 √2 ≈ 1.414，含横/竖版）。

    图框块判据的核心：A0~A4 长短边之比恒为 √2。区间放宽到 1.30~1.55
    以容纳带装订边/外框留白的工程图框（实测 EPLAN 图框 1.4126~1.4149）。
    """
    if w <= 0 or h <= 0:
        return False
    ar = max(w, h) / min(w, h)
    return lo <= ar <= hi

def _block_frame_rects(doc, max_depth=4):
    """把『图框块引用』还原成世界坐标矩形，供几何图框检测补充候选。

    背景（v1.6.51 修复 水雾电气图-7区 BASE 17 页 vs EN 32 页的『同图框不同页数』）：
    同一套图纸的两个 DWG，EN 版把拼板内的 8 个子图框存成模型空间 LWPOLYLINE，
    BASE 版却把它们存成 EPLFRAME_* 块引用(INSERT)。`_detect_lwpolyline_sheets`
    只看模型空间实体，于是 BASE 漏掉块内图框 → 只数到 17 页。

    ⚠️ 为什么不直接 `INSERT.explode()`：
      实测把所有 INSERT 无差别炸开会把块内的详图/表格/构件小矩形全部倒进候选池，
      巴布亚桩基 DWG 因此从 11 页暴涨到 66 页（LINE 口径更是 1 → 744）。

    本函数改为『只取每个块内最大的闭合矩形，且该矩形须符合 √2 图纸比例』：
      * 巴布亚的块矩形 22000x56700 (2.58) / 13700x7550 (1.82) → 被比例判据排除
      * 水雾 BASE 的 EPLFRAME 块 630x446 (1.4126) → 通过，补回 15 张 → 32 页 ✓
    旋转非 0 的 INSERT 直接跳过（保守，避免变换误差引入假框）。

    回归实测：水雾BASE 17→32 ✓ / 水雾EN 32→32 ✓ / 巴布亚 11→11 ✓
              马尔代夫 14→14 ✓ / Tenova 0→0 ✓（走布局计数）
    """
    cache = {}

    def rect_of(e):
        if e.dxftype() != "LWPOLYLINE":
            return None
        try:
            if not e.closed:
                return None
            pts = list(e.get_points())
            if len(pts) < 4:
                return None
            xs = [p[0] for p in pts]
            ys = [p[1] for p in pts]
            return (min(xs), min(ys), max(xs), max(ys))
        except Exception:
            return None

    def xform(r, ins):
        """把块坐标矩形按 INSERT 的插入点/缩放变换到父坐标系。"""
        try:
            sx = float(ins.dxf.xscale or 1)
            sy = float(ins.dxf.yscale or 1)
            ix = float(ins.dxf.insert.x)
            iy = float(ins.dxf.insert.y)
        except Exception:
            return None
        x1, x2 = r[0] * sx + ix, r[2] * sx + ix
        y1, y2 = r[1] * sy + iy, r[3] * sy + iy
        return (min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2))

    def block_max_rect(name, depth=0):
        """递归求块内面积最大的闭合矩形（块自身坐标系）。"""
        if depth > max_depth:
            return None
        if name in cache:
            return cache[name]
        cache[name] = None            # 先占位，防止块循环引用死递归
        try:
            blk = doc.blocks.get(name)
        except Exception:
            return None
        if blk is None:
            return None
        best, best_area = None, 0.0
        for e in blk:
            r = rect_of(e)
            if r is None and e.dxftype() == "INSERT":
                try:
                    sub = block_max_rect(e.dxf.name, depth + 1)
                except Exception:
                    sub = None
                r = xform(sub, e) if sub else None
            if r:
                a = (r[2] - r[0]) * (r[3] - r[1])
                if a > best_area:
                    best_area, best = a, r
        cache[name] = best
        return best

    out = []
    try:
        inserts = list(doc.modelspace().query("INSERT"))
    except Exception:
        return out
    for ins in inserts:
        try:
            rot = float(ins.dxf.rotation or 0)
        except Exception:
            rot = 0.0
        if abs(rot) > 1e-6 and abs(abs(rot) - 360) > 1e-6:
            continue                  # 旋转块保守跳过
        try:
            base_r = block_max_rect(ins.dxf.name)
        except Exception:
            base_r = None
        if not base_r:
            continue
        r = xform(base_r, ins)
        if not r:
            continue
        if not _sheet_like_ratio(r[2] - r[0], r[3] - r[1]):
            continue
        out.append(r)
    return out

def _detect_lwpolyline_sheets(doc):
    """统计模型空间中类似图纸的闭合 LWPOLYLINE 矩形数量。

    v1.6.51：除模型空间直接绘制的矩形外，追加『图框块引用』还原出的矩形
    （见 `_block_frame_rects`），修复图框以 INSERT 形式存放时的漏计。
    """
    rects = []
    for e in doc.modelspace().query("LWPOLYLINE"):
        try:
            if not e.closed:
                continue
            pts = list(e.get_points())
            if len(pts) < 4:
                continue
            xs = [p[0] for p in pts]; ys = [p[1] for p in pts]
            rects.append((min(xs), min(ys), max(xs), max(ys)))
        except Exception:
            pass
    try:
        rects.extend(_block_frame_rects(doc))
    except Exception:
        pass
    return _count_geom_frames(rects)

def _count_detail_sheets(doc):
    """针对『大样/节点』类详图：按标题块位置聚类估算图纸页数（兜底）。"""
    from collections import defaultdict
    detail_pat = re.compile(
        r"(?:大样|节点|DETAIL|CALLOUT|详图|detail|节点详图|大样图|详图索引)", re.I)
    detail_pos = []
    for layout in doc.layouts:
        for e in layout.query("INSERT"):
            try:
                name = e.dxf.name
            except Exception:
                continue
            if not detail_pat.search(name):
                continue
            has_id = False
            for attr in e.attribs:
                try:
                    tag = attr.dxf.tag
                    if tag in ("DWGNO", "NO", "PAGE", "页号", "图号", "SHEET"):
                        has_id = True
                        break
                except Exception:
                    pass
            if not has_id:
                continue
            try:
                detail_pos.append((e.dxf.insert.x, e.dxf.insert.y))
            except Exception:
                pass
    n = len(detail_pos)
    if n < 2:
        return 0
    # 取模型空间最大的闭合 LWPOLYLINE 作为『图框条带』尺寸参考
    largest = None
    max_area = 0
    for e in doc.modelspace().query("LWPOLYLINE"):
        try:
            if not e.closed:
                continue
            pts = list(e.get_points())
            if len(pts) < 4:
                continue
            xs = [p[0] for p in pts]; ys = [p[1] for p in pts]
            w = max(xs) - min(xs); h = max(ys) - min(ys)
            area = w * h
            if area > max_area:
                max_area = area
                largest = (w, h)
        except Exception:
            pass
    if not largest:
        return 0
    w, h = largest
    if w < 1 or h < 1:
        return 0
    ar = max(w, h) / max(min(w, h), 1)
    if ar > 3:
        grid_size = min(w, h)
    else:
        grid_size = max(w, h)
    # 防止用超大整体外框做网格（>20000 视为异常，放弃聚类）
    if grid_size > 20000 or grid_size < 1:
        return 0
    cells = defaultdict(int)
    for x, y in detail_pos:
        cells[(round(x / grid_size), round(y / grid_size))] += 1
    return len(cells)

def _raw_layout_count(dxf_path):
    """从原始 DXF 文本中统计图纸空间布局数（兼容 ezdxf 无法解析时）。"""
    try:
        with open(dxf_path, "rb") as f:
            raw = f.read()
        text = None
        for enc in ("utf-8", "gbk", "gb18030"):
            try:
                text = raw.decode(enc)
                break
            except Exception:
                continue
        if text is None:
            text = raw.decode("utf-8", "ignore")
        matches = re.findall(r"\*Paper_Space(?:\d*)", text)
        unique = set()
        for m in matches:
            if m == "*Paper_Space":
                m = "*Paper_Space0"
            unique.add(m)
        return len(unique)
    except Exception:
        return 0

def count_cad_frames(dxf_path):
    """统计 CAD 图框数（页数）。返回 (frames:int|None, reason:str|None)。

    reason 标注本次页数的『统计口径』，供 GUI 状态列透明展示：
      * 布局计数        —— 含图元的图纸空间布局数（最权威，一张图=一个布局）
      * 标题块图号      —— 标题块属性中不重复图号/页码（主+局部标题块）
      * 详图聚类估算    —— 『大样/节点』详图按位置聚类（每张详图算一页，存近似）
      * 几何图框估算    —— LINE/LWPOLYLINE 重建图框矩形（无标题块/布局时兜底，存近似）
      * DXF损坏·几何估算 / DXF损坏·布局估算 / DXF损坏·按1页估算
                          —— ezdxf 无法解析损坏 DXF 时的原始文本几何/布局兜底

    判定优先级（取最代表『图纸张数』的信号）：
      1. 含图元的图纸空间布局数（不含 Model）；
      2. 标题块不重复图号 + 详图聚类（详图优先：det>=sheets 时取详图数）；
      3. 模型/图纸空间 LINE/LWPOLYLINE 重建出的图框矩形；
      4. 有图元则按 1 张计；
      5. ezdxf 无法解析时：原始 DXF 文本几何 / 布局数兜底。
    """
    try:
        import ezdxf
    except Exception as e:
        return None, "无法加载 ezdxf 统计图框: %s" % e, 0

    doc = None
    try:
        doc = ezdxf.readfile(dxf_path)
    except Exception:
        try:
            san = sanitize_dxf(dxf_path)
            doc = ezdxf.readfile(san)
        except Exception:
            try:
                from ezdxf.recover import readfile
                doc, _ = readfile(san)
            except Exception:
                doc = None

    if doc:
        # 1) 图纸空间布局（含图元）
        #    在 dwg2dxf 输出里，『布局』常常只剩标题块（每张 2 个实体），真正的
        #    图框几何被全部塞进了 Model 空间——若只看布局数会把 9 张图报成 2 张。
        #    因此这里先收集「布局数 + 模型空间密度」，再用几何检测交叉验证。
        #    注意：只把「含非视口实体」的布局算作真实出图页。很多 DWG 的图纸空间
        #    布局只放了一个 VIEWPORT 指向 Model（标题块/图框/文字都在 Model 空间），
        #    这种「仅视口」空布局不应算独立一页，否则会把 1 张图误报成多页
        #    （全铜SS11-2500-35-0.4外形图.dwg：2 个仅视口布局 → 曾误报 2 页，实际
        #    Model 空间只有 1 张图）。
        paper = 0
        paper_total_ents = 0
        bare_viewport_layouts = 0
        try:
            for layout in doc.layouts:
                if layout.name.upper() == "MODEL":
                    continue
                try:
                    ents = list(layout)
                    nents = len(ents)
                except Exception:
                    ents, nents = [], 0
                paper_total_ents += nents
                if nents == 0:
                    continue
                non_vp = [e for e in ents if e.dxftype() != "VIEWPORT"]
                if len(non_vp) > 0:
                    paper += 1
                else:
                    bare_viewport_layouts += 1
        except Exception:
            paper = 0
        try:
            ms_ents = len(doc.modelspace())
        except Exception:
            ms_ents = 0

        # 几何图框（不仅兜底用，也要用于交叉校验布局数是否被低估）
        geo = 0
        try:
            geo_line = _detect_frame_rectangles(doc)
        except Exception:
            geo_line = 0
        try:
            geo_lw = _detect_lwpolyline_sheets(doc)
        except Exception:
            geo_lw = 0
        # 优先采用 LWPOLYLINE 闭合多段线图框：CAD 中『图纸图框』通常绘制为
        # 闭合 LWPOLYLINE 矩形；而 LINE 线段重建的矩形常把标题栏/详图框(小矩形)
        # 误判为图纸(如安丰办公楼：LINE 数出 14 个 1400x1700 标题块，LWPOLYLINE
        # 正确数出 9 张 A1 图框)。仅当 LWPOLYLINE 检测不到任何图框时，才回退到
        # LINE 几何检测。实测 4 个文件 LWPOLYLINE 口径均与 PDF 页数一致。
        geo = geo_lw if geo_lw >= 1 else geo_line

        # 若布局含真实出图视口（VIEWPORT），记录 has_viewport 供后续判定。
        has_viewport = False
        if paper >= 1:
            try:
                for layout in doc.layouts:
                    if layout.name.upper() == "MODEL":
                        continue
                    if len(list(layout.query("VIEWPORT"))) > 0:
                        has_viewport = True
                        break
            except Exception:
                pass

        # 几何交叉校验（放在「含视口即信任布局」之前）：
        # LibreDWG→DXF 常把所有图挤进 Model 空间、图纸布局只剩视口（几乎无标题块/
        # 图框实体），此时『布局数』会被严重低估，而模型空间里的几何图框数才是真实
        # 张数。当几何图框数明显多于布局数、模型空间密集、且布局实体极稀疏（印证图
        # 真的在 Model 里）时，改用几何图框数。
        #   · 上限 geo<=12 防御「单张大样图内 dozens 个 detail 小框」被误判成多页：
        #     FA-31003 类若其布局含标题块(paper_total_ents>8)会被下方 guard 拦下；
        #     即便布局也稀疏，geo<=12 也能拦住 27 这种明显 detail 网格。
        #   · v1.8.29 修正：当 paper==0（完全没有真实图纸空间布局）时，限制过严会
        #     把真实多页图（如 08-NBE 门窗表，模型空间有 19 个图框）误报成 1 页。
        #     此时放宽上限，但用面积分布防御：若最大框面积显著（>=5x）大于次大框，
        #     仍视为单张大样图；否则信任几何图框数。
        #   · paper_total_ents<=paper*8 进一步保证：布局本身含足量实体（真出图页）
        #     时不越权改用几何，避免把『一张含多视口的大图』拆成多页。
        #   · v1.8.13 新增：必须同时有 LWPOLYLINE 闭合图框（geo_lw>=1）。只用 LINE
        #     重建出的小矩形往往是图内标注框/局部放大框，不是真实图纸图框；若图框
        #     真是 LINE 构成，后续「标题块图号 / 几何图框估算」仍会兜底。
        use_geo = False
        if (geo >= 3 and geo > paper + 1
                and ms_ents > 1000 and paper_total_ents <= paper * 8
                and geo_lw >= 1):
            if paper >= 1:
                use_geo = (geo <= 12)
            else:
                # paper==0：放宽上限，但防御单张大样图
                use_geo = True
                try:
                    areas = []
                    for e in doc.modelspace().query("LWPOLYLINE"):
                        if not e.closed:
                            continue
                        pts = list(e.get_points())
                        if len(pts) < 4:
                            continue
                        xs = [p[0] for p in pts]
                        ys = [p[1] for p in pts]
                        w = max(xs) - min(xs)
                        h = max(ys) - min(ys)
                        area = w * h
                        if w >= 150 and h >= 150 and area >= 40000:
                            ar = max(w, h) / max(min(w, h), 1)
                            if ar <= 10:
                                areas.append(area)
                    areas.sort(reverse=True)
                    if len(areas) >= 2 and areas[0] >= 5.0 * areas[1]:
                        # 一个超大外框 + 内部小框 → 单张大样图
                        use_geo = False
                except Exception:
                    pass
        if use_geo:
            return geo, "布局稀疏·改用几何图框估算", paper

        # 含真实出图视口且几何未强烈反驳 → 信任布局计数
        # （避免单张大样图被几何检测误判成多页；FA-31003 即因此类保持 1 页）。
        if has_viewport and paper >= 1:
            return paper, "布局计数", paper

        if paper >= 1:
            return paper, "布局计数", paper

        # 全部图纸空间布局都是「仅视口」空布局（无标题块/图框/文字，图纸内容全在
        # Model 空间）→ 真实出图内容只有 Model 里的单张图，按 1 页计。
        # （全铜SS11-2500-35-0.4外形图.dwg：2 个仅视口布局 + Model 1 张图）
        if bare_viewport_layouts >= 1 and ms_ents > 50:
            return 1, "Model单图·图纸布局仅含视口", paper

        # 2) 标题块不重复图号 + 详图聚类
        #    口径（详图优先）：当『大样/节点』详图按位置聚类得到的张数
        #    >= 主/局部标题块的不重复图号时，视为『每张详图算一页』的详图集，
        #    以详图聚类数为页数（如幕墙/节点详图集 38 张）。
        try:
            sheets = _distinct_sheet_numbers(doc)
        except Exception:
            sheets = 0
        try:
            det = _count_detail_sheets(doc)
        except Exception:
            det = 0
        if det >= 1 and det >= sheets:
            return det, "详图聚类估算", paper
        if sheets >= 1:
            return sheets, "标题块图号", paper

        # 3) 几何图框矩形（已在顶部预计算过 geo）
        if geo >= 1:
            return geo, "几何图框估算", paper

        # 4) 大样/节点详图聚类（无图号时的兜底）
        if det >= 1:
            return det, "详图聚类估算", paper

        # 5) 有图元 -> 1
        try:
            if len(doc.modelspace()) > 0:
                return 1, "有图元·按1页估", paper
        except Exception:
            pass
        return None, "CAD 无图框/布局，无法统计页数", paper

    # 6) ezdxf 无法解析：原始 DXF 文本兜底
    #    优先按布局数估算：DXF 损坏通常意味着几何信息不可靠，而布局名
    #    (*Paper_Space*) 仍可在文本中识别。单布局大样图若先走几何估算，
    #    会把模型空间里的 detail 小框误判成多页（如 FA-31003 被估成 27 页）。
    rl = _raw_layout_count(dxf_path)
    if rl >= 1:
        return rl, "DXF损坏·布局估算", 0
    rects = _raw_closed_polylines(dxf_path)
    geo = _count_geom_frames(rects)
    if geo >= 1:
        return geo, "DXF损坏·几何估算", 0
    return 1, "DXF损坏·按1页估算"

def _extract_dwg_gbk_cjk(raw_bytes, min_run=4, max_per_call=4000):
    """从 DWG 二进制字节中扫描 GBK 编码的中文字符串，作为『编码混乱』mojibake 场景的救星。

    典型场景：LibreDWG→DXF 在某些含 GBK/中文的 DWG 上把 GBK 字节误作 Latin-1
    解码，输出 mojibake（如 `鍥句腑妗嗘灦`），每个 item 长度异常大、总字数虚高。
    这种情况 UTF-16LE 扫描救不了（中文是 GBK 编码），必须在原 DWG 字节中
    按 GBK 解码找连续 CJK 字符串。

    v1.5.3 重写：原版本用『连续可打印字符段』切分（min_run + CJK占比>=60%），
    在巴布亚桩基 DWG 上被切成碎段（diversity=0.63 接近乱码 1.0），导致兜底失败。
    实际整个文件 GBK 解码后 43 万 CJK 字符、diversity=0.048（典型真中文），
    切碎是因为每行/每段之间有非 CJK 字符（如 0x00 0x0A 0x0D），原算法按单字符扫
    碰到这些字节就切段，每段只有 5-20 字，段内字符多样性失真。

    新算法（v1.5.3 第二版）：
      * 第一步：用 GBK 双字节正则 `0x81-0xFE + 0x40-0xFE` 匹配所有 GBK 字符位置，
        按"连续 GBK 区域（中间允许 < 4 个非 GBK 字节间隔）"切分。
      * 第二步（严格过滤）：每段必须同时满足
          - 段长 >= min_run (4 字符)
          - 段内『严格 CJK 字符』（0x4E00-0x9FFF 基础平面）占比 >= 80%
          - 段内 CJK 字符数 >= 10（确保段足够长，多样性指标才可靠）
        否则视为 GBK 范围巧合段（dwf/jpeg 字节里 0x81-0xFE+0x40-0xFE 组合
        不一定都是真 CJK 字符，可能落在 GBK 扩展区 A/B/标点区）丢弃。

    返回 (items, stats): items 是字符串列表；stats 包含 cjk_total/cjk_diversity。
    """
    if not raw_bytes:
        return [], {"cjk_total": 0, "cjk_diversity": 1.0, "cjk_unique": 0, "items_count": 0}
    # 用 GBK 双字节模式匹配所有 GBK 字符位置
    # GBK 第一字节 0x81-0xFE, 第二字节 0x40-0xFE (不含 0x7F)
    import re as _re
    gbk_byte_re = _re.compile(rb'[\x81-\xfe][\x40-\xfe]')
    matches = list(gbk_byte_re.finditer(raw_bytes))
    if not matches:
        return [], {"cjk_total": 0, "cjk_diversity": 1.0, "cjk_unique": 0, "items_count": 0}
    # 按"连续 GBK 区域（中间允许 < 4 个非 GBK 字节间隔）"切分
    out = []
    cur_start = matches[0].start()
    cur_end = matches[0].end()
    for m in matches[1:]:
        if m.start() - cur_end <= 4:   # 允许 4 字节内间隔
            cur_end = m.end()
        else:
            # flush + 严格过滤
            seg = raw_bytes[cur_start:cur_end]
            try:
                s = seg.decode("gbk", errors="ignore").strip()
            except Exception:
                s = seg.decode("gb18030", errors="ignore").strip()
            if s and len(s) >= min_run and _gbk_seg_is_real_cjk(s):
                if s not in out:
                    out.append(s)
                if len(out) >= max_per_call:
                    break
            cur_start = m.start()
            cur_end = m.end()
    # flush last
    if len(out) < max_per_call:
        seg = raw_bytes[cur_start:cur_end]
        try:
            s = seg.decode("gbk", errors="ignore").strip()
        except Exception:
            s = seg.decode("gb18030", errors="ignore").strip()
        if s and len(s) >= min_run and _gbk_seg_is_real_cjk(s):
            if s not in out:
                out.append(s)
    # diversity 统计
    cjk_set = set()
    cjk_total = 0
    for s in out:
        for c in s:
            if 0x4e00 <= ord(c) <= 0x9fff:
                cjk_set.add(c)
                cjk_total += 1
    cjk_diversity = (len(cjk_set) / cjk_total) if cjk_total else 1.0
    return out, {
        "cjk_total": cjk_total,
        "cjk_diversity": cjk_diversity,
        "cjk_unique": len(cjk_set),
        "items_count": len(out),
    }

def _gbk_seg_is_real_cjk(seg, min_cjk_ratio=0.7, min_common_chars=2, min_total_cjk=8):
    """判断一个 GBK 解码后的字符串段是否真包含中文字符（不是 GBK 范围巧合段）。

    严格判据（同时满足）：
      1. 段内『严格 CJK 基础平面』字符（0x4E00-0x9FFF）占比 >= 70%
         排除 GBK 扩展区 A/B/标点区字符
      2. 段内 CJK 字符数 >= 8（确保段足够长，多样性指标才可靠）
      3. 段内『中文常用字集』命中数 >= 2
         （top-300 常用字集含"的/了/是/在/有/和"等，确保是真中文而非
         GBK 巧合字符如"鎌 芁 哅 皜"）
         误判案例（v1.5.3 实测）：巴布亚桩基 DWG 的 GBK 段虽然 CJK 基础平面
         字符多，但全是 GBK 巧合字符，无任何中文常用字 → 命中数 0 → 判定乱码。
    """
    if not seg:
        return False
    n = len(seg)
    cjk = 0
    common = 0
    for c in seg:
        cp = ord(c)
        if 0x4E00 <= cp <= 0x9FFF:
            cjk += 1
            if cp in _COMMON_CJK_CHARS:
                common += 1
    if cjk < min_total_cjk:
        return False
    if cjk / max(n, 1) < min_cjk_ratio:
        return False
    if common < min_common_chars:
        return False
    return True

_COMMON_CJK_CHARS = frozenset([
    # 介词/助词/连词（最高频，几乎所有真中文文档都有）
    0x7684, 0x4E86, 0x662F, 0x5728, 0x6709, 0x548C, 0x4E0E, 0x53CA, 0x6216, 0x4F46,
    0x800C, 0x4E5F, 0x5C31, 0x90FD, 0x53C8, 0x8FD8, 0x5DF2, 0x5C06, 0x628A, 0x88AB,
    0x8BA9, 0x4F7F, 0x7ED9, 0x4ECE, 0x5BF9, 0x5230, 0x5411, 0x5F80, 0x7531, 0x4E3A,
    0x56E0, 0x6240, 0x5176, 0x6B64, 0x8FD9, 0x90A3, 0x54EA,
    # 常用动词
    0x8BF4, 0x505A, 0x770B, 0x60F3, 0x53BB, 0x6765, 0x51FA, 0x5165, 0x4E0A, 0x4E0B,
    0x8FDB, 0x9000, 0x56DE, 0x8FC7, 0x8D77, 0x5F00, 0x5173, 0x7528, 0x5403, 0x559D,
    0x7761, 0x4F4F, 0x4E70, 0x5356, 0x7ED9, 0x6253, 0x5199, 0x8BFB, 0x542C, 0x8D70,
    0x8DD1, 0x98DE, 0x5750, 0x7AD9, 0x7B11, 0x54ED, 0x558A, 0x53EB, 0x95EE,
    # 常用名词
    0x4EBA, 0x6211, 0x4F60, 0x4ED6, 0x5979, 0x5B83, 0x4EEC, 0x5BB6, 0x56FD, 0x57CE,
    0x6751, 0x8DEF, 0x8F66, 0x6C34, 0x706B, 0x571F, 0x6728, 0x91D1, 0x77F3, 0x5C71,
    0x6CB3, 0x6D77, 0x5929, 0x5730, 0x65E5, 0x6708, 0x5E74, 0x65F6, 0x5206, 0x79D2,
    0x70B9, 0x4ECA, 0x660E, 0x6628, 0x524D, 0x540E, 0x5DE6, 0x53F3, 0x4E2D, 0x95F4,
    0x5185, 0x5916, 0x91CC, 0x65C1, 0x8FB9,
    # 数词
    0x4E00, 0x4E8C, 0x4E09, 0x56DB, 0x4E94, 0x516D, 0x4E03, 0x516B, 0x4E5D, 0x5341,
    0x767E, 0x5343, 0x4E07, 0x4EBF, 0x51E0, 0x591A, 0x5C11, 0x5927, 0x5C0F, 0x957F,
    0x77ED, 0x9AD8, 0x4F4E, 0x8FDC, 0x8FD1, 0x5BBD, 0x7A84, 0x539A, 0x8584, 0x91CD,
    0x8F7B, 0x5FEB, 0x6162, 0x65E9, 0x665A, 0x65B0, 0x8001, 0x597D, 0x574F,
    # 形容词
    0x7F8E, 0x4E11, 0x70ED, 0x51B7, 0x5E72, 0x6E7F, 0x4EAE, 0x6697, 0x6E05, 0x6D4A,
    0x767D, 0x9ED1, 0x7EA2, 0x9EC4, 0x84DD, 0x7EFF, 0x7D2B, 0x7070, 0x94F6, 0x7EF4,
    # 时间/方位
    0x4E1C, 0x897F, 0x5357, 0x5317, 0x6625, 0x590F, 0x79CB, 0x51AC, 0x5468, 0x53F7,
    # 古文/虚词
    0x7B49, 0x4E4B, 0x4EE5, 0x4E8E, 0x4E8E, 0x4E4E, 0x77E3, 0x54C9, 0x82E5, 0x5219,
    0x7136, 0x867D, 0x76D6, 0x592B, 0x51E1, 0x8BF8,
    # CAD/工程常用字
    0x56FE, 0x8868, 0x53F7, 0x5C42, 0x677F, 0x5899, 0x67F1, 0x6881, 0x57FA, 0x7840,
    0x6869, 0x627F, 0x53F0, 0x914D, 0x7B4B, 0x6DF7, 0x51DD, 0x94A2, 0x710A, 0x63A5,
    0x87BA, 0x6813, 0x9884, 0x57CB, 0x4EF6, 0x7BA1, 0x7EBF, 0x7F06, 0x6865, 0x67B6,
    0x6DB5, 0x6D1E, 0x4E95, 0x5BA4, 0x95E8, 0x7A97, 0x697C, 0x68AF, 0x7535, 0x6C14,
    0x6696, 0x901A, 0x9632, 0x6D88, 0x5B89, 0x5168, 0x56F4, 0x62A4, 0x680F, 0x7F69,
    0x58F3, 0x5957, 0x76D6, 0x5E95, 0x9876, 0x7AEF, 0x5934, 0x5C3E, 0x53E3, 0x9762,
    0x4FA7, 0x89D2, 0x90E8, 0x6BB5, 0x8DE8, 0x8DDD, 0x5F84, 0x7A0B, 0x6BD4, 0x5761,
    0x5EA6,
    # 量词
    0x4E2A, 0x53EA, 0x6761, 0x5757, 0x5F20, 0x7247, 0x5957, 0x7EC4, 0x53F0, 0x8F86,
    0x8258, 0x67B6, 0x5EA7, 0x680B, 0x5E62, 0x6237,
    # 大写数字
    0x58F9, 0x8D30, 0x53C1, 0x8086, 0x4F0D, 0x9646, 0x67D2, 0x634C, 0x7396, 0x62FE,
    # 施工/管理
    0x65BD, 0x5DE5, 0x8BBE, 0x8BA1, 0x76D1, 0x7406, 0x9A8C, 0x6536, 0x62A5, 0x544A,
    0x7B7E, 0x5B57, 0x7AE0, 0x671F, 0x5B8C, 0x6210, 0x672A, 0x534A, 0x6B62, 0x7981,
    0x8BB8, 0x53EF, 0x9700, 0x8981, 0x6C42,
    # 标点
    0x3002, 0xFF0C, 0xFF1B, 0xFF1A, 0xFF1F, 0xFF01, 0xFF08, 0xFF09, 0x3010, 0x3011,
    0x300A, 0x300B, 0x300C, 0x300D, 0x300E, 0x300F, 0xFF5E, 0x2014, 0x2026,
    # 标题/类型
    0x603B, 0x5E73, 0x9762, 0x6C47, 0x603B, 0x7D2F, 0x8BA1, 0x7ED3, 0x679C, 0x8F93,
    0x51FA, 0x8F93, 0x5165, 0x67E5, 0x8BE2, 0x8BB0, 0x5F55, 0x5907, 0x6CE8, 0x9644,
    0x52A0, 0x53D6, 0x5220, 0x9664, 0x4FEE, 0x6539, 0x5B58, 0x53D6, 0,  # 填充
])

def _extract_dwg_utf16_cjk(raw_bytes, min_run=4, max_per_call=4000):
    """从 DWG 二进制字节中扫描 UTF-16LE 编码的中文(CJK)字符串，
    作为 DWG→DXF 转换器丢失 CJK 时的最后兜底。

    实现思路：DWG 文件内文本字段用 UTF-16LE 存储 CJK 字符。当 LibreDWG→DXF
    转换器对中文(CJK)支持有限、转换出的 DXF 文本几乎不含 CJK 字符时，可直接
    在原始 DWG 字节中扫描所有『UTF-16LE 解码后出现 CJK 字符的连续段』。

    假阳性过滤（关键）：
      * 仅把【严格 CJK】(CJK 基本平面 U+4E00–U+9FFF、扩展 A U+3400–U+4DBF、
        扩展 B+ U+20000–U+2FFFF) 算作「中文」；韩文(Hangul)、平/片假名不计入，
        否则二进制噪声被误读为韩文/日文时会整段混入（如 '큡쑫뜰㬕'）。
      * 段内【严格 CJK 占比 >= 60%】才保留；全角 ASCII、CJK 标点、破折号等
        视作「胶水字符」允许出现在段内但不计入中文占比，用于连接连续中文。
      * 段长 >= min_run 才计入，剔除 JPEG/压缩数据偶发误读的短噪声。

    返回 (items, stats): items 是字符串列表；stats 包含
      * cjk_total : 段内严格 CJK 字符总数
      * cjk_diversity : 独立 CJK 字符数 / 总 CJK 字符数（真中文 < 0.6，乱码 ≈ 1.0）
      * 供 chinese_loss 路径用 diversity 判据避免把二进制数据误判为真 CJK
    """
    try:
        text = raw_bytes.decode("utf-16-le", errors="ignore")
    except Exception:
        return []

    def is_strict_cjk(cp):
        return (0x4E00 <= cp <= 0x9FFF          # CJK 基本平面
                or 0x3400 <= cp <= 0x4DBF        # CJK 扩展 A
                or 0x20000 <= cp <= 0x2FFFF)     # CJK 扩展 B+（surrogate 已合并）

    def is_glue(cp):
        # 中文文本里常伴随出现的「胶水」字符：计入连续段但不算中文
        return (0xFF01 <= cp <= 0xFF5E           # 全角 ASCII
                or 0x3000 <= cp <= 0x303F        # 全角空格 + CJK 标点
                or 0x2014 <= cp <= 0x201D        # — – … “ ”
                or 0x2026 <= cp <= 0x2027        # … ‧
                or 0x00B7 <= cp <= 0x00B7        # ·
                or 0x2018 <= cp <= 0x201B)       # ‘ ’ ‛ ‚

    def is_run_char(cp):
        return (0x20 <= cp <= 0x7E               # 半角空格/数字/字母/半角标点
                or is_strict_cjk(cp)
                or is_glue(cp))

    out = []
    cur = []
    cjk_set = set()
    cjk_total = 0

    def flush():
        if len(cur) < min_run:
            return
        cjk = sum(1 for c in cur if is_strict_cjk(ord(c)))
        if cjk / max(len(cur), 1) >= 0.6:        # 严格中文占比达标才保留
            s = "".join(cur).strip()
            if s and s not in out:
                out.append(s)

    for ch in text:
        cp = ord(ch)
        if is_run_char(cp):
            cur.append(ch)
        else:
            flush()
            cur = []
        if len(out) >= max_per_call:
            break
    flush()
    # 统计 diversity（真中文 < 0.6，乱码 ≈ 1.0）
    for s in out:
        for c in s:
            if is_strict_cjk(ord(c)):
                cjk_set.add(c)
                cjk_total += 1
    cjk_diversity = (len(cjk_set) / cjk_total) if cjk_total else 1.0
    return out, {
        "cjk_total": cjk_total,
        "cjk_diversity": cjk_diversity,
        "cjk_unique": len(cjk_set),
        "items_count": len(out),
    }

# =====================================================================
# 手机端桥接入口（Chaquopy 调用）
# =====================================================================
def _gbk_common_ratio(real, stats):
    cjk_total = stats.get("cjk_total", 0)
    common = sum(1 for s in real for c in s
                 if 0x4e00 <= ord(c) <= 0x9fff and ord(c) in _COMMON_CJK_CHARS)
    return common / max(cjk_total, 1)


def extract_cad_android(dxf_path, dwg_path=None):
    """手机端 DWG/DXF 统计主路径（对齐桌面 wordcount.py extract_cad，去除 ODA/OCR/dwggrep/同名PDF）。

    - dxf_path: Kotlin 侧 dwg2dxf 已转换好的 DXF（可能含 LibreDWG 结构缺陷，sanitize 已修复）
    - dwg_path: 原始 DWG 路径（可选，用于中文丢失时的 GBK/UTF-16 原始字节扫描恢复）

    返回 dict：
      items: [str]        文字段落列表（矢量文字）
      pages: int|None     图框/布局页数
      pages_reason: str   页数口径说明
      needs_pdf: bool     是否编码混乱需 PDF 兜底
      encoder_garbled: bool
    """
    text = extract_text_from_dxf(dxf_path)
    items = [ln for ln in text.split("\n") if ln.strip()]

    meta = {"pages": None, "pages_reason": None,
            "needs_pdf": False, "encoder_garbled": False}
    try:
        frames, freason, _paper = count_cad_frames(dxf_path)
        meta["pages"] = frames
        meta["pages_reason"] = freason
    except Exception:
        pass

    # ── 编码异常检测（对齐桌面 extract_cad 密度/garbled/sparse 判定）──
    # 仅在源 DWG 字节可读时做 GBK/UTF-16 恢复；纯英文图纸（本次 28 文件）不触发。
    if dwg_path:
        try:
            with open(dwg_path, "rb") as _f:
                raw_bytes = _f.read()
        except Exception:
            raw_bytes = b""
    else:
        raw_bytes = b""

    try:
        _cur = count_items(items)
        _cur_total = _cur["fe"] + _cur["nc"]
        frames = meta.get("pages") or 1
        _density = _cur_total / max(frames, 1)

        _items_cjk = sum(1 for _it in items for _c in _it if 0x4E00 <= ord(_c) <= 0x9FFF)
        _real_cjk = sum(1 for _it in items for _c in _it if ord(_c) in _COMMON_CJK_CHARS)
        _cjk_bytes = 0
        try:
            _cjk_bytes = sum(1 for _c in raw_bytes.decode("gb18030", "ignore")
                             if 0x4E00 <= ord(_c) <= 0x9FFF)
        except Exception:
            _cjk_bytes = 0
        _garbled = (_items_cjk >= 50) and (_real_cjk / max(_items_cjk, 1) < 0.05)
        _sparse = (_cjk_bytes > 50000 and _real_cjk / max(frames, 1) < 50
                   and _items_cjk > 0 and _cur_total < frames * 300)
        _encoding_loss = bool(_garbled or _sparse)
        _cjk_common_ratio = (_real_cjk / max(_items_cjk, 1)) if _items_cjk else 1.0

        if ((_density > 3000 and _cur_total > frames * 1000
             and _items_cjk >= 50 and _cjk_common_ratio < 0.30)
                or _encoding_loss):
            _real_text = None
            _gbk_real, _gbk_stats = _extract_dwg_gbk_cjk(
                raw_bytes, min_run=4, max_per_call=100000)
            _gbk_cjk_total = _gbk_stats.get("cjk_total", 0)
            _gbk_div = _gbk_stats.get("cjk_diversity", 1.0)
            _gbk_cr = _gbk_common_ratio(_gbk_real, _gbk_stats)
            if (_gbk_real and len(_gbk_real) >= 5 and _gbk_cjk_total >= 200
                    and _gbk_div < 0.6 and _gbk_cr >= 0.15):
                _real_text = _gbk_real
            else:
                _utf16_real, _utf16_stats = _extract_dwg_utf16_cjk(
                    raw_bytes, min_run=4, max_per_call=2000)
                _utf16_cjk_total = _utf16_stats.get("cjk_total", 0)
                _utf16_div = _utf16_stats.get("cjk_diversity", 1.0)
                _utf16_cr = _gbk_common_ratio(_utf16_real, _utf16_stats)
                if (_utf16_real and _utf16_cjk_total >= 200 and _utf16_div < 0.6
                        and len(_utf16_real) >= 5 and _utf16_cr >= 0.15):
                    _real_text = _utf16_real
            if _real_text is not None:
                _real_set = set(_real_text)
                items = list(_real_text)
                meta["encoder_garbled"] = False
            else:
                meta["encoder_garbled"] = True
                meta["needs_pdf"] = True
                items = []
    except Exception:
        pass

    # 清理中间 sanitize 文件
    try:
        _junk = dxf_path + "._sanitized.dxf"
        if os.path.exists(_junk):
            os.remove(_junk)
    except Exception:
        pass

    meta["items"] = items
    return meta


def extract_dxf_json(dxf_path, dwg_path=None):
    """返回 JSON 字符串：{"items":[...],"pages":N,"pages_reason":str,"needs_pdf":bool,"encoder_garbled":bool}"""
    r = extract_cad_android(dxf_path, dwg_path)
    return json.dumps(r, ensure_ascii=False, default=str)


def count_items_json(items):
    """返回 JSON：{"fe":N,"nc":N,"chars":N,"words":N}"""
    r = count_items(items)
    return json.dumps(r, ensure_ascii=False, default=str)


# ==== OLE office 嵌入文字提取（自 cad_ole_ocr.py，桌面同源） ====
import binascii
import io
import zipfile
import olefile

CFB_MAGIC = b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1"

BINARY_GROUPS = {"310", "311", "312", "313", "314", "315",
                 "316", "317", "318", "319"}

def _read_dxf_text(dxf_path):
    try:
        with open(dxf_path, "rb") as f:
            raw = f.read()
    except Exception:
        return ""
    try:
        return raw.decode("utf-8")
    except UnicodeDecodeError:
        return raw.decode("gb18030", errors="replace")

def find_ole_blobs(dxf_path):
    """Return a list of raw CFB byte blobs, one per OLE2FRAME in the DXF."""
    text = _read_dxf_text(dxf_path)
    if not text:
        return []
    lines = text.split("\n")

    blobs = []
    i, n = 0, len(lines)
    while i < n - 1:
        code = lines[i].strip()
        val = lines[i + 1].strip() if i + 1 < n else ""
        if code == "0" and val.upper() == "OLE2FRAME":
            hex_parts = []
            j = i + 2
            while j < n - 1:
                c2 = lines[j].strip()
                v2 = lines[j + 1].strip() if j + 1 < n else ""
                if c2 == "0":
                    break
                if c2 in BINARY_GROUPS and v2:
                    hex_parts.append(v2.replace(" ", ""))
                j += 2
            if hex_parts:
                try:
                    data = binascii.unhexlify("".join(hex_parts))
                except Exception:
                    data = b""
                if data:
                    m = data.find(CFB_MAGIC)
                    blobs.append(data[m:] if m >= 0 else data)
            i = j
            continue
        i += 1
    return blobs

_NS_X = '{http://schemas.openxmlformats.org/spreadsheetml/2006/main}'

_NS_W = '{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'

_NS_A = '{http://schemas.openxmlformats.org/drawingml/2006/main}'

_NS_P = '{http://schemas.openxmlformats.org/presentationml/2006/main}'

def _col_to_num(col):
    n = 0
    for ch in col.upper():
        n = n * 26 + (ord(ch) - ord("A") + 1)
    return n

def _cell_ref(ref):
    """'B12' -> (col=2, row=12). Returns (0, 0) when unparsable."""
    m = re.match(r"([A-Za-z]+)(\d+)", ref or "")
    if not m:
        return (0, 0)
    return (_col_to_num(m.group(1)), int(m.group(2)))

def _get_active_sheet_index(z, names):
    """Return the 0-based index of the worksheet that is *active* (i.e. the one
    a pasted OLE object actually renders inside the CAD drawing). Falls back to 0."""
    if "xl/workbook.xml" in names:
        try:
            import xml.etree.ElementTree as ET
            r = ET.fromstring(z.read("xl/workbook.xml"))
            for el in r.iter():
                at = el.get("activeTab")
                if at is not None:
                    try:
                        return int(at)
                    except Exception:
                        pass
        except Exception:
            pass
    return 0

def _extract_office_cells(zbytes, active_only=False):
    """Return a flat list of (sheet_idx, row, col, value) tuples for the text
    in an Office Open XML zip (xlsx / docx / pptx).

    For an embedded Excel OLE object, the CAD drawing only *renders* the
    workbook's **active worksheet** -- the other sheets are part of the source
    file but are NOT shown. Pass ``active_only=True`` to keep only that one
    sheet (this is what should be counted; otherwise every sheet in the
    workbook is summed and the table is massively over-counted).
    """
    try:
        z = zipfile.ZipFile(io.BytesIO(zbytes))
    except Exception:
        return []
    names = z.namelist()
    cells = []

    if any(n.startswith("xl/") for n in names):           # --- Excel ---
        sheet_files = [nm for nm in sorted(names)
                       if re.match(r"xl/worksheets/sheet\d+\.xml$", nm)]
        active_idx = _get_active_sheet_index(z, names) if active_only else -1
        shared = []
        if "xl/sharedStrings.xml" in names:
            import xml.etree.ElementTree as ET
            try:
                r = ET.fromstring(z.read("xl/sharedStrings.xml"))
                for si in r.findall(_NS_X + "si"):
                    shared.append("".join(t.text or "" for t in si.iter(_NS_X + "t")))
            except Exception:
                pass
        sidx = 0
        for nm in sheet_files:
            if active_only and sidx != active_idx:
                sidx += 1
                continue
            try:
                r = ET.fromstring(z.read(nm))
            except Exception:
                sidx += 1
                continue
            for c in r.iter(_NS_X + "c"):
                ref = c.get("r") or ""
                col, row = _cell_ref(ref)
                t = c.get("t")
                val = None
                if t == "s":
                    v = c.find(_NS_X + "v")
                    if v is not None and v.text is not None:
                        try:
                            i = int(v.text)
                            if 0 <= i < len(shared):
                                val = shared[i]
                        except Exception:
                            pass
                elif t == "inlineStr":
                    parts = [tt.text or "" for tt in c.iter(_NS_X + "t")]
                    if any(parts):
                        val = "".join(parts)
                else:
                    v = c.find(_NS_X + "v")
                    if v is not None and v.text is not None:
                        val = v.text
                if val:
                    cells.append((sidx, row, col, val))
            sidx += 1

    elif any(n.startswith("word/") for n in names):       # --- Word ---
        import xml.etree.ElementTree as ET
        for nm in names:
            if nm == "word/document.xml" or nm.startswith("word/header") or nm.startswith("word/footer"):
                try:
                    r = ET.fromstring(z.read(nm))
                except Exception:
                    continue
                for t in r.iter(_NS_W + "t"):
                    if t.text:
                        cells.append((0, 0, 0, t.text))

    elif any(n.startswith("ppt/") for n in names):        # --- PowerPoint ---
        import xml.etree.ElementTree as ET
        for nm in names:
            if re.match(r"ppt/slides/slide\d+\.xml$", nm):
                try:
                    r = ET.fromstring(z.read(nm))
                except Exception:
                    continue
                for t in r.iter(_NS_A + "t"):
                    if t.text:
                        cells.append((0, 0, 0, t.text))
    return cells

def _collapse_section_titles(cells):
    """Given positioned cells from one embedded workbook, return the text
    values with *repeated section titles* collapsed to a single occurrence.

    A 'repeated section title' is a value that is the top-left (minimum row,
    then minimum column) cell of two or more worksheets in the same workbook.
    Example: a 6-sheet workbook whose sheets 1-3 each carry '建、构筑物一览表'
    in A1 -> counted once instead of three times.  Ordinary data cells (a
    category repeated down a column, etc.) are kept once per cell.
    """
    topleft = {}                 # sheet_idx -> (row, col, val)
    for (s, row, col, val) in cells:
        cur = topleft.get(s)
        if cur is None or (row, col) < (cur[0], cur[1]):
            topleft[s] = (row, col, val)

    title_count = defaultdict(int)
    for (_r, _c, v) in topleft.values():
        title_count[v] += 1
    repeated = {v for v, c in title_count.items() if c >= 2}

    seen = set()
    out = []
    for (s, row, col, val) in cells:
        if val in repeated:
            if val in seen:
                continue
            seen.add(val)
        out.append(val)
    return out

def _extract_package_text(blob_bytes):
    """If the OLE object carries a `package` ZIP with an Office doc, return
    its text fragments (order-preserving, section titles NOT yet collapsed).
    Returns [] when there is no usable package."""
    cells = _extract_office_cells_from_blob(blob_bytes)
    if cells is None:
        return []
    return [v for (_s, _r, _c, v) in cells]

def _extract_office_cells_from_blob(blob_bytes):
    """Return positioned cells from an OLE blob's embedded Office package,
    or None when there is no usable package.

    AutoCAD / LibreDWG export stores the package under either lowercase
    ``package`` or capitalized ``Package`` (and occasionally other variants).
    We match case-insensitively so drawing lists / schedules embedded as Excel
    are not missed."""
    try:
        import olefile
    except Exception:
        return None
    try:
        of = olefile.OleFileIO(blob_bytes)
    except Exception:
        return None
    pkg = None
    try:
        for s in of.listdir():
            if s[0].lower() == "package":
                try:
                    pkg = of.openstream("/".join(s)).read()
                except Exception:
                    continue
                break
    except Exception:
        pass
    finally:
        try:
            of.close()
        except Exception:
            pass
    if not pkg or pkg[:2] != b"PK":
        return None
    return _extract_office_cells(pkg, active_only=True)

def _similar_fragments(a, b, threshold=0.97):
    """Jaccard similarity between two fragment sets; used to decide whether
    two pasted OLE objects are really the same table (CAD export jitter can
    make byte-identical content differ by a cell)."""
    if not a and not b:
        return True
    if not a or not b:
        return False
    inter = len(a & b)
    union = len(a | b)
    return (inter / union) >= threshold if union else True


def extract_ole_office_json(dxf_path):
    """提取 DXF 内 OLE2FRAME 的 office 嵌入文字（xlsx/docx/pptx Package 流），
    对齐桌面 cad_ole_ocr.extract_embedded_text 的 office 路径（含 section title
    collapse + similar-fragments 去重）。返回 JSON：
      {"joined": str, "ole_count": N, "unique_objects": N}
    位图 OLE（无 office package）不在本函数处理，交由 Kotlin 侧 ML Kit OCR。
    """
    blobs = find_ole_blobs(dxf_path)
    per_object = []
    for blob in blobs:
        cells = _extract_office_cells_from_blob(blob)
        texts = _collapse_section_titles(cells) if cells is not None else []
        per_object.append({"texts": texts, "frag_set": set(texts)})
    kept = []
    for o in per_object:
        merged = False
        for ko in kept:
            if _similar_fragments(o["frag_set"], ko["frag_set"]):
                if len(o["frag_set"]) > len(ko["frag_set"]):
                    ko["texts"] = o["texts"]
                    ko["frag_set"] = o["frag_set"]
                merged = True
                break
        if not merged:
            kept.append(o)
    objects_text = [o["texts"] for o in kept]
    joined = "\n".join("\n".join(o) for o in objects_text)
    return json.dumps({"joined": joined, "ole_count": len(blobs),
                       "unique_objects": len(kept)}, ensure_ascii=False, default=str)
