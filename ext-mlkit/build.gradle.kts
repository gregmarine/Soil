plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The extension serves one Soil, named when it is built: a debug extension the debug Soil, a
// release extension the release one.
val soilPackage = "com.symmetricalpalmtree.soil"

android {
    namespace = "com.symmetricalpalmtree.soil.ext.mlkit"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.symmetricalpalmtree.soil.ext.mlkit"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            buildConfigField("String", "SOIL_PACKAGE", "\"$soilPackage.dev\"")
        }
        release {
            isMinifyEnabled = false
            // Soil trusts exactly one signing key: this Mac's debug keystore.
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("String", "SOIL_PACKAGE", "\"$soilPackage\"")
        }
    }

    buildFeatures {
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
    implementation(project(":ext-api"))
    // The one engine dependency, in this module only. Approved for the Notesprout effort.
    implementation("com.google.mlkit:digital-ink-recognition:19.0.0")
    testImplementation("junit:junit:4.13.2")
}
