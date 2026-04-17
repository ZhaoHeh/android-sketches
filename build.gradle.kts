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
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.8.22")
    }
}
