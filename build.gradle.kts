plugins {
    alias(libs.plugins.android.application) apply false
    // AGP 9 compiles Kotlin itself (https://developer.android.com/build/migrate-to-built-in-kotlin).
    // Declaring KGP here only raises the Kotlin version Gradle resolves for it: AGP 9.5.1 asks for 2.2.10.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
