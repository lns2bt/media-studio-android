import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.bit.media"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.bit.media"
        minSdk = 29
        targetSdk = 36
        versionCode = 9
        versionName = "0.4"
        ndk { abiFilters += providers.gradleProperty("mediaAbi").getOrElse("arm64-v8a") }
    }

    // Release builds in CI point DEBUG_KEYSTORE at the project's key so updates install in place.
    providers.environmentVariable("DEBUG_KEYSTORE").orNull?.let { path ->
        signingConfigs.getByName("debug") { storeFile = file(path) }
    }

    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // yt-dlp launches executables from the installed app's nativeLibraryDir.
    packaging.jniLibs.useLegacyPackaging = true
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.11.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    val extractorVersion = "0.18.1"
    implementation("io.github.junkfood02.youtubedl-android:library:$extractorVersion")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$extractorVersion")
    testImplementation("junit:junit:4.13.2")
}
