import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing credentials live in <repo>/keystore.properties (gitignored).
// Without them the release build stays unsigned, so any clone can still build.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "xyz.robinroy.appblock"
    compileSdk = 36

    defaultConfig {
        applicationId = "xyz.robinroy.appblock"
        minSdk = 26
        targetSdk = 36
        versionCode = 14
        versionName = "2.1.0"
        // e2e tests (app/src/androidTest) drive the real app on a device.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    if (!keystoreProps.isEmpty) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
        buildTypes {
            release {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // The Shorts-detection rules ship in the APK (v1/rules.signed.json in
    // its assets), so a fresh install works offline before its first fetch.
    sourceSets["main"].assets.srcDir(rootProject.file("rules"))
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // Android's org.json is a stub off the device; this is the real thing for unit tests.
    testImplementation("org.json:json:20240303")
    // e2e: UI Automator finds and taps things on screen across apps (the
    // block wall is drawn over another app), like Playwright for Android.
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}

// ShortcutsTest reads these files straight from disk; declare them so an edit
// to them re-runs the tests instead of leaving the task UP-TO-DATE.
tasks.withType<Test>().configureEach {
    inputs.file("src/main/AndroidManifest.xml")
    inputs.dir("src/main/res/xml")
    inputs.dir(rootProject.file("rules"))
}

// The e2e tests' stand-in for YouTube (fixture/) goes on the device first.
tasks.matching { it.name == "connectedDebugAndroidTest" }.configureEach {
    dependsOn(":fixture:installDebug")
}

// Signs rules/<format>/rules.json (rules/v1/...) into rules.signed.json next
// to it, the file the app bundles and fetches: the rules as a string plus an
// ECDSA P-256 signature over it. First it stamps the rules' "updated" with
// the time now (UTC): the newer file wins on the phone. The private key is
// <repo>/rules-signing-key.pem (gitignored, on key holders' machines only,
// never CI's); the app checks against its public half
// (Storage.RULES_PUBLIC_KEY). Run it after every edit to a rules.json.
tasks.register("signRules") {
    val formats = rootProject.file("rules")
    val keyFile = rootProject.file("rules-signing-key.pem")
    doLast {
        check(keyFile.exists()) { "No rules-signing-key.pem in the repo root: only the maintainer can sign rules" }
        val der = Base64.getMimeDecoder().decode(
            keyFile.readText().replace(Regex("-----[A-Z ]+-----"), ""))
        val key = KeyFactory.getInstance("EC")
            .generatePrivate(PKCS8EncodedKeySpec(der))
        val stamp = Regex("\"updated\": \"([^\"]*)\"")
        // Whole seconds, so it reads "2026-10-22T17:30:00Z".
        val now = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        for (dir in formats.listFiles { f -> f.isDirectory }!!.sorted()) {
            val rules = dir.resolve("rules.json")
            val signed = dir.resolve("rules.signed.json")
            val text = rules.readText()
            check(stamp.containsMatchIn(text)) { "No \"updated\": \"...\" in $rules" }
            // A clock behind the last signing would publish rules every
            // phone ignores as older than the ones it has.
            if (signed.exists()) {
                val last = (JsonSlurper().parseText(signed.readText()) as Map<*, *>)["rules"] as String
                stamp.find(last)?.groupValues?.get(1)?.let {
                    check(now > Instant.parse(it)) { "The clock ($now) isn't past the last signing ($it)" }
                }
            }
            val json = text.replace(stamp, "\"updated\": \"$now\"")
            val sig = Signature.getInstance("SHA256withECDSA").run {
                initSign(key)
                update(json.toByteArray(Charsets.UTF_8))
                Base64.getEncoder().encodeToString(sign())
            }
            rules.writeText(json)
            signed.writeText("{\n  \"rules\": ${JsonOutput.toJson(json)},\n  \"sig\": ${JsonOutput.toJson(sig)}\n}\n")
            println("Signed rules/${dir.name}/rules.json (updated $now)")
        }
    }
}
