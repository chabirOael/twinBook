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
  services.gradle.org, github.com (JDK download), registry.npmjs.org, Maven Central, and
  maven.mozilla.org (GeckoView only; the repository is content-filtered to
  `org.mozilla.geckoview`). The first build downloads the 242 MB GeckoView AAR.

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

1. In `extension/`: `npm ci` if `node_modules` is missing or stale, then `npm run check`:
   TypeScript typecheck, Vitest tests (stream filters and observe mode, bridge client,
   capture transport, layer 1 redaction, the taint vectors, the probe, the observe-only
   guarantees of the real-site profile against a fake WebExtension API, the HAR importer,
   version stamp), esbuild bundle into `extension/dist/`, and the lint policy
   (`node lint.mjs`): `web-ext lint` errors fail; warnings fail unless listed with a reason
   in `extension/lint-allowlist.json` (today only `geckoViewAddons`).
2. `./gradlew check assembleDebug`: JVM tests in `:data`, `:mockserver` and `:capture` (store,
   finalize pass, the same taint vectors as the extension), Android lint in all five modules,
   and the debug APK (`app/build/outputs/apk/debug/app-debug.apk`,
   about 198 MB because GeckoView's native libraries for x86_64 and arm64-v8a are inside).

Extra arguments go to Gradle, for example `tools/check.sh --rerun-tasks`.

## 4. Emulator, app, instrumented tests, screenshots

```bash
tools/emulator-start.sh          # headless; returns when Android has booted (~45-65 s)
tools/app-run.sh                 # build, install and launch the debug app
tools/connected-test.sh          # all instrumented tests (:app and :engine) on the emulator
tools/shot.sh <name>             # screenshot to build/shots/<name>.png
tools/emulator-stop.sh           # shut the emulator down
```

- The emulator always uses console port 5554, so its adb serial is `emulator-5554`.
  The device scripts target `$ANDROID_SERIAL` if set, otherwise `emulator-5554`.
- `emulator-start.sh` is a no-op if the emulator is already running. It cold boots every
  time (no snapshots), and switches off animations and screen-off for UI tests.
  Emulator output: `build/emulator/emulator.log`.
- Instrumented test reports: `<module>/build/reports/androidTests/connected/debug/index.html`,
  XML in `<module>/build/outputs/androidTest-results/connected/debug/`, for `app` and
  `engine`. The engine tests log evidence lines with tag `twinbook-evidence`; Gradle keeps the
  logcat per test next to the XML.
- `connectedDebugAndroidTest` installs fresh APKs and uninstalls them afterwards, so every run
  starts with empty app data. Checks that need app data kept use the scripts below.

### Engine probes and measurements (keep app data)

```bash
tools/engine-instrument.sh <Class[#method]> [-e key value ...]   # one :engine test via am instrument
tools/persistence-test.sh        # G15: cookies and storage.local across process kill
tools/extension-update-test.sh   # G16: changed extension in a reinstalled APK, data kept
tools/measure-memory.sh          # PSS over all app processes at four stages
tools/measure-startup.sh [runs]  # app cold start: process start -> extension ready / lab page
```

`engine-instrument.sh` builds and installs the :engine test APK with `adb install -r` (data
kept) and runs one class with `am instrument` in a fresh process. It prints the result and the
`twinbook-evidence` lines. `PersistenceProbe` and `MeasurementProbe` carry the `@ManualProbe`
annotation and are excluded from Gradle connected runs; only these scripts run them.
`measure-startup.sh` needs the app installed (`tools/app-run.sh`).
- Debug builds have the application ID `io.github.chabiroael.twinbook.debug`; main
  activity `io.github.chabiroael.twinbook.MainActivity`.

### Build types: debug and daily

| Build | Application ID | Used by |
|---|---|---|
| `debug` | `io.github.chabiroael.twinbook.debug` | every test and agent script (`connectedDebugAndroidTest` installs and uninstalls it) |
| `daily` | `io.github.chabiroael.twinbook.daily` | the owner's logged-in session only; installed next to debug, label "twinBook daily" |

`daily` is a debug build with its own ID suffix (debuggable, debug-signed, so `run-as` can read
its captures). No test task targets it (`testBuildType = "debug"`), and the Gradle tasks
`uninstallDaily` and `uninstallAll` fail on purpose. Read section 7 before touching it.

### Capture tooling (M2a)

```bash
tools/daily-install.sh [--no-build]     # build and install or update the daily app, data kept
tools/daily-launch.sh                   # launch the daily app
tools/capture-pull.sh [--debug] list    # sessions in the daily (or debug) app, finalized or not
tools/capture-pull.sh [--debug] pull <id>   # copy a finalized session to captures/<id>, verify checksums
tools/capture-pull.sh [--debug] pull-all
node tools/capture-summary.mjs [--short] captures/<id>   # hosts, types, leads, redaction report
tools/har-import.sh <file.har> [--id <id>] [--out <dir>] # HAR from desktop Firefox -> captures/<id>
tools/app-instrument.sh <Class[#method]> [-e k v]        # one :app test via am instrument, debug app, data kept
tools/capture-kill-test.sh              # C7: a killed capture cannot be pulled and is deleted at start
tools/daily-survival-test.sh            # C13: daily data survives tests, M1 scripts and an update
```

`captures/` is git-ignored. The format is in docs/CAPTURE.md, the owner's steps in
docs/CAPTURE-CHECKLIST.md. `capture-pull.sh` and `app-instrument.sh` never delete anything on
the device. `CaptureProbe` (app) is a `@ManualProbe`: only the scripts run it.

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
app/        Android app (Compose, Material 3, single activity): start screen, engine lab,
            capture browser. Build types debug and daily.
engine/     Android library: GeckoView runtime, sessions, bridge; packages the extension.
            Instrumented gate tests in engine/src/androidTest. See docs/ENGINE.md.
data/       Pure Kotlin JVM library, future models/normalizer/classifier (placeholder)
mockserver/ Pure Kotlin JVM library: loopback mock of the site's traffic, used by tests
capture/    Pure Kotlin JVM library: capture store, layer 2 taint scrubber, finalize pass
extension/  twin-bridge WebExtension: TypeScript, esbuild, Vitest, web-ext
gradle/     Wrapper and version catalog (libs.versions.toml holds every version)
tools/      Environment and device scripts
docs/       PLAN.md, SETUP.md, prompts/, reports/
```

How the extension gets into the APK: `:engine:buildTwinBridge` runs `npm run build` in
`extension/` (and `npm ci` first if `node_modules` is missing or stale), producing
`extension/dist/` with a per-build version (`x.y.z.N`, see docs/ENGINE.md section 7).
`:engine:twinBridgeAssets<Variant>` copies `dist/` into a generated assets directory of the
`:engine` library, so both the :engine test APK and the app (through the library merge)
contain `assets/extensions/twin-bridge/manifest.json`, `background.js` and `anchor.js`, once.
Both tasks declare inputs and outputs and are skipped when nothing changed.
`-Ptwinbook.extensionMarker=<text>` compiles a marker into the bundle, which changes the
version.

Useful Gradle commands (after `source tools/env.sh`):

```bash
./gradlew :data:test :mockserver:test            # JVM unit tests only
./gradlew lint                                    # Android lint, all modules
./gradlew :app:assembleDebug
./gradlew :engine:assembleDebugAndroidTest        # the :engine instrumented-test APK
./gradlew :engine:buildTwinBridge                 # extension only
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
- **Editing `engine/build.gradle.kts`** reruns `:engine:buildTwinBridge` once, because the
  task classes are declared in that script and Gradle treats a changed script as a changed
  task implementation. Harmless (about 2 s).
- **Emulator deadlocks during boot** with `detected a hanging thread 'QEMU2 CPU0 thread'` in
  `build/emulator/emulator.log` and `Netsim daemon failed to start: Permission denied` in
  `/tmp/android-$USER/netsimd/netsim_stderr.log`: `XDG_RUNTIME_DIR` names a directory that
  does not exist (IDE and agent shells on this machine set `/run/user/1000/`).
  `emulator-start.sh` now unsets it in that case. If you start the emulator by hand, use
  `env -u XDG_RUNTIME_DIR emulator ...`. After such a crash, delete the stale
  `~/.android/avd/twinbook_api36.avd/*.lock` files if no qemu process is running.
- **Memory with GeckoView.** Peak during `tools/connected-test.sh` with the emulator running:
  10.2 GB used of 11.7 GB plus 2.5 GB swap. It passes, but close other heavy programs, and
  run `./gradlew --stop` if the emulator becomes sluggish.
- **APK installs are large** (about 190 to 200 MB each for the app and the :engine test APK);
  `adb install` takes about 10 s on the emulator.
- **GeckoView start-up quirks** (bootstrap session, extension reinstall recovery, initial
  about:blank) are explained in docs/ENGINE.md section 9. Do not remove them as
  simplifications.
- **Configuration cache and build cache** are on. If a build behaves oddly after editing
  build logic, retry with `--no-configuration-cache` to rule it out.

## 7. Protecting the owner's session

From M2a on, the owner is logged in to the real site in the `daily` app on this emulator.
That login lives only in the daily app's private data inside the AVD's user-data image. These
actions destroy it, and **agents must never do any of them**:

- Uninstalling the daily app: `adb uninstall io.github.chabiroael.twinbook.daily`,
  `adb shell pm uninstall ...daily`, `./gradlew uninstallDaily` or `uninstallAll` (both disabled
  in `app/build.gradle.kts`), or uninstalling it from Android settings or the launcher.
- Clearing its data: `adb shell pm clear ...daily`, "Clear storage" or "Clear cache" in
  Android settings, or deleting anything under its private storage with `run-as`
  (`adb shell run-as ...daily rm ...`).
- Installing a daily APK signed with a different key. Android then refuses the update, and the
  only way forward would be an uninstall. The key is `~/.android/debug.keystore`: never delete
  or replace it. `tools/daily-install.sh` stops if an update is refused; it never uninstalls.
- Changing the daily build's application ID (`applicationIdSuffix = ".daily"`): that would be a
  different app, and the logged-in one would be left behind.
- Wiping or recreating the AVD: `emulator -wipe-data`, Android's factory reset, "Wipe Data" in
  Device Manager, `avdmanager delete avd -n twinbook_api36`, `avdmanager create avd --force`
  with the same name, deleting `~/.android/avd/twinbook_api36.avd/` or its `userdata-qemu.img*`
  files, or replacing its system image.
- Starting the emulator with any wipe option (`-wipe-data`, `-wipe-all`). `tools/emulator-start.sh`
  never passes one. Cold boots (`-no-snapshot`) keep the data.

Safe: `tools/emulator-stop.sh`, `tools/emulator-start.sh [--window]`, `tools/connected-test.sh`,
the M1 device scripts, `tools/app-instrument.sh`, `tools/engine-instrument.sh` (they touch only
the debug app and the engine test app), `tools/daily-install.sh` (update in place) and
`tools/capture-pull.sh` (reads only). `tools/daily-survival-test.sh` checks this (C13 in
docs/reports/M2a.md).
