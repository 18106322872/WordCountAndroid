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
    }
}

rootProject.name = "WordCount"
include(":app")
