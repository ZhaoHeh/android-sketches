plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.hehe.sketch.feat.adk"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    packagingOptions {
        resources {
            merges += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES"
            )
        }
    }
}

// The tested AAR is added back as a project dependency for instrumentation variants. Apply the
// optional-backend exclusion to every configuration so that variant does not restore Room through
// ADK's published constraints.
configurations.configureEach {
    exclude(group = "androidx.room")
    // Newer Error Prone annotation jars trigger a bug in the D8 version bundled with AGP 7.4.
    // These are compile-time-only annotations and Material already supplies the compatible 2.15
    // artifact used by the rest of the application.
    exclude(group = "com.google.errorprone", module = "error_prone_annotations")
}

dependencies {
    implementation(project(":sketch-core"))
    implementation(project(":feat-quickjs"))
    implementation(libs.google.adk.kotlin.core) {
        // ADK's Android core variant also exposes its optional ML Kit Gemini Nano model. The
        // mlkit genai-prompt beta currently forces kotlin-stdlib 2.3.21, despite ADK 0.6.0
        // publishing its own APIs at Kotlin 2.1 compatibility. This playground uses cloud Gemini,
        // so keep that unrelated model backend off the classpath instead of suppressing metadata
        // compatibility checks.
        exclude(group = "com.google.mlkit", module = "genai-prompt")
        // ADK also publishes RoomSessionService as an optional backend. This module deliberately
        // uses InMemorySessionService; Room 2.8 requires AGP 8.1.1 and must not force the host
        // experiment app off its AGP 7.4/JDK 11 baseline.
        exclude(group = "androidx.room", module = "room-runtime")
        exclude(group = "androidx.room", module = "room-ktx")
    }
    // ADK only uses OpenTelemetry's stable tracing/context API. Its published 1.56 jars contain
    // class metadata that makes AGP 7.4's D8 crash, so keep the workaround local to this feature
    // and use an API-compatible Java 8 build that the existing Android toolchain can dex.
    implementation("io.opentelemetry:opentelemetry-api:1.32.0") {
        version {
            strictly("1.32.0")
        }
    }
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
