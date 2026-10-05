plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.vakyam.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vakyam.app"
        minSdk = 31
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Galaxy S23 FE (Exynos 2200 / Snapdragon 8 Gen 1) is arm64 only; skipping the other
        // ABIs keeps the MediaPipe + LiteRT-LM native libraries from tripling the APK size.
        ndk {
            abiFilters += "arm64-v8a"
        }

        // false: run Gemma on CPU, where the weights stay memory-mapped from the model file.
        // true: try the GPU first (faster, but copies the weights into GPU memory, roughly
        // +1 GB) and fall back to CPU if the delegate fails to initialise.
        buildConfigField("boolean", "LLM_PREFER_GPU", "false")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Signed with the debug key so `installRelease` works out of the box on a dev device.
            // Replace with a real signing config before distributing.
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

    androidResources {
        // MediaPipe memory-maps the task bundle straight out of the APK; it must stay uncompressed.
        noCompress += "task"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    // Targets Java 17 bytecode with whichever JDK runs Gradle (Android Studio's bundled JBR),
    // rather than a toolchain that would require a separate JDK 17 install.
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Compose + Material 3
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // AndroidX core / lifecycle / navigation
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // CameraX (ImageAnalysis only; no preview surface is bound)
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")

    // Vision: MediaPipe Face Landmarker (478 landmarks incl. iris)
    implementation("com.google.mediapipe:tasks-vision:0.10.29")

    // On-device LLM: LiteRT-LM runtime for the Gemma 4 E2B .litertlm bundle
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.17.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
