# Third-party software in twinBook

twinBook itself is licensed under the GNU General Public License v3.0 or later (`LICENSE`).
These components are shipped inside the app. Build-only tools (Gradle, the Android SDK,
Node packages used to build and test the extension) are not listed.

## uBlock Origin

| | |
|---|---|
| What | Content blocker, installed as a built-in WebExtension (`uBlock0@raymondhill.net`) |
| Version | 1.75.0 (released 2026-09-16) |
| License | GNU General Public License v3.0 (`LICENSE.txt` inside the extension) |
| Source | https://github.com/gorhill/uBlock, tag `1.75.0` |
| Shipped file | release asset `uBlock0_1.75.0.firefox.signed.xpi` (4,650,100 bytes), unpacked unchanged into the APK at `assets/extensions/ublock0/` |
| SHA-256 of the asset | `5b74415860456370644bd80f16125e865b0e6c356bb5dfcfb84069967eaa5287` (the digest GitHub publishes for it) |
| How it gets in | Downloaded at build time by `:engine:fetchUblockOrigin` and checked against the checksum in `gradle/libs.versions.toml`; never committed to this repository (docs/SHELL.md section 4) |

uBlock Origin downloads updates of its filter lists from the lists' own servers while it runs.
The filter lists keep their own licenses, listed in uBlock Origin's dashboard under "Filter
lists" (for example EasyList and EasyPrivacy: GPLv3 or CC BY-SA 3.0; Peter Lowe's list; the
Online Malicious URL Blocklist: CC0). The app shows the version, the license and the source
link under Settings, "Versions".

## GeckoView

| | |
|---|---|
| What | Mozilla's browser engine for Android |
| Version | 157.0.20260924084938 |
| License | Mozilla Public License 2.0 |
| Source | https://hg.mozilla.org/mozilla-central (release build from https://maven.mozilla.org/maven2/, artifact `org.mozilla.geckoview:geckoview`) |

## AndroidX, Jetpack Compose, Kotlin coroutines

| Component | License | Source |
|---|---|---|
| AndroidX (core, activity), Jetpack Compose (BOM 2026.09.00) | Apache License 2.0 | https://android.googlesource.com/platform/frameworks/support |
| kotlinx.coroutines 1.11.0, Kotlin standard library 2.4.20 | Apache License 2.0 | https://github.com/Kotlin/kotlinx.coroutines, https://github.com/JetBrains/kotlin |

Exact versions are in `gradle/libs.versions.toml`.
