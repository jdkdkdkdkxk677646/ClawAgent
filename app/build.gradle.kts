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
        versionCode = 9
        versionName = "4.0.0-alpha.1"
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
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    // Required by Robolectric to load Android resources on the JVM.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}
