plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
}
android {
    namespace = "ru.igrolov.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "ru.igrolov.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 132
        versionName = "1.2.7"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    androidTestImplementation("androidx.work:work-testing:2.9.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}


