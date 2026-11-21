plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kachur.vpn"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.kachur.vpn"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions.unitTests.isIncludeAndroidResources = true
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.activity:activity:1.10.1")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("junit:junit:4.13.2")
}
