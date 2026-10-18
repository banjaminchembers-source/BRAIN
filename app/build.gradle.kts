plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ai.jarvis.offline"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.jarvis.offline"
        minSdk = 26
        targetSdk = 35

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
        versionCode = 1
        versionName = "0.1.0"
    }
}

dependencies {
    implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.8")
}
