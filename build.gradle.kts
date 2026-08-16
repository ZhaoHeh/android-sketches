// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    repositories {
//        maven("https://maven.aliyun.com/repository/google")
//        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:7.4.2")
        // LiteRT-LM ships Kotlin 2.3 / Java 21 bytecode. Keep AGP and Gradle on the
        // JDK 11 baseline, but use a dexer new enough to consume that library.
        classpath("com.android.tools:r8:8.13.19")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.21")
    }
}
