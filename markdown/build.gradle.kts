plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.symmetricalpalmtree.soil.markdown"
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

    // No returnDefaultValues on purpose: everything JVM-tested here is pure Kotlin, and a test
    // that strays into android.text should fail loudly ("not mocked") rather than pass against
    // stubs that lie. The renderer and MarkdownDraw are exercised through their consumers.
}

dependencies {
    // `:markdown` is the Markdown engine Notesprout's text objects and headings render through,
    // brought across from Notesprout SN whole. It depends on nothing in this project and on no
    // library beyond the Kotlin stdlib and the android SDK the renderer's spans come from.
    testImplementation("junit:junit:4.13.2")
}
