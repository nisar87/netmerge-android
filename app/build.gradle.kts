plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.netmerge.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.netmerge.android"
        minSdk = 29
        targetSdk = 36
        // a release sets both from its tag (release.yml: -PversionName=0.2.0-beta.1 -PversionCode=3)
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 3
        versionName = (project.findProperty("versionName") as String?) ?: "0.2.0-beta.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Releases are signed with a private key that never enters this repository: the release workflow puts it on the
    // build machine from GitHub's encrypted secrets and names it in these variables. Without them (test builds,
    // emulator runs) the public test key is used.
    val releaseKey = System.getenv("NETMERGE_KEYSTORE")?.takeIf { file(it).exists() }

    signingConfigs {
        // A fixed key for test builds only (it is public on purpose), so a new test build installs over the last one.
        getByName("debug") {
            storeFile = file("test-signing.jks")
            storePassword = "netmerge-test"
            keyAlias = "test"
            keyPassword = "netmerge-test"
        }
        if (releaseKey != null) create("release") {
            storeFile = file(releaseKey)
            storePassword = System.getenv("NETMERGE_STORE_PASSWORD")
            keyAlias = System.getenv("NETMERGE_KEY_ALIAS")
            keyPassword = System.getenv("NETMERGE_KEY_PASSWORD")
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
            signingConfig = signingConfigs.getByName(if (releaseKey != null) "release" else "debug")
            // test builds: tests on the phone read the app's own files ("adb shell run-as"); never in a release
            isDebuggable = releaseKey == null
        }
    }

    splits {
        // one APK per processor type as well: the phone (arm64) gets only its own engine (about half the size)
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
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
