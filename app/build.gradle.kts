import java.io.File
import javax.inject.Inject
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
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
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

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

// --- twin-bridge WebExtension packaging -------------------------------------------------
//
// buildTwinBridge runs `npm run build` in extension/ (bundle + manifest into extension/dist).
// twinBridgeAssets<Variant> copies extension/dist into a generated assets directory under
// extensions/twin-bridge/, which AGP adds to the variant's assets. Both tasks declare inputs
// and outputs, so they are skipped when nothing changed.

/** Runs the extension's npm build. Needs Node on PATH: `source tools/env.sh`. */
abstract class BuildTwinBridgeTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Internal
    abstract val extensionDir: DirectoryProperty

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
    sources.from(
        twinBridgeDir.dir("src"),
        twinBridgeDir.file("manifest.json"),
        twinBridgeDir.file("package.json"),
        twinBridgeDir.file("package-lock.json"),
        twinBridgeDir.file("tsconfig.json"),
        twinBridgeDir.file("build.mjs"),
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
