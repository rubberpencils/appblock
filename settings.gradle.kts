pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "appblock"
include(":app")
include(":fixture")   // a stand-in for YouTube in the e2e tests

// Turn on the repo's git hooks (.githooks/: the pre-commit test run) in every
// clone that builds, like husky does for npm projects. Git never enables
// in-repo hooks by itself, so a fresh clone would otherwise commit without
// running the tests.
if (File(rootDir, ".git").exists()) {
    ProcessBuilder("git", "config", "core.hooksPath", ".githooks")
        .directory(rootDir).start().waitFor()
}
