plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "ru.igrolov.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "ru.igrolov.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 106
        versionName = "1.0.4"
    }
    // Fixed DEVELOPMENT signing identity. Never use this key for production releases.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("signing/igrolov-debug.jks")
            storePassword = "android"
            keyAlias = "igrolov-debug"
            keyPassword = "android"
        }
    }
    // Temporary workaround for AGP Lint service crash on release builds.
    // Re-enable release lint before Google Play publication.
    lint { checkReleaseBuilds = false }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
