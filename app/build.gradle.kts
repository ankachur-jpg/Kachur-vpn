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
        versionCode = 10001
        versionName = "1.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes.getByName("release") {
        // Release APK signing is performed locally after Gradle has built the unsigned package.
        signingConfig = null
    }
    buildTypes.create("phoneCheck") {
        initWith(buildTypes.getByName("release"))
        applicationIdSuffix = ".phonecheck"
        signingConfig = signingConfigs.getByName("debug")
        matchingFallbacks += listOf("release")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets["main"].assets.srcDir(rootProject.file("third_party/amneziawg/build/assets"))
    testOptions.unitTests.isIncludeAndroidResources = true
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(files(rootProject.file("third_party/amneziawg/build/tunnel.aar")))
    implementation("androidx.activity:activity:1.10.1")
    implementation("androidx.collection:collection:1.4.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
