plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    // Its own namespace: an R/BuildConfig collision with a consumer is the one thing a library
    // module must never risk.
    namespace = "com.symmetricalpalmtree.soil.paper"
    compileSdk = 35

    defaultConfig {
        minSdk = 29
    }

    buildFeatures {
        // Slog gates on this module's own BuildConfig.DEBUG.
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
    // `:paper` is what Soil and every Sprout app with a paper surface share: the theme, the
    // chrome, the stroke codec and ink-on-rows. It depends on g-paper + androidx only — NEVER on
    // `:soil`, and NEVER on `:seam`.
    // g-paper is `api` because consumers write against PaperView / Stroke. The version pin lives
    // here and nowhere else.
    api("com.symmetricalpalmtree.gpaper:gpaper-core:0.1.68")
    api("com.symmetricalpalmtree.gpaper:gpaper-ratta:0.1.68")
    // `InkScreenActivity` is an `AppCompatActivity` a consumer extends, so appcompat is `api`.
    api("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
}
