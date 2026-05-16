plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jlleitschuh.gradle.ktlint")
}

ktlint {
    // Pin a recent ktlint that matches the Kotlin 1.9.x toolchain we
    // build with; bumping it should be a deliberate PR. `verbose`
    // makes the CI output actionable when a rule fires.
    version.set("1.3.1")
    android.set(true)
    verbose.set(true)
    outputToConsole.set(true)
    ignoreFailures.set(false)
    filter {
        // ktlint walks `build/` by default which pulls in generated
        // Compose code. Exclude it so the gate stays focused on
        // hand-written sources.
        exclude { it.file.path.contains("/build/") }
    }
}

android {
    namespace = "dev.xd.bluetrack"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.xd.bluetrack"
        minSdk = 29
        targetSdk = 34
        versionCode = 2
        versionName = "2.0.0"
    }

    signingConfigs {
        // Release signing pulls its inputs from env vars so the keystore
        // never lives in the repo. The release CI workflow base64-decodes
        // `ANDROID_KEYSTORE_BASE64` into a file and points
        // `ANDROID_KEYSTORE_PATH` at it.
        //
        // If the env vars are unset (local PR builds, every CI lane that
        // is not the release workflow), this config stays unconfigured
        // and `assembleRelease` produces `app-release-unsigned.apk`
        // exactly as before — which keeps the R8 smoke test in Android
        // CI working without secret leakage.
        create("release") {
            val keystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Only attach the signing config when the keystore env vars
            // were resolved above. Otherwise leave it null so AGP falls
            // back to the unsigned APK output.
            if (!System.getenv("ANDROID_KEYSTORE_PATH").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3:1.3.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // X25519 + HKDF-SHA256 for the BLE feedback channel handshake. The
    // platform JCE supplies AES-GCM; BC carries the parts that are not on
    // the public Android API surface for minSdk 29 (X25519 KeyAgreement
    // arrived in API 33, so we use BC's deterministic primitive directly).
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    // Tiny JSON lib for the cross-platform golden-vector fixture loader.
    testImplementation("org.json:json:20240303")
}
