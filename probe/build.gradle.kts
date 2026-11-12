// A stand-in for "any app on the phone": its traffic goes through the merging VPN like every other app's, so its
// test measures what apps get. It tests itself (no other app involved).
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.netmerge.probe"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.netmerge.probe"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        getByName("debug") {
            storeFile = file("../app/test-signing.jks")
            storePassword = "netmerge-test"
            keyAlias = "test"
            keyPassword = "netmerge-test"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("junit:junit:4.13.2")
}
