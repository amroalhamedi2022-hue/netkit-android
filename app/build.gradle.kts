plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val netkitDeviceTest = providers.gradleProperty("netkitDeviceTest").orNull == "true"

android {
    namespace = "com.netkit.local"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.netkit.local"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "0.6.0-alpha"
    }
    buildTypes {
        debug {
            if (netkitDeviceTest) {
                // Keep the one-off device-test APK installable alongside the normal app.
                applicationIdSuffix = ".devicetest"
                versionNameSuffix = "-device-test"
            }
        }
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }
    buildFeatures { compose = true }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}
dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("com.google.zxing:core:3.5.4") // Apache-2.0, QR encoding only
    implementation("commons-net:commons-net:3.13.0")
    implementation("com.github.mwiede:jsch:0.2.26")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.activity:activity-ktx:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
