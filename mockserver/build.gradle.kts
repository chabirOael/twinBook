// Pure Kotlin JVM library: the hermetic mock server that imitates the shape of the site's
// traffic. It runs inside instrumented test processes on the device, bound to loopback,
// and in JVM unit tests on the host. For device scripts that restart the app or the device it
// also runs on the host (`application` plugin, MockHost.kt, tools/mock-host.sh), reached from
// the device through `adb reverse`.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.android.lint)
    application
}

application {
    mainClass = "io.github.chabiroael.twinbook.mockserver.MockHostKt"
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
