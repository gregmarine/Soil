plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.symmetricalpalmtree.soil"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.symmetricalpalmtree.soil"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
        }
        release {
            isMinifyEnabled = false
            // The seam trusts exactly one signing key, so debug and release are signed alike,
            // with this Mac's debug keystore. Every Sprout app must be signed the same way.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        // Production code logs through Slog → android.util.Log; on the JVM the framework stubs
        // return defaults instead of throwing "not mocked".
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":seam"))
    // g-paper arrives transitively: `:paper` declares it as `api`.
    implementation(project(":paper"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("net.zetetic:sqlcipher-android:4.6.1")
    implementation("androidx.sqlite:sqlite:2.4.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    testImplementation("junit:junit:4.13.2")
}
