plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.example.metafix"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.metafix"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }
}

dependencies {
    implementation(project(":mp4engine"))
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
}
