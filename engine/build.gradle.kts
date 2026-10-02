// Android library: the GeckoView wrapper (runtime, sessions, extension bridge). It also
// packages the built-in WebExtensions into its assets, so the app and this module's
// instrumented tests both get them (the app through the library merge, exactly once):
// twin-bridge, built from extension/, and uBlock Origin, downloaded from its pinned release.
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipFile
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
        // Probes that only tools/ scripts run (persistence, update, memory).
        testInstrumentationRunnerArguments["notAnnotation"] = "io.github.chabiroael.twinbook.engine.ManualProbe"
        ndk { abiFilters += listOf("x86_64", "arm64-v8a") }
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    // AGP's default ignore pattern drops asset directories whose name starts with "_" (<dir>_*).
    // uBlock Origin keeps its translations in _locales/, without which Gecko rejects it as invalid.
    androidResources { ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:!CVS:!thumbs.db:!picasa.ini:!*~" }

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
    api(project(":capture"))

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
        twinBridgeDir.dir("data"),
        twinBridgeDir.file("manifest.json"),
        twinBridgeDir.file("package.json"),
        twinBridgeDir.file("package-lock.json"),
        twinBridgeDir.file("tsconfig.json"),
        twinBridgeDir.file("build.mjs"),
        twinBridgeDir.file("build-lib.mjs"),
    )
    distDir.set(twinBridgeDir.dir("dist"))
}

// --- uBlock Origin ------------------------------------------------------------------------
//
// fetchUblockOrigin downloads the signed Firefox release asset of the version pinned in
// gradle/libs.versions.toml into ~/.cache/twinbook/downloads (the toolchain's download cache),
// verifies its SHA-256, and unpacks it. A file already in the cache with the right checksum is
// used as is, so only the first build downloads anything; a cached file with a wrong checksum is
// deleted and fetched again, and a fresh download with a wrong checksum fails the build.
// ublockOriginAssets<Variant> copies the unpacked files into the variant's assets under
// extensions/ublock0/. The files are never committed (license GPLv3, see THIRD-PARTY.md).

abstract class FetchUblockOriginTask : DefaultTask() {
    @get:Input
    abstract val version: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    @get:Internal
    abstract val cacheDir: DirectoryProperty

    @get:OutputDirectory
    abstract val unpackedDir: DirectoryProperty

    @TaskAction
    fun fetch() {
        val name = "uBlock0_${version.get()}.firefox.signed.xpi"
        val url = "https://github.com/gorhill/uBlock/releases/download/${version.get()}/$name"
        val expected = sha256.get().lowercase()
        val cache = cacheDir.get().asFile.apply { mkdirs() }
        val xpi = File(cache, name)
        if (xpi.exists() && sha256Of(xpi) != expected) {
            logger.warn("uBlock Origin: cached $name has a wrong checksum; fetching it again")
            xpi.delete()
        }
        if (!xpi.exists()) {
            logger.lifecycle("uBlock Origin: downloading $url")
            val part = File(cache, "$name.${ProcessHandle.current().pid()}.part")
            URI(url).toURL().openStream().use { input -> part.outputStream().use { input.copyTo(it) } }
            val got = sha256Of(part)
            if (got != expected) {
                part.delete()
                throw GradleException("uBlock Origin: $name has SHA-256 $got, expected $expected. Nothing was installed.")
            }
            if (!part.renameTo(xpi) && !xpi.exists()) throw GradleException("uBlock Origin: could not move ${part.name} into the cache")
        }
        val out = unpackedDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        ZipFile(xpi).use { zip ->
            for (entry in zip.entries()) {
                val target = File(out, entry.name)
                if (!target.canonicalPath.startsWith(out.canonicalPath + File.separator)) throw GradleException("uBlock Origin: bad zip entry ${entry.name}")
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile.mkdirs()
                    zip.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
                }
            }
        }
        logger.lifecycle("uBlock Origin ${version.get()}: ${xpi.length()} bytes, SHA-256 $expected verified, unpacked")
    }

    private fun sha256Of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Copies the unpacked uBlock Origin into an assets directory, at extensions/ublock0/. */
abstract class UblockOriginAssetsTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val unpackedDir: DirectoryProperty

    @get:OutputDirectory
    abstract val assetsDir: DirectoryProperty

    @get:Inject
    abstract val fileSystemOperations: FileSystemOperations

    @TaskAction
    fun copy() {
        fileSystemOperations.sync {
            from(unpackedDir) { into("extensions/ublock0") }
            into(assetsDir)
        }
    }
}

val fetchUblockOrigin = tasks.register<FetchUblockOriginTask>("fetchUblockOrigin") {
    group = "build"
    description = "Downloads the pinned uBlock Origin release (checksum verified) and unpacks it."
    version.set(libs.versions.ublockOrigin)
    sha256.set(libs.versions.ublockOriginSha256)
    cacheDir.set(File(providers.systemProperty("user.home").get(), ".cache/twinbook/downloads"))
    unpackedDir.set(layout.buildDirectory.dir("ublock-origin/unpacked"))
}

androidComponents {
    onVariants { variant ->
        val suffix = variant.name.replaceFirstChar { it.uppercase() }
        val copyTask = tasks.register<TwinBridgeAssetsTask>("twinBridgeAssets$suffix") {
            distDir.set(buildTwinBridge.flatMap { it.distDir })
        }
        variant.sources.assets?.addGeneratedSourceDirectory(copyTask, TwinBridgeAssetsTask::assetsDir)
        val ublockTask = tasks.register<UblockOriginAssetsTask>("ublockOriginAssets$suffix") {
            unpackedDir.set(fetchUblockOrigin.flatMap { it.unpackedDir })
        }
        variant.sources.assets?.addGeneratedSourceDirectory(ublockTask, UblockOriginAssetsTask::assetsDir)
    }
}
