pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // g-paper is published to mavenLocal only, from ~/git/g-paper.
        mavenLocal()
        google()
        mavenCentral()
    }
}

rootProject.name = "soil"
include(":soil")
include(":seam")
include(":paper")
include(":seam-kit")
include(":markdown")
include(":notesprout")
// The stranger is signed with a key that is never committed (`*.keystore` is ignored), so it
// joins the build only where that key has been generated. See seam-stranger/README.md.
if (file("seam-stranger/stranger.keystore").exists()) {
    include(":seam-stranger")
}
