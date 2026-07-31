// AlignTool 手机版
// 纯 Kotlin + 纯 XML(OOXML) 实现，不引入 Chaquopy / Python / poi-ooxml。
// 复用 WordCountAndroid 的 Gradle 骨架(minSdk 26 / arm64 / packaging 排除)。
plugins {
    id("com.android.application") version "8.5.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}
