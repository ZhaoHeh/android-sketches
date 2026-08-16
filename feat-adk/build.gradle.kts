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

// This playground uses in-memory sessions, so keep ADK's unused optional backends off every
// variant.
configurations.configureEach {
    exclude(group = "androidx.room")
    exclude(group = "com.google.errorprone", module = "error_prone_annotations")
}

dependencies {
    implementation(project(":sketch-core"))
    implementation(project(":feat-quickjs"))
    implementation(project(":feat-gemma"))
    implementation(libs.google.litertlm.android)
    implementation(libs.google.gson)
    implementation(libs.google.adk.kotlin.core) {
        // The selected local model comes from feat-gemma, not ADK's optional ML Kit backend.
        exclude(group = "com.google.mlkit", module = "genai-prompt")
        exclude(group = "androidx.room", module = "room-runtime")
        exclude(group = "androidx.room", module = "room-ktx")
    }
    // Keep the already-tested tracing API version local to the ADK experiment.
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
