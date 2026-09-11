pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "ClawAgent"
include(":app")
// v4.0 架构手术：领域层下沉为纯 Kotlin module（白名单 = 物理边界）
include(":core-agent")
include(":core-tools")
