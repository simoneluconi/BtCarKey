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

    buildTypes {
        release {
            isMinifyEnabled = false
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
