import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.isFile) {
        localPropertiesFile.inputStream().use(::load)
    }
}

fun signingProperty(name: String): String? {
    return System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: localProperties.getProperty(name)?.takeIf { it.isNotBlank() }
}

val releaseKeystoreFile = signingProperty("ANDROID_KEYSTORE_FILE")
val hasReleaseSigningConfig = listOf(
    releaseKeystoreFile,
    signingProperty("ANDROID_KEYSTORE_PASSWORD"),
    signingProperty("ANDROID_KEY_ALIAS"),
    signingProperty("ANDROID_KEY_PASSWORD"),
).all { it != null }

android {
    namespace = "com.kei.pulse"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.kei.pulse"
        minSdk = 31
        targetSdk = 36
        versionCode = 303
        versionName = "1.19.6"
    }

    signingConfigs {
        if (hasReleaseSigningConfig) {
            create("release") {
                storeFile = rootProject.file(releaseKeystoreFile!!)
                storePassword = signingProperty("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = signingProperty("ANDROID_KEY_ALIAS")
                keyPassword = signingProperty("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            if (hasReleaseSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // Quiet non-actionable advisories so lintDebug stays clean and meaningful:
        //  - dependency/SDK/Gradle "newer version available" — deps are deliberately pinned for stability
        //  - ObsoleteSdkInt — harmless dead version guards under minSdk 31
        //  - UseKtx (style) / DataExtractionRules (allowBackup=true is intentional)
        // Real-bug checks (UnusedResources, SetWorldReadable, DefaultLocale, …) stay ON.
        disable += setOf(
            "GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion",
            "ObsoleteSdkInt", "UseKtx", "DataExtractionRules",
        )
    }

    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Forward -Dreplay.logcat=<path> to the unit-test JVM so AutoTdpReplayTest.adHocReplayOfASharedCapture can
// replay an arbitrary PulseAutoTdp capture on demand. Only set when provided, so the test's assumeTrue(...)
// skips it otherwise.
tasks.withType<Test>().configureEach {
    providers.systemProperty("replay.logcat").orNull?.let { systemProperty("replay.logcat", it) }
}
