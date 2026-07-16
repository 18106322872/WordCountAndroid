plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

android {
    namespace = "com.henry.wordcount"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.henry.wordcount"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        // 只编常用手机架构，缩小体积
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        // Chaquopy：内嵌 Python + 解析库；OCR 模型在构建时被剥离，运行时从 GitHub Release 下载
        python {
            version = "3.11"
            pip {
                install("pdfminer.six")
                install("python-docx")
                install("pillow")
                install("openpyxl")
                install("python-pptx")
                install("ezdxf")
                install("olefile")
                install("rapidocr_onnxruntime")
                install("opencv-python")
                install("onnxruntime")
                install("py7zr")
                // 注：pymupdf(fitz) 在 Chaquopy 无 Android wheel，故不引入；
                // 引擎已对 fitz 缺失做降级（PDF 文字层/图片OCR/.ai/导出PDF 相应降级）。
            }
        }

        // 接收千牛/微信分享时读取文件所需的权限
        manifestPlaceholders["appName"] = "字数统计"
    }

    // Chaquopy 原生库兼容 AGP 8 的打包方式
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("debug") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    composeCompiler {
        // 稳定版 Compose BOM 2024.06.00
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
}

// ---- 折中方案核心：构建时剥离 rapidocr 内置 OCR 模型，运行时由 App 下载 ----
android.applicationVariants.all {
    val variantName = name
    tasks.named("merge${variantName.replaceFirstChar { it.uppercase() }}Assets") {
        doLast {
            val tree = fileTree(project.buildDir) {
                include("**/rapidocr_onnxruntime/**/*.onnx")
            }
            var removed = 0
            tree.visit { f ->
                if (!f.isDirectory) {
                    f.file.delete()
                    removed++
                }
            }
            println("WordCount: stripped $removed OCR model file(s) from APK (downloaded at runtime)")
        }
    }
}
