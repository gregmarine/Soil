plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.symmetricalpalmtree.soil.seamkit"
    compileSdk = 35

    defaultConfig {
        minSdk = 29
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
    // What both ends of the seam share beyond the interface itself: the documents that carry
    // statements and rows, and an app's way of reaching Soil. It exists because `:seam` depends
    // on nothing but the platform and `:paper` never depends on `:seam`, and this needs both.
    api(project(":seam"))
    api(project(":paper"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // The clipboard's payload is JSON, and it is shared: Soil's Scratch Pad writes it, Notesprout
    // writes and reads it, Docsprout reads it. The one JSON library of the project.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation("junit:junit:4.13.2")
}
