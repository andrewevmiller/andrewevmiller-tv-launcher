plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.andrewevmiller.tvlauncher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.andrewevmiller.tvlauncher"
        // The only target is the owner's Hisense Google TV (Android 12, API 31).
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
    buildFeatures {
        compose = true
    }
    lint {
        // Personal sideload, never published. Both are deliberate: WRITE_SECURE_SETTINGS is granted once over
        // adb by scripts/deploy.sh (it lets Settings switch the screensaver), and QUERY_ALL_PACKAGES is how a
        // launcher lists every installed app.
        disable += setOf("ProtectedPermissions", "QueryAllPackagesPermission")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.tv.material)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}
