// Android library: the GeckoView wrapper (runtime, sessions, extension bridge). It also
// packages the twin-bridge WebExtension into its assets, so the app and this module's
// instrumented tests both get it (the app through the library merge, exactly once).
import java.io.File
import javax.inject.Inject
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.chabiroael.twinbook.engine"
    compileSdk {
        version = release(libs.versions.compileSdk.get().toInt()) {
            minorApiLevel = libs.versions.compileSdkMinor.get().toInt()
        }
    }
    buildToolsVersion = libs.versions.buildTools.get()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("x86_64", "arm64-v8a") }
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    testOptions { animationsDisabled = true }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
    }
}

kotlin {
    jvmToolchain(libs.versions.jdkToolchain.get().toInt())
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget(libs.versions.jvmTarget.get())
    }
}

dependencies {
    api(libs.geckoview)
    api(libs.kotlinx.coroutines.android)

    androidTestImplementation(project(":mockserver"))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

// --- twin-bridge WebExtension packaging -------------------------------------------------
//
// buildTwinBridge runs `npm run build` in extension/ (bundle + manifest with a per-build
// version into extension/dist). twinBridgeAssets<Variant> copies extension/dist into a
// generated assets directory under extensions/twin-bridge/, which AGP adds to the variant's
// assets. Both tasks declare inputs and outputs, so they are skipped when nothing changed.
// -Ptwinbook.extensionMarker=<text> compiles a marker into the bundle (changes the version);
// tools/extension-update-test.sh uses it.

/** Runs the extension's npm build. Needs Node on PATH: `source tools/env.sh`. */
abstract class BuildTwinBridgeTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Internal
    abstract val extensionDir: DirectoryProperty

    @get:Input
    abstract val marker: Property<String>

    @get:OutputDirectory
    abstract val distDir: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun build() {
        val npm = findOnPath("npm")
        if (npm == null || findOnPath("node") == null) {
            throw GradleException(
                "twin-bridge: Node.js (node and npm) not found on PATH. Run `source tools/env.sh` " +
                    "in this shell before Gradle; it puts the nvm Node from .nvmrc first on PATH. " +
                    "If a Gradle daemon was started without it, run `./gradlew --stop` first. " +
                    "See docs/SETUP.md.",
            )
        }
        val dir = extensionDir.get().asFile
        val lockFile = File(dir, "package-lock.json")
        val installed = File(dir, "node_modules/.package-lock.json")
        if (!installed.exists() || installed.lastModified() < lockFile.lastModified()) {
            execOperations.exec {
                workingDir = dir
                commandLine(npm.path, "ci", "--no-audit", "--no-fund")
            }
        }
        execOperations.exec {
            workingDir = dir
            environment("TWIN_BRIDGE_MARKER", marker.get())
            commandLine(npm.path, "run", "build")
        }
    }

    private fun findOnPath(name: String): File? =
        System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .filter { it.isNotEmpty() }
            .map { File(it, name) }
            .firstOrNull { it.canExecute() }
}

/** Copies the built extension into an assets directory, at extensions/twin-bridge/. */
abstract class TwinBridgeAssetsTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val distDir: DirectoryProperty

    @get:OutputDirectory
    abstract val assetsDir: DirectoryProperty

    @get:Inject
    abstract val fileSystemOperations: FileSystemOperations

    @TaskAction
    fun copy() {
        fileSystemOperations.sync {
            from(distDir) { into("extensions/twin-bridge") }
            into(assetsDir)
        }
    }
}

val twinBridgeDir: Directory = rootProject.layout.projectDirectory.dir("extension")

val buildTwinBridge = tasks.register<BuildTwinBridgeTask>("buildTwinBridge") {
    group = "build"
    description = "Builds the twin-bridge WebExtension into extension/dist."
    extensionDir.set(twinBridgeDir)
    marker.set(providers.gradleProperty("twinbook.extensionMarker").orElse("default"))
    sources.from(
        twinBridgeDir.dir("src"),
        twinBridgeDir.file("manifest.json"),
        twinBridgeDir.file("package.json"),
        twinBridgeDir.file("package-lock.json"),
        twinBridgeDir.file("tsconfig.json"),
        twinBridgeDir.file("build.mjs"),
        twinBridgeDir.file("build-lib.mjs"),
    )
    distDir.set(twinBridgeDir.dir("dist"))
}

androidComponents {
    onVariants { variant ->
        val copyTask = tasks.register<TwinBridgeAssetsTask>(
            "twinBridgeAssets${variant.name.replaceFirstChar { it.uppercase() }}",
        ) {
            distDir.set(buildTwinBridge.flatMap { it.distDir })
        }
        variant.sources.assets?.addGeneratedSourceDirectory(copyTask, TwinBridgeAssetsTask::assetsDir)
    }
}
