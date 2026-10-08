// Plain Kotlin/JVM build so the protocol can be unit-tested without the Android SDK.
// The Android app compiles these same sources directly (see android/app/build.gradle.kts).
plugins {
    kotlin("jvm") version "2.0.21"
}

// Emit Java 17 bytecode (what the Android build uses) from whatever JDK 17+ is installed.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Android ships org.json in the platform, so the app must not bundle it.
    // Here it is compile-only, and provided for tests.
    compileOnly("org.json:json:20240303")
    testImplementation("org.json:json:20240303")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    systemProperty("testVectors", file("test-vectors/messages.jsonl").absolutePath)
}
