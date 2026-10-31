plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ryzellx.boardcoach.mod"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ryzellx.boardcoach.mod"
        minSdk = 26
        targetSdk = 34
        versionCode = 17
        versionName = "1.7.3"
    }

    // Kunci TETAP (disimpan di repo) supaya update tidak bentrok tanda tangan.
    // Kalau kuncinya berubah, Android menolak install ("App not installed").
    signingConfigs {
        create("stable") {
            storeFile = file("../keystore/boardcoach.p12")
            storePassword = "boardcoach2026"
            keyAlias = "boardcoach"
            keyPassword = "boardcoach2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stable")
        }
        debug {
            // debug pakai kunci yang sama → build debug & release bisa saling update
            signingConfig = signingConfigs.getByName("stable")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // Lint release bikin OOM Metaspace di runner — matikan (bukan blocker build).
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.webkit:webkit:1.11.0")
}
