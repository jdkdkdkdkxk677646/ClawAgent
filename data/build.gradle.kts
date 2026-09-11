plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// v4.0 Phase 2 数据层:会话树的 Room 持久化 + 旧 SharedPreferences JSON 迁移。
// conversation 包(内存模型 + 存储门面)从 :app 原样迁入,包名不变,
// 因此 MainActivity 零改动。

android {
    namespace = "com.openclaw.clawagent.data"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Robolectric 需要(与 :app 相同的配置)
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

// 失败时在 CI 日志里直接吐出 expected/actual（ ComparisonFailure 全文）。
tasks.withType<Test>().configureEach {
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        events("failed")
    }
}

dependencies {
    api("androidx.room:room-runtime:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.json:json:20240205")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240205")
    testImplementation("org.robolectric:robolectric:4.12.1")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("androidx.room:room-testing:2.6.1")
}
