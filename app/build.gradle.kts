// Not a secret - see the comment on GOOGLE_WEB_CLIENT_ID below.
val GOOGLE_WEB_CLIENT_ID_DEFAULT = "211766917957-motgfultf6r1hdniteg8q1o3b4uued1c.apps.googleusercontent.com"

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.fitnessapp.summary"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.fitnessapp.summary"
        // Health Connect's client library itself needs API 26+. The data this app
        // reads comes from Garmin Connect, which only writes to Health Connect on
        // Android 14+ (API 34) - so on 26..33 the app installs and runs but will
        // report Health Connect as unavailable rather than crashing. See
        // HealthConnectManager.availability().
        minSdk = 26
        targetSdk = 35
        // CI passes -PappVersionCode=<github.run_number>, so each build has a
        // unique, increasing version the in-app updater can compare against.
        // Falls back to 1 for plain local builds where that property isn't set.
        versionCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1
        versionName = "1.0.${versionCode}"

        // Web client ID from Firebase Console -> Authentication -> Sign-in method
        // -> Google (after enabling that provider). Not a secret - it's a public
        // OAuth client identifier, safe to commit like google-services.json - so
        // it's just hardcoded here directly rather than routed through a CI
        // secret. Update this constant once the Google provider is enabled.
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$GOOGLE_WEB_CLIENT_ID_DEFAULT\"")

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        // A fixed, checked-in debug keystore (keystore/debug.keystore) instead of
        // the default machine-generated one. This matters specifically because
        // Google Sign-In validates the calling app's signing certificate (SHA-1)
        // against the one registered in the Firebase/Google Cloud console - if
        // every CI run signed with a fresh random debug key, sign-in would break
        // unpredictably. Using a fixed key keeps the SHA-1 stable across every
        // build forever. This is a debug-only key with well-known default
        // credentials (alias/password "androiddebugkey"/"android") - never used
        // for a release/Play Store build, so there's nothing sensitive about
        // committing it.
        create("fixedDebug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }

        // Real release signing, for store distribution. The keystore itself is NOT
        // committed to the repo (unlike the debug one) - it's kept as a
        // base64-encoded GitHub Actions secret and only materialized on disk during
        // CI. Local builds that don't pass these Gradle properties simply don't get
        // this signingConfig assigned (see buildTypes.release below), so
        // `./gradlew assembleRelease` still works locally, it just produces an
        // unsigned APK that can't be installed as-is.
        if (project.hasProperty("releaseKeystorePath")) {
            create("release") {
                storeFile = file(project.property("releaseKeystorePath") as String)
                storePassword = project.property("releaseKeystorePassword") as String
                keyAlias = project.property("releaseKeyAlias") as String
                keyPassword = project.property("releaseKeystorePassword") as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (project.hasProperty("releaseKeystorePath")) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixedDebug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            // JVM unit tests run against a stub android.jar whose every method throws.
            // The parsers under test are plain Kotlin, but they log through AppLog ->
            // android.util.Log, which would fail the test for a reason that has nothing
            // to do with what it checks.
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // Renames the built APK from the generic "app-debug.apk"/"app-release.apk" to
    // something a person can recognise in their Downloads folder or a browser's
    // download bar. Purely cosmetic - it has no effect on updates or install
    // conflicts, which are governed entirely by applicationId + signing certificate
    // + versionCode (all three stay fixed/increasing regardless of this filename;
    // see the signingConfigs and defaultConfig comments above).
    applicationVariants.all {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "fitness-summary-${buildType.name}.apk"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.4")

    // Health Connect - the actual source of every metric in this app. Garmin
    // Connect writes into it; we only ever read. See health/HealthConnectManager.kt.
    implementation("androidx.health.connect:connect-client:1.1.0")

    // Room (local database - the on-device cache; Firestore is the cloud source of truth)
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Lets suspend functions await() a Firebase Task (Auth/Firestore APIs return Tasks)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    // Firebase (Firestore = cloud database, Auth = Google Sign-In gating access to it)
    implementation(platform("com.google.firebase:firebase-bom:34.16.0"))
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-auth")

    // Google Sign-In via Credential Manager (current recommended API, replaces the
    // old deprecated GoogleSignInClient / GoogleSignInOptions approach)
    implementation("androidx.credentials:credentials:1.5.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.5.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.2.0")

    // Networking for the GitHub Releases update-checker (see update/) and the
    // unofficial Garmin Connect client (see garmin/)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Garmin's official FIT SDK - used only to ENCODE a weight-scale FIT file for
    // upload-service/upload (see garmin/GarminWeightUploader.kt). The FIT binary format
    // (definition messages, scaled fields, CRC) is exactly the kind of thing not worth
    // hand-rolling when the format's owner publishes the encoder on Maven Central.
    implementation("com.garmin:fit:21.214.0")

    // Reads the password-protected .zip that Zepp Life mails as a data export
    // (see scale/ZeppExportParser.kt). Those archives use WinZip AES, which
    // java.util.zip cannot open at all - it fails with "unsupported compression
    // method 99" - so the alternative would be making the user unzip by hand on
    // the phone, which most Android file managers also cannot do.
    implementation("net.lingala.zip4j:zip4j:2.11.5")

    // Encrypted on-device storage for the Garmin OAuth1 token (see garmin/GarminAuthClient.kt).
    // The Garmin *password* is never stored anywhere, only ever held in memory for the
    // single login request; what's persisted is the long-lived OAuth1 token/secret pair,
    // and only this encrypted store, not plain SharedPreferences, is an acceptable place
    // for it.
    implementation("androidx.security:security-crypto:1.1.0")

    testImplementation("junit:junit:4.13.2")
    // The real org.json, for the JVM tests only. android.jar ships org.json too, but
    // `isReturnDefaultValues = true` above turns every one of its methods into a stub that
    // answers 0/null - which would make a JSON parser test pass while parsing nothing.
    // This matters more than usual here: the Garmin heart-rate-zone response is the one
    // payload in this project whose field names could NOT be verified against a reference
    // implementation, so its parser has to be pinned against real JSON.
    testImplementation("org.json:json:20231013")
}
