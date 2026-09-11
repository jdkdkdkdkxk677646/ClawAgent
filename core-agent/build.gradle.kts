plugins {
    id("org.jetbrains.kotlin.jvm")
}

// v4.0 领域层:agent 循环 + OpenAI 协议 + 传输 + 工具接口/注册表。
// 纯 JVM——不允许出现任何 android.* import,这是 Phase 1 手术的硬约束,
// 也是"agent 循环可以 100% 单元测试"的前提。

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Android runtime 自带 org.json,但纯 JVM module 必须显式依赖;
    // 与 CI 测试用的 org.json:json 版本保持一致,序列化行为不漂移。
    implementation("org.json:json:20240205")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240205")
}
