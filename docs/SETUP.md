# twinBook setup

How to build, test and run twinBook on the development machine (Ubuntu 22.04 on WSL2).
Written for an agent with no prior context: follow it top to bottom.

All commands run from the repository root. Every script in `tools/` sources
`tools/env.sh` itself, so the scripts work from any shell, even one with no profile.
For your own Gradle, `adb` or `npm` commands, source it first in that shell:

```bash
source tools/env.sh
```

## 1. Prerequisites

- Linux x86_64 with `bash`, `curl`, `unzip`, `tar`, `sha256sum`. No `sudo` is needed.
- Node 22 installed through nvm (`~/.nvm/versions/node/v22.*`). The major version is in
  `.nvmrc`. If it is missing: `nvm install 22` (in an interactive shell where nvm is loaded).
- `/dev/kvm` readable and writable by your user, for the emulator only. Check:
  `[ -r /dev/kvm ] && [ -w /dev/kvm ] && echo ok`. See Pitfalls if it fails.
- About 6 GB free disk for the toolchain, network access to dl.google.com,
  services.gradle.org, github.com (JDK download), registry.npmjs.org, Maven Central.

## 2. Install the toolchain (once)

```bash
tools/setup-toolchain.sh
```

Idempotent: rerun it any time; when everything is in place it ends with
`[setup] done: nothing changed`. First run downloads about 2 GB and takes about 5 minutes.
It installs:

| What | Where |
|---|---|
| Eclipse Temurin JDK 21 | `~/.jdks/jdk-21.0.12.1+1`, symlink `~/.jdks/temurin-21` (= `JAVA_HOME`) |
| Android SDK | `~/Android/Sdk` (= `ANDROID_HOME`) |
| SDK packages | `cmdline-tools/latest` 23.0, `platform-tools`, `platforms/android-37.2`, `build-tools/37.0.0`, `emulator`, `system-images/android-36/google_apis/x86_64` |
| AVD `twinbook_api36` | `~/.android/avd/twinbook_api36.avd` (Pixel 6, x86_64, 3 GB RAM, 4 cores) |
| Android CLI (auto-installed by cmdline-tools 23) | `~/.android/bin/android-cli` |
| Download cache | `~/.cache/twinbook/downloads` |

Gradle itself is fetched by the wrapper (`./gradlew`) into `~/.gradle` on first use.
`local.properties` is not needed and is git-ignored: `ANDROID_HOME` from `tools/env.sh`
is enough.

## 3. Run all checks (no device needed)

```bash
tools/check.sh
```

Runs, failing on the first error:

1. In `extension/`: `npm ci` if `node_modules` is missing or stale, then `npm run check`
   (TypeScript typecheck, Vitest tests, esbuild bundle into `extension/dist/`,
   `web-ext lint` on `dist/`).
2. `./gradlew check assembleDebug`: unit tests in `:data` and `:engine`, Android lint in
   all three modules, and the debug APK
   (`app/build/outputs/apk/debug/app-debug.apk`).

Extra arguments go to Gradle, for example `tools/check.sh --rerun-tasks`.

## 4. Emulator, app, instrumented tests, screenshots

```bash
tools/emulator-start.sh          # headless; returns when Android has booted (~45-65 s)
tools/app-run.sh                 # build, install and launch the debug app
tools/connected-test.sh          # instrumented tests (app/src/androidTest) on the emulator
tools/shot.sh <name>             # screenshot to build/shots/<name>.png
tools/emulator-stop.sh           # shut the emulator down
```

- The emulator always uses console port 5554, so its adb serial is `emulator-5554`.
  The device scripts target `$ANDROID_SERIAL` if set, otherwise `emulator-5554`.
- `emulator-start.sh` is a no-op if the emulator is already running. It cold boots every
  time (no snapshots), and switches off animations and screen-off for UI tests.
  Emulator output: `build/emulator/emulator.log`.
- Instrumented test reports: `app/build/reports/androidTests/connected/debug/index.html`,
  XML in `app/build/outputs/androidTest-results/connected/debug/`.
- Debug builds have the application ID `io.github.chabiroael.twinbook.debug`; main
  activity `io.github.chabiroael.twinbook.MainActivity`.

### Visible emulator window (manual use, WSLg)

```bash
tools/emulator-start.sh --window
```

Same as headless but with a window on the Windows desktop through WSLg. Qt has no Wayland
plugin in the emulator build, so it logs `Could not find the Qt platform plugin "wayland"`
and falls back to X11 (XWayland). That message is harmless. `DISPLAY` must be set, which
WSLg does in normal shells (`echo $DISPLAY` prints `:0`).

On this machine no system library was missing (checked with `ldd` on the emulator, its
Qt xcb plugin and qemu). The emulator's windowed mode loads these Ubuntu packages, all
present here: `libx11-6 libxcb1 libxext6 libxi6 libsm6 libice6 libxkbfile1 libdrm2
libpulse0 libnss3 libnspr4 libdbus-1-3`. On another machine, if the windowed emulator
exits with `error while loading shared libraries: <lib>`, the owner installs them with:

```bash
sudo apt install libx11-6 libxcb1 libxext6 libxi6 libsm6 libice6 libxkbfile1 libdrm2 libpulse0 libnss3 libnspr4 libdbus-1-3
```

## 5. Project layout

```
app/        Android app (Compose, Material 3, single activity). Packages the extension.
engine/     Android library, future GeckoView wrapper (placeholder in M0)
data/       Pure Kotlin JVM library, future models/normalizer/classifier (placeholder)
extension/  twin-bridge WebExtension: TypeScript, esbuild, Vitest, web-ext
gradle/     Wrapper and version catalog (libs.versions.toml holds every version)
tools/      Environment and device scripts
docs/       PLAN.md, SETUP.md, prompts/, reports/
```

How the extension gets into the APK: `:app:buildTwinBridge` runs `npm run build` in
`extension/` (and `npm ci` first if `node_modules` is missing or stale), producing
`extension/dist/`. `:app:twinBridgeAssets<Variant>` copies `dist/` into a generated assets
directory that AGP adds to the variant, so the APK contains
`assets/extensions/twin-bridge/manifest.json` and `background.js`. Both tasks declare
inputs and outputs and are skipped when nothing changed.

Useful Gradle commands (after `source tools/env.sh`):

```bash
./gradlew :data:test :engine:testDebugUnitTest   # JVM unit tests only
./gradlew lint                                    # Android lint, all modules
./gradlew :app:assembleDebug
./gradlew :app:buildTwinBridge                    # extension only
```

## 6. Pitfalls on this machine

- **KVM access.** The emulator needs read and write on `/dev/kvm` (owned `root:kvm`).
  Proper fix by the owner: `sudo usermod -aG kvm $USER`, then restart WSL from Windows
  (`wsl --shutdown`). A per-boot workaround is `sudo chmod 666 /dev/kvm`; it is lost when
  WSL restarts. `tools/emulator-start.sh` refuses to start without KVM rather than falling
  back to slow software emulation.
- **Old system adb.** `/usr/bin/adb` is an old Ubuntu package (1.0.41). Mixing it with the
  SDK's adb restarts the adb server back and forth and drops devices. Always use the SDK
  adb, which `tools/env.sh` puts first on `PATH` (`which adb` should print
  `~/Android/Sdk/platform-tools/adb`).
- **Old system Java.** `java` on the default `PATH` is OpenJDK 11, too old for the build.
  `tools/env.sh` sets `JAVA_HOME` and `PATH` to Temurin 21. Gradle fails with an unclear
  error if run without it.
- **Node and nvm.** Non-interactive shells do not load nvm, so `node`/`npm` are missing
  there. `tools/env.sh` adds the newest installed `~/.nvm/versions/node/v<.nvmrc major>.*`
  to `PATH` without loading nvm. The Gradle daemon gets its environment from the shell
  that starts the build; if a build fails with `twin-bridge: Node.js (node and npm) not
  found on PATH`, run `source tools/env.sh` and `./gradlew --stop`, then rebuild.
- **Memory budget (11 GB RAM + 4 GB swap).** Gradle daemon heap 2 GB, Kotlin daemon 1 GB,
  4 Gradle workers (`gradle.properties`); emulator guest RAM 3 GB (qemu process about
  4 GB resident). Other resident processes on this machine (a k3s server, the IDE server)
  take about 1.5 GB. Gradle and the emulator together fit, with some swap. Do not raise
  the AVD RAM or Gradle heap without measuring. `./gradlew --stop` frees about 3 GB when
  you only need the emulator.
- **sdkmanager is deprecated.** cmdline-tools 23 forwards `sdkmanager` to the new Android
  CLI (`android sdk install|list|remove`, package paths use `/` instead of `;`). It
  accepts the SDK license on install; `sdkmanager --licenses` is a no-op now. On first
  use the `android` launcher downloads `~/.android/bin/android-cli` (about 90 MB).
- **Gradle deprecation warning.** Every build prints "Deprecated Gradle features were
  used". It comes from AGP 9.4.1 itself (`Configuration.setVisible`), not from this
  project. Check with `./gradlew help --warning-mode all`.
- **Editing `app/build.gradle.kts`** reruns `:app:buildTwinBridge` once, because the task
  classes are declared in that script and Gradle treats a changed script as a changed
  task implementation. Harmless (about 2 s).
- **Configuration cache and build cache** are on. If a build behaves oddly after editing
  build logic, retry with `--no-configuration-cache` to rule it out.
