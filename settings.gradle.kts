pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        maven("https://api.xposed.info/") // LSPosed/Xposed API 专用仓库
    }
}
rootProject.name = "AppSlimAnalyzer"
include(":app")
