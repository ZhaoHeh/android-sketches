plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

val quickJsSourceDir = providers.gradleProperty("quickjsSourceDir")
    .orElse(rootProject.layout.projectDirectory.dir("third_party/quickjs").asFile.absolutePath)
    .get()
val quickJsHeader = file(quickJsSourceDir).resolve("quickjs.h")

require(quickJsHeader.isFile) {
    """
    QuickJS source was not found at: $quickJsSourceDir
    Initialize it with `git submodule update --init --recursive`, or pass
    `-PquickjsSourceDir=/absolute/path/to/quickjs`.
    """.trimIndent()
}

android {
    namespace = "dev.hehe.sketch.feat.quickjs"
    compileSdk = 34
    ndkVersion = "26.1.10909125"

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DQUICKJS_SOURCE_DIR=${file(quickJsSourceDir).absolutePath}",
                    "-DANDROID_STL=c++_static"
                )
                cppFlags += listOf("-std=c++17", "-Wall", "-Wextra")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(project(":sketch-core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
