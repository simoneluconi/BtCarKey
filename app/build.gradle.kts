plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.simoneluconi.btcarkey"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.simoneluconi.btcarkey"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    // Upload key for Google Play, from the user's own ~/.gradle/gradle.properties (never in the repo).
    // Without it release builds come out unsigned, and scripts\release.cmd refuses to run.
    val uploadStore = providers.gradleProperty("BTCARKEY_STORE_FILE").orNull
    signingConfigs {
        if (uploadStore != null) {
            create("upload") {
                storeFile = file(uploadStore)
                storePassword = providers.gradleProperty("BTCARKEY_STORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("BTCARKEY_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("BTCARKEY_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("upload")
        }
    }

    // android.util.Log (EventLog) returns defaults in JVM tests instead of throwing
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    // Connects while the app is in use and lets go in the background (battery)
    implementation(libs.lifecycle.process)
    implementation(libs.core.ktx)
    implementation(libs.coroutines.android)
    // GATT client: request queue, MTU split, link-loss reconnection (BSD-3-Clause)
    implementation(libs.nordic.ble)
    // Fingerprint or the phone's PIN before the device key is shown or exported
    implementation(libs.biometric)
    // biometric pulls an old fragment that rejects the request codes of current activity-compose
    implementation(libs.fragment)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
