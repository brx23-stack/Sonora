plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.sonora.player"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sonora.player"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.3.2"

        // FFmpeg natives are bundled for 64-bit ARM phones only (keeps the APK smaller).
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    packaging {
        jniLibs {
            pickFirsts += "**/libc++_shared.so"
            // Compress the native libraries and let the installer unpack them (extractNativeLibs=true).
            // Some phones (Realme/ColorOS) fail to install with "Failed to extract native libraries"
            // when the libraries are loaded straight from the APK.
            useLegacyPackaging = true
            // JavaCPP also ships the `ffmpeg`/`ffprobe` command-line executables. We only use the
            // JNI libraries, and these compressed non-.so files inside lib/ make some phones
            // (e.g. Realme/ColorOS) reject the APK with "Failed to extract native libraries".
            excludes += setOf("**/ffmpeg", "**/ffprobe")
        }
        resources {
            excludes += setOf(
                "META-INF/native-image/**",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1"
            )
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("com.google.guava:guava:33.3.1-android")
    // MP3 conversion (FFmpeg with the LAME encoder, via JavaCV)
    implementation("org.bytedeco:javacv:1.5.11")
    implementation("org.bytedeco:javacpp:1.5.11")
    implementation("org.bytedeco:javacpp:1.5.11:android-arm64")
    implementation("org.bytedeco:ffmpeg:7.1-1.5.11")
    implementation("org.bytedeco:ffmpeg:7.1-1.5.11:android-arm64")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
