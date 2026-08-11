import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // AGP 9 has built-in Kotlin support and registers the `kotlin` extension itself, so applying
    // org.jetbrains.kotlin.android here would fail with a duplicate-extension error.
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.example.wavesync.core.player"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.bundles.media3)
}
