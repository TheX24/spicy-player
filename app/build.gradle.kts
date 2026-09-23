plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.tx24.spicyplayer"
    compileSdk = 35

    fun localClientKey(): String {
        val dotEnv = rootProject.file(".env")
        if (!dotEnv.isFile) return ""
        return dotEnv.useLines { lines ->
            lines.map(String::trim)
                .firstOrNull { it.startsWith("SPICY_LYRICS_CLIENT_KEY=") }
                ?.substringAfter('=')?.trim()?.trim('"', '\'').orEmpty()
        }
    }

    defaultConfig {
        applicationId = "com.tx24.spicyplayer.next"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // A publishable (sl_pk_) key, made to ship in clients: SL rate-limits it per viewer IP.
        val key = localClientKey().replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "SPICY_LYRICS_CLIENT_KEY", "\"$key\"")
    }

    // Android logging/clock calls are no-ops in JVM tests, so provider code runs there unchanged.
    testOptions { unitTests.isReturnDefaultValues = true }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging.resources.excludes += "/META-INF/{CONTRIBUTORS.md,LICENSE.md,NOTICE.md,README.md}"

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.activity:activity-compose:1.7.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.core:core-ktx:1.10.1")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.6.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.1")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("io.coil-kt:coil-compose:2.4.0")
    implementation("com.jakewharton.timber:timber:5.0.1")
    implementation("com.atilika.kuromoji:kuromoji-ipadic:0.9.0")
    implementation("com.belerweb:pinyin4j:2.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.1")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.11.0")
    implementation("javax.inject:javax.inject:1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
}
