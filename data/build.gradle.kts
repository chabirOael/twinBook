// Pure Kotlin JVM library: the web shell's link rules (M3a); models, normalizer and ad
// classifier in later milestones.
// No Android dependency. Android lint still runs on it as part of `check`.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.android.lint)
}

java {
    sourceCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
    targetCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
}

kotlin {
    jvmToolchain(libs.versions.jdkToolchain.get().toInt())
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget(libs.versions.jvmTarget.get())
    }
}

// The web shell's link rules ship as a resource of this module (LinkRules.forSite()).
sourceSets {
    main {
        resources {
            srcDir(rootProject.layout.projectDirectory.dir("rules"))
            include("links-v1.json")
        }
    }
}

dependencies {
    testImplementation(libs.junit)
}
