plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.relay.spike"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.relay.spike"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-spike"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        getByName("main") {
            // Shared protocol code, unit-tested separately in /protocol.
            java.srcDir("../../protocol/src/main/kotlin")
        }
    }
}
