plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.netmerge.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.netmerge.android"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.0.1-spike"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // A fixed key for test builds only (it is public on purpose), so a new test build installs over the last
        // one. Real releases will be signed with a private key kept out of this repository.
        getByName("debug") {
            storeFile = file("test-signing.jks")
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
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("junit:junit:4.13.2")
}
