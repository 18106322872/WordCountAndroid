pluginManagement {
    repositories {
        google()
        mavenCentral()
        // Chaquopy 插件仓库
        maven("https://maven.chaquo.com")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        // PdfiumAndroid（com.github.barteksc）托管在 jitpack
        maven("https://jitpack.io")
    }
}

rootProject.name = "WordCount"
include(":app")
