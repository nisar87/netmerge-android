plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.netmerge.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.netmerge.android"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.0"
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

    buildTypes {
        // The build for real phones: shrunk (about a tenth of the debug build, which carries every Material icon
        // and all of Compose unshrunk; a 43 MB debug build failed to install on the test phone) and signed with the
        // test key until there is a release key.
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
            // tests on the phone read the app's own files ("adb shell run-as"); off for a real release
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(files("libs/engine.aar")) // built from engine/ by gomobile (see CI)
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("junit:junit:4.13.2")
}
