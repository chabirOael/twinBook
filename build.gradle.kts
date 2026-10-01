// Plugins are declared here once so every module resolves the same versions. AGP 9 compiles
// Kotlin itself (built-in Kotlin); declaring kotlin-jvm here also pins the Kotlin Gradle
// plugin that AGP uses to the catalog version.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.lint) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
