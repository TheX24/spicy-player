plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.tx24.spicyplayer"
    compileSdk = 37

    fun localClientKey(): String {
        val dotEnv = rootProject.file(".env")
        if (!dotEnv.isFile) return System.getenv("SPICY_LYRICS_CLIENT_KEY").orEmpty()
        return dotEnv.useLines { lines ->
            lines.map(String::trim)
                .firstOrNull { it.startsWith("SPICY_LYRICS_CLIENT_KEY=") }
                ?.substringAfter('=')?.trim()?.trim('"', '\'').orEmpty()
        }
    }

    defaultConfig {
        applicationId = "com.tx24.spicyplayer.next"
        minSdk = 23
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"
        // A publishable (sl_pk_) key, made to ship in clients: SL rate-limits it per viewer IP.
        val clientKey = localClientKey()
        require(clientKey.isBlank() || clientKey.startsWith("sl_pk_")) {
            "SPICY_LYRICS_CLIENT_KEY must be a publishable sl_pk_ key"
        }
        val key = clientKey.replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "SPICY_LYRICS_CLIENT_KEY", "\"$key\"")
    }

    buildTypes {
        getByName("debug") {
            // Its own app next to the release one, so testing never touches the installed release.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "Spicy Player Debug")
        }
        getByName("release") {
            // Shrunk and optimised: Compose runs noticeably slower unoptimised (debug builds are
            // also interpreted at first), so judge smoothness on this build, not on debug.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without the release keystore (a local build), sign with the debug key so it installs
            // over an earlier local release build. Never on CI: a release must fail unsigned.
            if (System.getenv("CI") == null) signingConfig = signingConfigs.getByName("debug")
        }
    }

    val releaseStore = System.getenv("ANDROID_RELEASE_KEYSTORE")
    if (!releaseStore.isNullOrBlank()) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseStore)
                storePassword = System.getenv("ANDROID_RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_RELEASE_KEY_PASSWORD")
            }
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
    }

    // Android logging/clock calls are no-ops in JVM tests, so provider code runs there unchanged.
    testOptions { unitTests.isReturnDefaultValues = true }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    packaging.resources.excludes += "/META-INF/{CONTRIBUTORS.md,LICENSE.md,NOTICE.md,README.md}"

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    // Backdrop blur for SL's glass controls (real blur on Android 12+, a tint below).
    implementation("dev.chrisbanes.haze:haze:1.7.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("com.google.code.gson:gson:2.14.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    // Animated covers: Apple Music's looping HLS artwork, muted, with a disk cache.
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-database:1.11.1")
    implementation("com.jakewharton.timber:timber:5.0.1")
    implementation("com.atilika.kuromoji:kuromoji-ipadic:0.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("com.squareup.retrofit2:converter-gson:3.0.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("javax.inject:javax.inject:1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
}
