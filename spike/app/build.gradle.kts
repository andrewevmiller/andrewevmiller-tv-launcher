plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.andrewevmiller.spike"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.andrewevmiller.spike"
        // Same device as :app: Hisense Google TV, Android 12 (API 31).
        minSdk = 31
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Personal sideload: sign release with the debug key so `assembleRelease` installs directly.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// Deliberately no dependencies: plain android.app.Activity + framework Views, so there is nothing
// to resolve beyond AGP and the Kotlin stdlib that the Kotlin plugin adds itself.
