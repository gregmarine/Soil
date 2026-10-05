plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.symmetricalpalmtree.soil.bibleref"
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
}

dependencies {
    // `:bible-ref` is the one reader of a Bible reference: the canon, the parser, the wire and
    // the scanner that finds references in prose. Pure Kotlin, brought across from Notesprout
    // SN's Bible extension; it depends on nothing in this project and on no library beyond the
    // Kotlin stdlib, so Docsprout, Notesprout, Soil and Biblesprout all read a reference alike.
    testImplementation("junit:junit:4.13.2")
}
