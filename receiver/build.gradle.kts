import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.wifistream.receiver"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wifistream.receiver"
        minSdk = 28
        targetSdk = 35
        versionCode = 14
        versionName = "1.4.4"
        // ARM only: every phone and TV this runs on. Leaving out the emulator's x86 keeps the APK smaller.
        ndk { abiFilters += setOf("arm64-v8a", "armeabi-v7a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Personal sideloaded app: sign release with the debug key for now.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // Compressed native libraries: a much smaller download, unpacked once at install.
        jniLibs { useLegacyPackaging = true }
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
            )
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":protocol"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    // Lock-screen and headset controls, and music that keeps playing in the background.
    implementation(libs.androidx.media3.session)
    // The compatibility engine for files the main player cannot play (WMV, RMVB, VC-1, interlaced MPEG-2, styled subtitles).
    implementation(libs.libvlc)
    implementation(libs.okhttp)
    // Sharing side: the small HTTP server other devices play from.
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.partial.content)
    implementation(libs.ktor.server.auto.head.response)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.coil.compose)
    // Software decoding for audio the device cannot play itself (DTS, TrueHD, AC3 on phones).
    implementation(libs.jellyfin.media3.ffmpeg)
    // QR codes: drawn on the TV (ZXing), scanned on the phone (Google's scanner, no camera permission needed).
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}
