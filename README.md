# 字数统计（Android 离线版）

把桌面端「统一字数统计」程序移植到安卓，**全部计算在手机本地完成，不依赖任何服务器、不需要任何账号**。

- 文档类（PDF 文字层 / DOCX / XLSX / PPTX / TXT / CDR / DXF / 压缩包）由内嵌 Python 离线统计；
- **图片 OCR 用 Kotlin 层 Tesseract（tess-two）完成**，中文模型随 APK 内置，**完全离线、连不上网也能用**，适配无 GMS 的华为手机。

统计口径与 Word「字数统计」对话框一致：字数 / 中文字符和朝鲜语单词 / 非中文单词 / 字符数(不计空格)

---

## 一、支持格式

| 格式 | 手机端 | 说明 |
|------|--------|------|
| PDF（文字层） | ✅ | 文字层直接统计 |
| PDF（整页图片 / 图片页） | ⚠️ | 文字层能统计；图片页 OCR 受 pymupdf 限制（见下「已知限制」） |
| DOCX / XLSX / PPTX / TXT | ✅ | 全支持 |
| 图片（png/jpg/jpeg/bmp/tif/tiff/gif/webp） | ✅ | 内嵌 Tesseract 离线 OCR（中文模型已打包进 APK） |
| AI（Illustrator） | ⚠️ | 含 PDF 内容的 .ai 可提取；否则降级 |
| CDR（CorelDRAW X4+） | ✅ | ZIP 容器内 content.xml 提取 |
| DXF（CAD 矢量文字） | ✅ | 矢量文字 + OLE 嵌入对象 |
| 压缩包（zip/tar/gz/bz2/xz） | ✅ | 自动解压、递归统计内部文件 |
| .7z / .rar | ⚠️ | 需对应解压工具，手机端通常不可用，会提示不支持 |
| .doc / .ppt（旧版二进制） | ❌ | 依赖 Windows Word/PowerPoint COM，手机无法支持 |
| .dwg（CAD 二进制） | ❌ | 需 dwg2dxf 转换器（Android 无此二进制），请用 .dxf |

> **已知限制**：桌面端用 `pymupdf(fitz)` 做 PDF 图片页栅格化、.ai 读取、导出「无法准确统计内容」PDF。
> Chaquopy 在安卓无 pymupdf 的 wheel，故引擎已对 `fitz` 缺失做降级：
> - 文档文字层、DOCX/XLSX/PPTX/TXT、图片 OCR（Tesseract）、CAD 矢量、zip 压缩包 **照常可用**；
> - PDF 整页图片的「栅格化后 OCR」与「导出无法准确统计内容」PDF **受限**（有 fitz 时桌面版才完整）。
> - Tesseract 中文识别准确率一般（对清晰印刷体较好，对艺术字/手写/复杂排版较弱），但能离线工作。

---

## 二、你不需要装 Android Studio

编译在 GitHub 云端完成。你只需要一个**免费的 GitHub 账号**。

### 步骤

1. 在 GitHub 新建一个**空仓库**（例如 `WordCountAndroid`）。
2. 把本目录全部内容推上去 —— **两种方式任选其一**：
   - **方式一（双击即可）**：直接**双击本目录里的 `start_push.bat`**，黑窗口会提示你粘贴仓库地址，把
     `https://github.com/<你的用户名>/WordCountAndroid.git` 粘进去回车即可。
   - **方式二（命令行）**：在本目录空白处右键 → **Git Bash Here**，输入：
     ```bash
     bash push.sh https://github.com/<你的用户名>/WordCountAndroid.git
     ```
     （也可只输 `bash push.sh`，随后按提示粘贴地址）
3. 推送后，GitHub 自动在 **Actions** 标签页编译：
   - `apk` 任务：下载 Tesseract 中文模型进 assets，编译出 `app-debug.apk`（模型已内置）。
4. 进入 Actions 运行记录 → **Artifacts** → 下载 `app-debug.apk`。（工作流也会自动发一个 Release 挂着 APK。）

> 没有任何「运行时下载模型」的步骤——OCR 模型在编译时就打进 APK 了，安装即用、永久离线。

---

## 三、在手机上安装

1. 把 `app-debug.apk` 传到手机（微信文件传输助手 / USB / 网盘均可）。
2. 手机**设置 → 安全 → 允许安装未知来源应用**，找到对应来源开启。
3. 点开 APK → 安装。
4. 首次打开后，在千牛/微信里长按文件 → 分享 → 「字数统计」即可。
   图片会**本地离线 OCR**识别后统计，无需联网、无需下载任何东西。

---

## 四、本地开发（可选，需 Android Studio）

若你后续装了 Android Studio，直接打开本目录即可编译调试。
发布正式版（release）需在 `app/build.gradle` 配置签名 `signingConfig`。

---

## 五、项目结构

```
WordCountAndroid/
├── .github/workflows/build.yml      # GitHub Actions 云编译（下载 OCR 模型 + 编译 APK）
├── app/
│   ├── build.gradle                 # Chaquopy 配置 + pip 依赖 + tess-two 依赖
│   └── src/main/
│       ├── AndroidManifest.xml      # 分享接收(intent-filter) + 权限
│       ├── python/wordcount.py      # 移植后的统计引擎（与桌面版同算法）
│       ├── assets/tessdata/         # Tesseract 中文模型（CI 自动下载，不入库）
│       └── java/com/henry/wordcount/
│           ├── MainActivity.kt      # Compose UI：列表/汇总/导出/分享接收
│           ├── PythonEngine.kt      # Chaquopy 桥接
│           └── OcrEngine.kt         # 内嵌 Tesseract 离线 OCR
├── build.gradle.kts
└── settings.gradle.kts
```

## 六、构建时发生了什么

1. `app/build.gradle` 的 `chaquopy { ... pip { ... } }` 把解析库（pdfminer/python-docx/openpyxl/…）打进 APK；
2. 同一文件的 `implementation 'com.rmtheis:tess-two:9.1.0'` 引入 Tesseract 安卓引擎；
3. CI 在编译前把 `chi_sim.traineddata` 下载进 `app/src/main/assets/tessdata/`，随 APK 内置；
4. App 首次运行时把模型从 assets 拷到私有目录，之后**完全离线**做图片 OCR。
