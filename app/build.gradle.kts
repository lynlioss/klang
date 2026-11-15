import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing comes from keystore.properties (local, git-ignored) or the
// KLANG_* environment variables used by the release workflow. Without either,
// builds stay debug-signed so phone builds keep working.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingSetting(property: String, environment: String): String? =
    keystoreProperties.getProperty(property)?.takeIf { it.isNotBlank() }
        ?: System.getenv(environment)?.takeIf { it.isNotBlank() }

val releaseSigningStore = signingSetting("storeFile", "KLANG_KEYSTORE_FILE")

if (releaseSigningStore == null && System.getenv("KLANG_REQUIRE_RELEASE_SIGNING").toBoolean()) {
    throw GradleException(
        "Release signing is required but no keystore was configured. " +
            "Set KLANG_KEYSTORE_FILE or create keystore.properties.",
    )
}

android {
    namespace = "app.lynli.klang"
    compileSdk = 37
    compileSdkMinor = 2
    buildToolsVersion = "37.0.0"
    defaultConfig {
        // Permanent app identity: a different value is a different app to Android
        // and Play. The namespace above stays app.lynli.klang; the two are independent.
        applicationId = "io.github.lynlioss.klang"
        minSdk = 36
        targetSdk = 36
        // Single source of truth for the release: scripts/bump-version.py edits
        // these two lines, and the UI reads them through BuildConfig.
        versionCode = 25
        versionName = "1.0.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        create("release") {
            if (releaseSigningStore != null) {
                storeFile = rootProject.file(releaseSigningStore)
                storePassword = signingSetting("storePassword", "KLANG_KEYSTORE_PASSWORD")
                keyAlias = signingSetting("keyAlias", "KLANG_KEY_ALIAS")
                // Most keystores reuse the store password for the key entry.
                keyPassword = signingSetting("keyPassword", "KLANG_KEY_PASSWORD") ?: storePassword
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = false // ARM64 aapt2 does not support AGP 9 resource optimization reliably.
            signingConfig = signingConfigs.getByName(if (releaseSigningStore != null) "release" else "debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    androidResources { noCompress += listOf("mp3", "m4a") }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs.keepDebugSymbols += "**/*.so"
    }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.compose.material3:material3:1.5.0-rc01")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation("com.materialkolor:material-kolor:4.1.1")
    implementation("org.yaml:snakeyaml:2.6")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
