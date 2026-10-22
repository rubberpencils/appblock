// A stand-in for YouTube in the e2e tests (FeedE2eTest): a home screen with
// a small "Shorts shelf" and a feed screen whose player fills the window,
// both with the view id YouTube's Shorts player has. The real YouTube needs
// the network, signs in, shows ads, and changes under us; this doesn't.
// connectedDebugAndroidTest installs it (app/build.gradle.kts).
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "xyz.robinroy.appblock.fixture"
    compileSdk = 36

    defaultConfig {
        applicationId = "xyz.robinroy.appblock.fixture"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
