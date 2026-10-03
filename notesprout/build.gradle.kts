plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Notesprout talks to one Soil, named when it is built: the permission that guards the seam is
// named after the install. A debug build talks to the debug Soil and a release build to the
// release one.
val soilPackage = "com.symmetricalpalmtree.soil"

android {
    namespace = "com.symmetricalpalmtree.soil.notesprout"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.symmetricalpalmtree.soil.notesprout"
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
            manifestPlaceholders["soilPackage"] = "$soilPackage.dev"
            buildConfigField("String", "SOIL_PACKAGE", "\"$soilPackage.dev\"")
        }
        release {
            isMinifyEnabled = false
            // The seam trusts exactly one signing key: Soil's, which is this Mac's debug keystore.
            signingConfig = signingConfigs.getByName("debug")
            manifestPlaceholders["soilPackage"] = soilPackage
            buildConfigField("String", "SOIL_PACKAGE", "\"$soilPackage\"")
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
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // `:seam` and `:paper` arrive through it, and g-paper through `:paper`.
    implementation(project(":seam-kit"))
    implementation(project(":markdown"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    // The clipboard payload is JSON: the one JSON library of the project, as in Soil.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation("junit:junit:4.13.2")
}
