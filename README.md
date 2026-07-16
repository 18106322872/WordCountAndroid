# 字数统计（Android 离线版）

把桌面端「统一字数统计」程序移植到安卓，**全部计算在手机本地完成，不依赖任何服务器**。
采用「折中方案」：APK 体积小（不含 OCR 模型），OCR 模型（约几十 MB）在**首次用到图片/扫描件时**从托管地址下载一次，之后永久离线。

- 从**千牛 / 微信**长按文件 → 分享 → 选「字数统计」即可统计
- 也支持本机文件选择器批量选取
- 统计口径与 Word「字数统计」对话框一致：字数 / 中文字符和朝鲜语单词 / 非中文单词 / 字符数(不计空格)

---

## 一、支持格式

| 格式 | 手机端 | 说明 |
|------|--------|------|
| PDF（文字层） | ✅ | 文字层直接统计 |
| PDF（整页图片 / 图片页） | ⚠️ | 文字层能统计；图片页 OCR 需要已下载 OCR 模型（pymupdf 渲染受限，见下） |
| DOCX / XLSX / PPTX / TXT | ✅ | 全支持 |
| 图片（png/jpg/bmp/tif/gif/webp） | ✅ | 需 OCR 模型（首次自动下载） |
| AI（Illustrator） | ✅ | 含 PDF 内容的 .ai 可提取 |
| CDR（CorelDRAW X4+） | ✅ | ZIP 容器内 content.xml 提取 |
| DXF（CAD 矢量文字） | ✅ | 矢量文字 + OLE 嵌入对象 |
| 压缩包（zip/7z/tar/gz/bz2/xz） | ✅ | 自动解压、递归统计内部文件 |
| .doc / .ppt（旧版二进制） | ❌ | 依赖 Windows Word/PowerPoint COM，手机无法支持 |
| .dwg（CAD 二进制） | ❌ | 需 dwg2dxf 转换器（Android 无此二进制），请用 .dxf |
| .rar | ⚠️ | 需 unrar/bsdtar，手机端通常不可用 |

> **已知限制**：桌面端用 `pymupdf(fitz)` 做 PDF 图片页栅格化、.ai 读取、导出「无法准确统计内容」PDF。
> Chaquopy 在安卓无 pymupdf 的 wheel，故引擎已对 `fitz` 缺失做降级：
> - PDF 文字层、DOCX/XLSX/PPTX/TXT、图片 OCR、CAD 矢量、压缩包 **照常可用**；
> - PDF 整页图片的「栅格化后 OCR」与导出 PDF 中的 CAD 截图部分 **受限**（有 fitz 时桌面版才完整）。

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
   - `model` 任务：自动从 rapidocr 包里抽出 OCR 模型，打成 `ocr_models.zip`；
   - `apk` 任务：编译出 `app-debug.apk`。
4. 进入 Actions 运行记录 → **Artifacts** → 下载 `app-debug.apk` 和 `ocr_models.zip`。

### 发布 OCR 模型（让 App 能下载）

App 首次用图片时需要下载 `ocr_models.zip`。两种托管方式任选：

- **方式 A（推荐，GitHub 一条龙）**：给仓库打一个 `v*` 标签并推送，工作流会自动创建一个 Release，把 APK 和 `ocr_models.zip` 都作为附件。
  然后修改 `app/src/main/java/com/henry/wordcount/ModelDownloader.kt` 里的：
  ```kotlin
  const val MODEL_URL =
      "https://github.com/<你的用户名>/WordCountAndroid/releases/download/v1.0.0/ocr_models.zip"
  ```
  并把 `ocr_models.zip` 作为该 Release 的附件（工作流已自动上传；若手动发布请自己传）。
- **方式 B（国内直链网盘）**：把 `ocr_models.zip` 传到 **蓝奏云 / 123pan**（免费、给直链），
  把 `MODEL_URL` 改成网盘给的直链地址即可。

> 模型只需下载**一次**，下载完存在手机里，之后完全离线，不再消耗流量。

---

## 三、在手机上安装

1. 把 `app-debug.apk` 传到手机（微信文件传输助手 / USB / 网盘均可）。
2. 手机**设置 → 安全 → 允许安装未知来源应用**，找到对应来源开启。
3. 点开 APK → 安装。
4. 首次打开后，在千牛/微信里长按文件 → 分享 → 「字数统计」即可。
   第一次遇到图片/扫描件时，App 会自动下载 OCR 模型（WiFi 下几十秒），之后永久离线。

---

## 四、本地开发（可选，需 Android Studio）

若你后续装了 Android Studio，直接打开本目录即可编译调试。
发布正式版（release）需在 `app/build.gradle.kts` 配置签名 `signingConfig`。

---

## 五、项目结构

```
WordCountAndroid/
├── .github/workflows/build.yml      # GitHub Actions 云编译（APK + OCR 模型）
├── app/
│   ├── build.gradle.kts             # Chaquopy 配置 + pip 依赖 + 构建时剥离 OCR 模型
│   └── src/main/
│       ├── AndroidManifest.xml      # 分享接收(intent-filter) + 权限
│       ├── python/wordcount.py      # 移植后的统计引擎（与桌面版同算法）
│       ├── java/com/henry/wordcount/
│       │   ├── MainActivity.kt      # Compose UI：列表/汇总/导出/分享接收
│       │   ├── PythonEngine.kt      # Chaquopy 桥接
│       │   └── ModelDownloader.kt   # OCR 模型一次性下载
│       └── res/                     # 主题/字符串/FileProvider 路径
├── build.gradle.kts
└── settings.gradle.kts
```

## 六、构建时发生了什么（折中方案核心）

1. `app/build.gradle.kts` 的 `python { pip { ... } }` 把解析库 + OCR 代码打进 APK；
2. 同一文件的 `merge*Assets.doLast` 任务在打包时**删除** rapidocr 内置的 `.onnx` 模型，
   使 APK 保持小巧；
3. App 运行时若遇到图片，从 `MODEL_URL` 下载 `ocr_models.zip` 并解压到私有目录，
   通过环境变量 `WORDCOUNT_OCR_DIR` 告诉 RapidOCR 用下载的模型——之后完全离线。
