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
// v4.0 Phase 2：数据层（Room 会话存储 + 旧 JSON 迁移）
include(":data")
