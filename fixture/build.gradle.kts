// A stand-in for YouTube and Instagram in the e2e tests (FeedE2eTest): a
// home screen with a small "Shorts shelf" and a small Explore-style reel
// tile, a Shorts screen and a Reels screen (also opened by a reel link)
// whose player fills the window, all with the view ids YouTube's and
// Instagram's players have. The real apps need the network and an account,
// show ads, and change under us; this doesn't.
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
