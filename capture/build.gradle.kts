// Pure Kotlin JVM library: the capture store (session directories in app-private storage),
// the layer 2 taint scrubber and the finalize pass. No Android dependency, so all of it is
// tested on the JVM in tools/check.sh. The app wires it to the bridge (:engine).
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

dependencies {
    testImplementation(libs.junit)
}

// The taint test vectors are shared with the extension (extension/test/vectors/taint.json).
val taintVectors = rootProject.layout.projectDirectory.file("extension/test/vectors/taint.json")
val redactionRules = rootProject.layout.projectDirectory.file("extension/data/redaction-rules.json")
tasks.test {
    inputs.file(taintVectors).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(redactionRules).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("twinbook.taintVectors", taintVectors.asFile.absolutePath)
    systemProperty("twinbook.redactionRules", redactionRules.asFile.absolutePath)
}
