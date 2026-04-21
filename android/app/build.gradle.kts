import java.util.Properties

plugins {
    id("com.android.application")
    id("kotlin-android")
    id("dev.flutter.flutter-gradle-plugin")
}

// Read API key from local.properties (never commit the actual key)
val localProps = Properties()
rootProject.file("local.properties").takeIf { it.exists() }
    ?.inputStream()?.use { localProps.load(it) }
val geminiSemanticEnabled = localProps
    .getProperty("gemini.semantic.enabled", "false")
    .trim()
    .equals("true", ignoreCase = true)
val geminiVisionEnabled = localProps
    .getProperty("gemini.vision.enabled", "false")
    .trim()
    .equals("true", ignoreCase = true)

android {
    namespace = "com.ketan.slam"
    compileSdk = 34
    ndkVersion = "25.1.8937393"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        @Suppress("DEPRECATION")
        jvmTarget = "1.8"
    }

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.ketan.slam"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // LLM assistant key + model. We accept either the legacy `gemini.*`
        // names or the newer `llm.assistant.*` names so existing
        // local.properties files keep working. The first non-blank value wins.
        val llmApiKey = listOf(
            localProps.getProperty("llm.assistant.api.key", ""),
            localProps.getProperty("gemini.api.key", "")
        ).firstOrNull { it.isNotBlank() } ?: ""
        val llmModel = listOf(
            localProps.getProperty("llm.assistant.model", ""),
            localProps.getProperty("gemini.model", "")
        ).firstOrNull { it.isNotBlank() } ?: "gemini-2.0-flash"
        val llmEndpoint = localProps.getProperty(
            "llm.assistant.endpoint",
            "https://generativelanguage.googleapis.com/v1beta/models/"
        )

        buildConfigField("String", "GEMINI_API_KEY", "\"$llmApiKey\"")
        buildConfigField("String", "GEMINI_MODEL", "\"$llmModel\"")
        buildConfigField("String", "LLM_ENDPOINT_BASE", "\"$llmEndpoint\"")
        buildConfigField(
            "boolean", "GEMINI_SEMANTIC_CORRECTOR_ENABLED",
            geminiSemanticEnabled.toString()
        )
        buildConfigField(
            "boolean", "GEMINI_VISION_UPDATES_ENABLED",
            geminiVisionEnabled.toString()
        )
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    //dont compress the tflite file
    androidResources {
        noCompress.add("tflite")
    }
}

flutter {
    source = "../.."
}

dependencies {
    // ARCore
    implementation("com.google.ar:core:1.41.0")

    // TensorFlow Lite for YOLOv11 (REPLACING ML Kit)
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.14.0")

    // Camera and image processing
    implementation("androidx.camera:camera-core:1.3.1")
    implementation("androidx.camera:camera-camera2:1.3.1")
    implementation("androidx.camera:camera-lifecycle:1.3.1")

    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.20")

    // AndroidX
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")

    // Coroutines for async operations
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // ML Kit Text Recognition for OCR
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:1.9.20")
}
