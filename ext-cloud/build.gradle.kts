plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// The extension serves one Soil, named when it is built: a debug extension the debug Soil, a
// release extension the release one.
val soilPackage = "com.symmetricalpalmtree.soil"

android {
    namespace = "com.symmetricalpalmtree.soil.ext.cloud"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.symmetricalpalmtree.soil.ext.cloud"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // The Google OAuth client, the desktop-app type, read from the shell at build time: the
        // same two variables Notesprout reads (decision 2026-10-03: the existing client). Compiled
        // only into this extension's APK; Soil never sees them. Blank: the provider reports
        // `configured = false` and Soil says so.
        buildConfigField("String", "DRIVE_CLIENT_ID", "\"${System.getenv("DRIVE_CLIENT_ID") ?: ""}\"")
        buildConfigField("String", "DRIVE_CLIENT_SECRET", "\"${System.getenv("DRIVE_CLIENT_SECRET") ?: ""}\"")
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            buildConfigField("String", "SOIL_PACKAGE", "\"$soilPackage.dev\"")
            // A separate root, so a dev build never mingles test files under the real tree.
            buildConfigField("String", "ROOT_FOLDER_NAME", "\"Soil Dev\"")
        }
        release {
            isMinifyEnabled = false
            // Soil trusts exactly one signing key: this Mac's debug keystore.
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("String", "SOIL_PACKAGE", "\"$soilPackage\"")
            buildConfigField("String", "ROOT_FOLDER_NAME", "\"Soil\"")
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // The contract, the seam's row encoding for the store Soil lends, and the shared chrome for
    // the connect screen. The only module with INTERNET: no OkHttp, no Gson, no Google library.
    implementation(project(":ext-api"))
    implementation(project(":seam-kit"))
    implementation(project(":paper"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
}
