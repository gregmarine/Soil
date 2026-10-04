plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Docsprout talks to one Soil, named when it is built: the permission that guards the seam is
// named after the install. A debug build talks to the debug Soil and a release build to the
// release one.
val soilPackage = "com.symmetricalpalmtree.soil"

android {
    namespace = "com.symmetricalpalmtree.soil.docsprout"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.symmetricalpalmtree.soil.docsprout"
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

    sourceSets {
        // The proofread JVM tests load the real dictionary this APK ships, so the asset
        // directory doubles as a test-resource root (classpath: proofread/en_82765.dict).
        getByName("test") { resources.srcDir("src/main/assets") }
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

    // SymSpellKt: the spell checker behind Proofread (approved 2026-10-04, for this module only,
    // on the footing of pdfbox in :ext-pdf). The bundled dictionary is
    // assets/proofread/en_82765.dict (gzip content, an opaque extension on purpose: AAPT gunzips
    // any `.gz` asset and strips the extension), with its attribution in NOTICE.txt beside it.
    implementation("com.darkrockstudios:symspellkt:3.4.0")

    testImplementation("junit:junit:4.13.2")
}
