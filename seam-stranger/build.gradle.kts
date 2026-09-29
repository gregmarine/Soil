plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The check of the seam's guards: the same small app, built twice.
//   friend   — signed with the key Soil is signed with. It must be answered.
//   stranger — signed with a key of its own. It must be refused at the bind.
// It asks the debug Soil (`…soil.dev`) by default; `-PsoilPackage=…` names another.
val soilPackage = (findProperty("soilPackage") as String?) ?: "com.symmetricalpalmtree.soil.dev"

android {
    namespace = "com.symmetricalpalmtree.soil.stranger"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.symmetricalpalmtree.soil.seamcheck"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        manifestPlaceholders["soilPackage"] = soilPackage
        buildConfigField("String", "SOIL_PACKAGE", "\"$soilPackage\"")
    }

    signingConfigs {
        // Generated on this machine and never committed; see README.md.
        create("stranger") {
            storeFile = file("stranger.keystore")
            storePassword = "stranger"
            keyAlias = "stranger"
            keyPassword = "stranger"
        }
    }

    flavorDimensions += "signer"
    productFlavors {
        create("friend") {
            dimension = "signer"
            applicationIdSuffix = ".friend"
            signingConfig = signingConfigs.getByName("debug")
        }
        create("stranger") {
            dimension = "signer"
            applicationIdSuffix = ".stranger"
            signingConfig = signingConfigs.getByName("stranger")
        }
    }

    buildTypes {
        debug {
            // The flavour decides the key, not the build type.
            signingConfig = null
        }
        release {
            isMinifyEnabled = false
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
}

dependencies {
    implementation(project(":seam"))
}
