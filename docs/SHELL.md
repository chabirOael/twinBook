# twinBook web shell

The web shell is what the `daily` build opens: the site's mobile version in a GeckoView that
behaves like an app, with uBlock Origin built in. Written for an agent with no prior context.
Build and device instructions are in `docs/SETUP.md`, the engine in `docs/ENGINE.md`, the plan
in `docs/PLAN.md`. The owner's trial steps are in `docs/SHELL-CHECKLIST.md`.

## 1. What it is and what it is not

- One visible session (`EngineSession`, profile `MOBILE`) on `https://m.facebook.com/`. Logged
  in, that site is a "web lite" client fed through a WebSocket (docs/findings/payloads.md
  section 11), so its ads can only be hidden after they are drawn: uBlock Origin's mobile
  cosmetic filters do that, not twin-bridge.
- No address bar, no native tabs, no restyling, no CSS of our own: nobody but the owner can see
  the logged-in site, so the shell does not touch its layout. The site keeps its own navigation.
- What the shell adds: a splash until the engine is ready, back in the page's history,
  link hygiene, saved state across process death and restart, crash recovery, the system's
  light or dark setting, no reload on rotation, ad hiding and tracker blocking (uBlock
  Origin), an optional strict mode (twin-bridge cancels the site's logging beacons), a small menu
  and a minimal settings screen.
- Not yet (M3b): file upload, downloads, camera and microphone, full-screen video, media
  controls. Every permission request is still denied (docs/ENGINE.md section 3).

## 2. Structure

```
app/src/main/kotlin/io/github/chabiroael/twinbook/
  MainActivity.kt    single activity, a small screen stack; daily opens SHELL, debug opens START
  Shell.kt           ShellConfig (site id + link rules), Shell (process-wide state), ExternalOpener,
                     AndroidOpener, SessionStateStore
  ShellScreen.kt     ShellScreen, SettingsScreen, DashboardScreen, GeckoViewFor
  ShellSettings.kt   the owner's settings (SharedPreferences "shell-settings")
  DevOverrides.kt    launch options for device scripts, debug build only
  AppEngine.kt       the one Engine: twin-bridge, uBlock Origin, start-up mode; debug-only Gecko prefs
  Prompts.kt         page dialogs (shared with the capture browser)
data/src/main/kotlin/.../data/links/LinkRules.kt   the link rules (pure Kotlin, JVM tests)
rules/links-v1.json                                 own hosts, redirect page, tracking parameters
engine/src/main/kotlin/.../engine/Engine.kt        content blocker, start-up probe, start-up modes
engine/src/main/kotlin/.../engine/EngineSession.kt navigation policy, session state, recovery
extension/src/strict.ts, data/strict-endpoints.json strict mode
extension/src/startupProbe.ts                       reports how the blocker's probe requests ended
extension/src/netlog.ts                             diag.netlog.* request counter (measurements)
```

Screens and the menu:

| Screen | Reached from | Back |
|---|---|---|
| Shell | app start (daily), "Web shell (real site)" on the developer start screen (debug) | the page's history; with none left: the system (daily, predictive back to home) or the start screen (debug) |
| Settings | menu, "Settings" | closes |
| uBlock Origin dashboard | Settings, "uBlock Origin dashboard" | the dashboard's own tab history, then closes; its "← Back" button closes at once |
| Developer start screen | menu, "Developer screens" (debug and daily builds) | back to the shell |

The menu is a 36 dp round button in the bottom-right corner over the page: Reload, Home,
Settings, Developer screens. Reload is a menu action, not pull to refresh: the logged-in site is
a single-page client whose scrolling container we cannot see, so "scrolled to the top" cannot be
known from outside; a pull gesture would either fight the page's own scrolling or reload by
accident and lose a comment being written.

Window: edge to edge (`enableEdgeToEdge`), the GeckoView padded by `WindowInsets.safeDrawing`
(status bar, navigation bar, display cutout, on-screen keyboard). The activity declares
`configChanges` for orientation, size, uiMode, keyboard, density, font scale and locale, so
rotation, dark mode and keyboards never recreate it; sessions live in process-wide objects
anyway. `onConfigurationChanged` passes the configuration to Gecko
(`GeckoRuntime.configurationChanged`), and the runtime is created with
`preferredColorScheme(COLOR_SCHEME_SYSTEM)`, so `prefers-color-scheme` follows the system,
live. Predictive back: `enableOnBackInvokedCallback="true"`; the shell's back handler is
enabled only while the page can go back, so the system's back-to-home animation runs otherwise.

The splash is shown until the engine is ready and the first page has painted (or finished
loading). The loading bar (2 dp, at the top) appears only for loads longer than 400 ms. The
shell starts its first load only after its GeckoView has a size: a page loaded into a view of
width 0 lays out at width 0 first.

## 3. Site configuration and link rules

`ShellConfig(siteId, rules)`. `ShellConfig.realSite()` reads `rules/links-v1.json` (packaged as
a Java resource of `:data`); `ShellConfig.mock(origin)` uses the same tracking parameters with the
mock's hosts (`127.0.0.1` and, in debug builds, `mock.twinbook.test`) and its redirect page
`/l.php`. Instrumented tests inject it with `Shell.configureForTest`; device scripts with the
launch options in section 8.

Own hosts (top-level navigation stays in the shell), decided from the request metadata of the
owner's four recordings (counts by host and type in docs/reports/M3a.md):

| Host | Why |
|---|---|
| `facebook.com` and every subdomain | every main_frame of the recordings (`m.`, `www.`); sockets and beacons on other subdomains |
| `fbcdn.net` and subdomains | the site's CDN: every image, video, script, font |
| `fbsbx.com` and subdomains | the site's sandbox domain, a sub_frame of its pages |

Not in the recordings and therefore not own hosts: `fb.me`, `fb.com`, `messenger.com`,
`instagram.com`, `meta.com`. They open in the browser.

`LinkRules.decide(url)` for every top-level navigation (and new-window request):

1. `tel:`, `mailto:`, `geo:` → handed to the system (only after a user gesture).
2. Other non-http(s) schemes → the engine's own rule: `about:`, `data:`, `blob:` load, anything
   else (`fb://`, `intent://`) is ignored and never leaves the app.
3. The redirect page `l.facebook.com/l.php` or `lm.facebook.com/l.php`: its `u` parameter is the
   target. It is unwrapped without being loaded, repeatedly (nested redirects, at most 5), and
   decoded once more if still percent-encoded (`https%3A...`). No usable http(s) target, or a
   target with another scheme (`javascript:`, `intent:`): ignored. A `mailto:` target goes to
   the system.
4. A target on an own host stays in the shell (a redirect target replaces the navigation:
   `LoadInstead`); any other http(s) URL opens in the default browser (`AndroidOpener`: an
   ACTION_VIEW intent with the browser selector, so an app that claims the link is not chosen).
5. Tracking parameters (`trackingParams` in the rules file: `fbclid`, `utm_*`, `gclid`, the
   site's `__cft__*`, `__tn__`, `__xts__*` and others, one reason each) are removed from every
   target that leaves the shell or comes out of the redirect page. The rest of the URL is kept
   byte for byte. Navigation within the site is never rewritten.

An outbound navigation without a user gesture (a script, not a tap) is not opened; a server
redirect of a tapped link is. Popups without a gesture are refused (`Prompts(allowPopupsWithoutGesture
= false)`); a `target=_blank` link or `window.open` after a tap follows the same rules.

## 4. uBlock Origin

Fetched: `:engine:fetchUblockOrigin` downloads
`https://github.com/gorhill/uBlock/releases/download/<version>/uBlock0_<version>.firefox.signed.xpi`
into `~/.cache/twinbook/downloads/` (the toolchain's cache), checks the SHA-256 pinned in
`gradle/libs.versions.toml` next to the version, and unpacks it into
`engine/build/ublock-origin/unpacked`. A cached file with the right checksum is used as is, so
only the first build of a machine downloads anything; a wrong checksum fails the build.
`ublockOriginAssets<Variant>` copies it into the variant's assets at `extensions/ublock0/`.
Nothing of it is committed. To update: change both values in `libs.versions.toml` (version and
the digest GitHub publishes for the asset) and THIRD-PARTY.md.

Packaging pitfall: AGP drops asset directories whose name starts with `_` by default
(`<dir>_*`), and Gecko then rejects uBlock Origin as invalid (its translations are in
`_locales/`). `androidResources.ignoreAssetsPattern` is set in `:app` and `:engine` without that
entry.

Installed: as a built-in extension (`resource://android/assets/extensions/ublock0/`, ID
`uBlock0@raymondhill.net`) by `Engine` when `EngineConfig.blocker` is set, in the same start-up
mode as twin-bridge (section 5). Built-in extensions need no signature check; the release's
`META-INF` is shipped unchanged anyway.

Mobile environment: uBlock Origin decides it from the user agent of its background page
(`/\bMobile\b/` in `vapi-common.js`); GeckoView's default user agent contains `Mobile`. Two
checks show it in this app: its support page's troubleshooting information says
`Firefox Mobile: 157`, and on first run it selected the list "AdGuard – Mobile Ads" (tagged
`ua: mobile` in its `assets.json`) by itself (`ShellScreenTest#ublockDashboardOpensAndReportsAMobileEnvironment`,
screenshot `docs/reports/assets/M3a-ublock-support.png`). Its rules for the mobile site are in
`!#if env_mobile` blocks of its default "uBlock filters" list.

Default lists active after the first run: uBlock filters (with badware, privacy, unbreak and
quick fixes), EasyList, AdGuard – Mobile Ads, EasyPrivacy, Online Malicious URL Blocklist, Peter
Lowe's list. uBlock Origin updates them itself from their own servers (auto-update on).

Switched: "Ad hiding" in Settings calls `Engine.setBlockerEnabled`, which uses GeckoView's
`WebExtensionController.disable` and `enable` (EnableSource.APP), not a reinstall; switching on
waits until the blocker filters again (section 5), then the page reloads. The setting is stored
in SharedPreferences and applied at every start before the first page (a disabled add-on stays
disabled across restarts; the engine follows the setting either way). Off means off for the
whole app, which shows only the site, so third-party trackers the site loads are no longer
blocked either.

Dashboard: Settings, "uBlock Origin dashboard" opens its options page
(`WebExtension.MetaData.optionsPageUrl`, `moz-extension://<uuid>/dashboard.html`) in a session of
its own that may load `moz-extension:` pages; http(s) links from it open in the browser. Its
popup and logger are not reachable from the UI (debug builds can open any of its pages with the
launch option `twinbook.dashboardPage`, section 8).

Testing pitfall: EasyList turns generic cosmetic filters off on local addresses
(`@@://127.0.0.1$generichide`, `localhost`, `10.0.0.`), so element hiding cannot be tested on
`127.0.0.1`. Debug builds give Gecko a preference file (`configFilePath`) that resolves
`mock.twinbook.test` to the loopback interface (`network.dns.localDomains`); the test that checks
hiding loads the mock under that name. The daily build never reads that file.

## 5. Start-up contract with two extensions

Site pages load only after `Engine.awaitReady()`, which now waits for both extensions:

- **twin-bridge is ready** when it is installed and its background script has said hello over
  the bridge (unchanged from M1).
- **uBlock Origin is ready** when it has cancelled a probe request. It has no bridge, so the
  engine asks the page side: in the headless bootstrap session it loads a `data:` page whose only
  subresource is `http://127.0.0.1:65535/__utm.gif?twinbook_startup_probe=<id>` (EasyPrivacy blocks
  `/__utm.gif` everywhere; nothing listens on that port, so an unfiltered probe fails locally and
  never leaves the device). twin-bridge watches those requests (`startupProbe.ts`, non-blocking
  listeners) and answers `probe.result`: `blocked` when Gecko reports `NS_ERROR_ABORT` (an
  extension cancelled it), `passed` otherwise. The engine retries every 50 ms until `blocked`.
  uBlock Origin holds every tab request from the moment its background script runs until its
  filters are loaded ("Suspend network activity until all filter lists are loaded", on by
  default), so a normal start needs one probe, answered as soon as it filters. On its very first
  install it does not suspend (it lets requests through while it compiles its lists, about 8 s
  on the emulator); the probe catches that too: probes pass until it filters.
- If an already installed uBlock Origin does not filter within 10 s it is reinstalled once
  (the add-on start-up state quirk of docs/ENGINE.md section 9 applies to it too). On its first
  install (it is not in `WebExtensionController.list()` yet) it is not: it may take longer than
  10 s to compile its lists on a slow phone, and a reinstall would start that over. After 60 s
  without filtering it is reported as failed and the shell shows the error with a way to turn
  ad hiding off.
- With ad hiding off, uBlock Origin is disabled before the first page and not waited for.

Start-up modes (`EngineConfig.startupMode`):

| Mode | What happens at every start |
|---|---|
| `ENSURE_BUILT_IN` | `ensureBuiltIn` for both extensions (install only if the packaged version differs); the bootstrap session makes Gecko start their background scripts |
| `INSTALL_EVERY_START` | `installBuiltIn` for both, which starts their background scripts at once |

Measured over 20 cold starts each on the emulator (`tools/measure-shell-startup.sh`, numbers in
docs/reports/M3a.md section 3), from process start, medians (min to max):

| Mode | twin-bridge ready | uBlock Origin ready (= both ready) | first page shown | probes | passed unfiltered |
|---|---|---|---|---|---|
| `ENSURE_BUILT_IN` | 3,886 ms (3,076 to 7,397) | 5,322 ms (4,290 to 9,095) | 6,320 ms (5,045 to 10,076) | 20 (1 per start) | 0 |
| `INSTALL_EVERY_START` | 4,737 ms (3,915 to 14,079) | 6,378 ms (5,298 to 18,977) | 6,690 ms (5,578 to 19,516) | 20 (1 per start) | 0 |

Chosen: `ENSURE_BUILT_IN` (`AppEngine.DEFAULT_STARTUP_MODE`): about 1 s faster to ready, a
narrower spread, no failed start in 20, and it keeps the extensions' state untouched at every
start. Installing at every start gains nothing: the bootstrap session already starts the
background scripts as soon as Gecko is up.

The proof that no request escapes filtering during start-up is the same script: each of the 40
cold starts opens a mock page whose image `/__utm.gif` is on the list, and the mock's request log
must never show it.

## 6. Saved state and crash recovery

- Gecko reports the session state (history entries, scroll positions, form data) through
  `ProgressDelegate.onSessionStateChange`, but only on its session store timer
  (`browser.sessionstore.interval`, 10 s in GeckoView). `EngineSession` therefore calls
  `flushSessionState()` after every finished load, and the shell again when the activity stops.
- `Shell` writes the latest state, debounced by 300 ms, to `files/shell/state.json` through a
  temporary file, tagged with the site id, so a mock state is never restored into the real site.
  States from before the shell has restored or loaded its first page (the new session's
  `about:blank`) and states without a web page are not saved.
- At start, after `awaitReady`, the shell restores the saved state (`restoreState`: history and
  the current page, which Gecko loads) or loads the start page.
- Back and forward come from `HistoryDelegate.onHistoryStateChange` (current index), which Gecko
  also reports for a restored history; `onCanGoBack` may not fire again after a restore.
- Gecko's back skips history entries that a page added without a user gesture (protection
  against back-button hijacking). Script-driven navigations therefore cannot build a history
  that back walks through; the device script activates links from the keyboard.
- Crash: `onCrash` or `onKill` closes the GeckoSession; the shell shows "The page stopped
  working" with Reload. `EngineSession.recover` reopens the session and restores the last state
  that was taken on a web page (not the state of the page that crashed it), else loads the last
  web page, else the start page. Proven with Gecko's `about:crashcontent`.

## 7. Strict mode

Off by default. When on, twin-bridge registers one blocking `onBeforeRequest` listener that
cancels requests whose host is one of the site's own hosts (or the mock's) and whose path is
exactly one of `extension/data/strict-endpoints.json`: the mobile site's
`/ajax/weblite_load_logging/` and `/ajax/weblite_resources_timing_logging/` (docs/findings/payloads.md
section 12). It is the only code in twin-bridge that cancels anything (`observeOnly.test.ts`
allows `cancel` in `strict.ts` only). Never active while a capture runs: `capture.start`
switches it off before the capture's listeners exist and `capture.stop`/`discard` switch it back.
The app sends `strict.set` before the first site page of every start.

## 8. Tests and scripts

| What | Where |
|---|---|
| Link rules, 47 table cases | `data/src/test/.../LinkRulesTest.kt` (JVM, `tools/check.sh`) |
| Strict mode, probe watcher | `extension/test/strict.test.ts`, `startupProbe.test.ts` (Vitest) |
| Shell on the in-process mock: splash, back, reload, home, menu, settings, rotation, dark scheme, crash recovery, links, uBlock Origin on/off, dashboard and mobile environment, strict mode | `app/src/androidTest/.../ShellScreenTest.kt` |
| Last page and history across process death and device restart | `tools/shell-persistence-test.sh` |
| Start-up modes, 20 cold starts each, nothing escapes filtering | `tools/measure-shell-startup.sh <mode> [runs] [restart-every]` |

The device scripts run the mock on the host (`tools/mock-host.sh start|stop|log`, port 8723,
`adb reverse`), so it survives app kills and device restarts and its request log shows every
page the app loads. Debug-build launch options (`DevOverrides`, ignored by the daily build):

```bash
adb shell am start -n io.github.chabiroael.twinbook.debug/io.github.chabiroael.twinbook.MainActivity \
  --es twinbook.mockOrigin http://127.0.0.1:8723 --ez twinbook.openShell true \
  [--es twinbook.mockStart /shell/ads.html] [--es twinbook.startupMode INSTALL_EVERY_START] \
  [--es twinbook.dashboardPage logger-ui.html]
```

Mock pages for the shell: `mockserver/.../ShellPages.kt` (internal links, the redirect page,
outbound links with tracking parameters, ads page, beacons, long page, form, dark-scheme page).

## 9. Pitfalls

- The emulator's qemu process grows by about 250 MB per cold start of the app (host-side
  rendering) and a guest reboot does not shrink it; at about 9 GB the host's OOM killer ends it.
  Restart the emulator between long device sessions (docs/SETUP.md).
- `tools/emulator-stop.sh` syncs the guest before `emu kill`: without it, an app installed
  seconds before the stop can be lost (seen with the daily app in M3a; docs/reports/M3a.md).
- Never test generic cosmetic filters on 127.0.0.1 (section 4).
- Session state arrives on a 10 s timer unless flushed (section 6).
