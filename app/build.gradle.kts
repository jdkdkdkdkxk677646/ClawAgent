plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.openclaw.clawagent"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.openclaw.clawagent"
        minSdk = 26
        targetSdk = 34
        versionCode = 12
        versionName = "4.1.1"
    }

    signingConfigs {
        create("release") {
            // Populated only when CI provides the keystore env vars; local
            // builds and secrets-less CI fall back to the debug signing config
            // below, so `gradle assembleRelease` never hard-fails.
            val storePath = System.getenv("KEYSTORE_FILE") ?: ""
            if (storePath.isNotEmpty()) {
                storeFile = rootProject.file(storePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("KEY_ALIAS") ?: ""
                keyPassword = System.getenv("KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (System.getenv("KEYSTORE_FILE").isNullOrEmpty()) {
                signingConfigs.getByName("debug")
            } else {
                signingConfigs.getByName("release")
            }
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
        viewBinding = true
        // v4.0 Phase 3:Compose UI
        compose = true
    }

    composeOptions {
        // Kotlin 1.9.20 的配套编译器
        kotlinCompilerExtensionVersion = "1.5.4"
    }

    // Robolectric to load Android resources on the JVM (MainActivityTest etc.)
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // v4.0 架构手术:领域层与工具层下沉为纯 Kotlin module。
    implementation(project(":core-agent"))
    implementation(project(":core-tools"))
    // v4.0 Phase 2:会话存储 Room 化(conversation 包已迁入 :data)。
    implementation(project(":data"))
    implementation("androidx.core:core-ktx:1.12.0")
    // Photo Picker (PickVisualMedia) needs the activity 1.7+ result APIs.
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okio:okio:3.9.0")
    // Encrypted key/value store for the API key. Wraps AES-256 in the Android
    // Keystore so the key never lands in plain text on disk.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // v4.0 Phase 3:Compose UI
    implementation(platform("androidx.compose:compose-bom:2024.02.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Unit tests (JVM). org.json:json shadows the throwing Android stub so
    // SseStreamParser tests can parse real JSON on the JVM.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240205")
    // Robolectric so MainActivityTest can use ApplicationProvider and the
    // real SharedPreferences / Context on the JVM (no emulator needed).
    testImplementation("org.robolectric:robolectric:4.12.1")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("androidx.test.ext:junit:1.1.5")
    // ChatViewModelTest: Dispatchers.setMain / UnconfinedTestDispatcher
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}
