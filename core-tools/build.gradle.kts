plugins {
    id("org.jetbrains.kotlin.jvm")
}

// v4.0 工具层:六只纯 JVM 爪子(计算器/时钟/笔记/抓取/搜索/规划)。
// 只依赖 core-agent 的接口,不碰 Android——工具的全部业务逻辑在 CI 上可测。

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core-agent"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240205")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240205")
}
