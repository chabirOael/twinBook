import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.chabiroael.twinbook"
    compileSdk {
        version = release(libs.versions.compileSdk.get().toInt()) {
            minorApiLevel = libs.versions.compileSdkMinor.get().toInt()
        }
    }
    buildToolsVersion = libs.versions.buildTools.get()

    defaultConfig {
        // Provisional application ID, may change before release.
        applicationId = "io.github.chabiroael.twinbook"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Probes that only tools/ scripts run (capture kill and pull checks).
        testInstrumentationRunnerArguments["notAnnotation"] = "io.github.chabiroael.twinbook.ManualProbe"
        ndk { abiFilters += listOf("x86_64", "arm64-v8a") }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        // The login-safe build the owner logs in to. Debuggable like debug (so capture-pull.sh
        // can read its private storage with run-as), installed next to it. No test task targets
        // it: instrumented tests use the debug build (testBuildType), and the uninstall tasks for
        // it are disabled below. See docs/SETUP.md "Protecting the owner's session".
        create("daily") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".daily"
            versionNameSuffix = "-daily"
            matchingFallbacks += listOf("debug")
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // GeckoView's native libraries are compressed in the APK and extracted at install.
        jniLibs { useLegacyPackaging = true }
    }

    testOptions { animationsDisabled = true }
    testBuildType = "debug"
}

// Uninstalling the daily build would destroy the owner's login. Refuse it outright.
tasks.matching { it.name == "uninstallDaily" || it.name == "uninstallAll" }.configureEach {
    doFirst {
        throw GradleException("$name is disabled: uninstalling the daily build destroys the owner's login (docs/SETUP.md).")
    }
}

kotlin {
    jvmToolchain(libs.versions.jdkToolchain.get().toInt())
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget(libs.versions.jvmTarget.get())
    }
}

dependencies {
    implementation(project(":engine"))
    implementation(project(":data"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    androidTestImplementation(project(":mockserver"))
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
