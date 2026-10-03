# M3a report: web shell core and uBlock Origin

Branch `m3a-web-shell`, built on `main` at `d011526` (equal to `origin/main` at the start).
Nothing pushed, nothing merged. The `daily` app was never launched.

## 1. Outcome

Complete: the shell, link hygiene, uBlock Origin with a measured start-up contract, strict mode,
the text-input root cause, the carried-over items and the documents are done, and every check in
section 2 passed, with the evidence quoted there. The owner can start using the `daily` build on
the phone: it is updated in place on the emulator with every data file unchanged, and
docs/SHELL-CHECKLIST.md has the phone steps. One incident needs the planner's attention: the
`daily` app's code was missing from the emulator after an emulator kill and was restored by an
in-place update with its data intact (section 9).

## 2. Acceptance checks

| ID | Check | Status | Evidence |
|---|---|---|---|
| S1 | `check.sh` from a clean clone, second run downloads nothing | pass | {{S1}} |
| S2 | whole instrumented suite three times in a row | pass | {{S2}} |
| S3 | device scripts | pass | {{S3}} |
| S4 | shell on the mock: splash, back, reload, home, menu, rotation, dark scheme | pass | `ShellScreenTest` (7 tests, in each suite run). Evidence lines of run A: `S4 splash shown first, then the start page http://127.0.0.1:38791/shell/home.html`; `S4 back: feed -> home in the page's history, app stays in front`; `S4 menu reload: home requested 2 -> 3 times`; `S4 menu home: back on http://127.0.0.1:38791/shell/home.html`; `S4 rotation to landscape: loadCount 3 unchanged, dark.html requested 1 time(s), activity not recreated`; `S4 dark scheme: page reported [light, dark, light] while the system went dark and back to light, without a reload (loadCount 3)`; `S4 back: feed -> home, then back with no history left the shell`; settings: `uBlock Origin 1.75.0 (packaged 1.75.0), Ready \| license GPL-3.0, source https://github.com/gorhill/uBlock (release 1.75.0) \| release asset SHA-256 5b74…5287`. Screenshots: `docs/reports/assets/M3a-shell-light.png` (mock start page, edge to edge, menu button), `docs/reports/assets/M3a-shell-dark.png` (dark-scheme page while the system is dark). |
| S5 | process death and restart restore the last page and history; crash shows recovery and reloads | pass | `tools/shell-persistence-test.sh` (last lines under the table). `ShellScreenTest#crashedContentProcessShowsRecoveryAndReloads`: `S5 about:crashcontent: session crashed=true, recovery view shown`; `S5 recovery: session reopened and its last page reloaded (http://127.0.0.1:38033/shell/feed.html), feed requested 1 -> 2`. |
| S6 | link rules: table-driven tests; on the mock an internal link stays, a wrapped outbound link is unwrapped, stripped, handed over, the redirect page never requested | pass | `LinkRulesTest`: 4 tests, 0 failed; the table holds 47 cases (own hosts, look-alike hosts, user info in the authority, outbound with tracking parameters, the redirect page on both hosts, encoded targets incl. UTF-8 and doubly encoded, nested redirects up to the limit and beyond, targets on own hosts, malformed and empty targets, `javascript:`, `intent:` and `fb:` targets, `tel:`, `mailto:`, `geo:`, other schemes). Device, `ShellScreenTest#linksStayLeaveUnwrappedOrGoToTheSystem`: `S6 outbound: browser got [https://example.com/article?id=7, https://example.org/direct?ref=mock, https://example.net/new-window?k=v]; system got [tel:+15550100, mailto:someone@example.com, geo:25.28,51.53]; /l.php requested 0 times`; the mock's request log for the whole test: `[GET /shell/home.html, POST /log?run=&field=layout, POST /log?run=&field=loaded]` (the redirect page link was `/l.php?u=https%3A%2F%2Fexample.com%2Farticle%3Fid%3D7%26fbclid%3DIwAR0mock%26utm_source%3Dfacebook%26utm_medium%3Dsocial&h=AT0mockHash`); `S6 internal link stayed in the shell: http://127.0.0.1:36291/shell/feed.html?run=`. The real opener on the emulator (dry run, section 6): `START u0 {act=android.intent.action.VIEW cat=[android.intent.category.BROWSABLE] dat=http://localhost:8723/... pkg=com.android.chrome}`. |
| S7 | uBlock Origin fetched with checksum, built in, mobile environment; blocks and hides on the mock; both return with ad hiding off | pass | Fetch: `uBlock Origin 1.75.0: 4650100 bytes, SHA-256 5b74415860456370644bd80f16125e865b0e6c356bb5dfcfb84069967eaa5287 verified, unpacked`; the digest equals the one GitHub publishes for the asset (`sha256:5b74…5287` in the release API). Installed as built-in: `content blocker uBlock0@raymondhill.net 1.75.0 installed (enabled=true, wanted=true)`. Mobile: `S7 uBlock Origin support page: 'Firefox Mobile' found 1 time(s), the auto-selected list 'adguard-mobile' (ua: mobile) 1 time(s)`; screenshot `docs/reports/assets/M3a-ublock-support.png` (`Firefox Mobile: 157`, the list set). Mock, `ShellScreenTest#ublockBlocksAndHidesAndBothComeBackWithAdHidingOff`: `S7 ad hiding on: /__utm.gif requested 0 times; page reports {"banner":false,"byClass":false,"content":true,"pixel":false}`; `S7 ad hiding off (uBlock Origin disabled, not reinstalled): /__utm.gif requested 1 time(s); page reports {"banner":true,"byClass":true,"content":true,"pixel":true}`; `S7 ad hiding on again: Ready(version=1.75.0, probes=1, passed=0); no new /__utm.gif request; page reports {"banner":false,…}`. Dashboard: `S7 dashboard entry opened moz-extension://…/dashboard.html#3p-filters.html`. |
| S8 | start-up contract measured, 20 cold starts per variant; nothing escapes filtering | pass | `tools/measure-shell-startup.sh`, table in section 3. Both variants: `probes: 20 in total, passed unfiltered 0; /__utm.gif requests 0` and `== measure-shell-startup …: PASS (no listed request reached the mock)`. That script is the test for the last point: each of the 40 cold starts opened `/shell/ads.html`, whose image `/__utm.gif` is on EasyPrivacy, and the mock's log never shows it. |
| S9 | strict mode cancels the listed endpoints, off by default, inactive during a capture; observe-only tests pass | pass | Vitest `strict.test.ts` 7 tests and `observeOnly.test.ts` 8 tests pass (the source check now allows `cancel` in `strict.ts` only). Device, `ShellScreenTest#strictMode…`: off by default (`strict.describe` → `enabled false`); `S9 strict off: {weblite_load_logging=2, weblite_resources_timing_logging=2, control_logging=2}; strict on: {…=0, …=0, control_logging=2} (fetch and sendBeacon each)`; `S9 during a capture strict mode is inactive (…"enabled":true,"capturing":true,"active":false…): {weblite_load_logging=2, …}`; active again after the capture. Capture tests (`CaptureRecorderTest`, `ObserveModeTest`, `CaptureBrowserScreenTest`) pass in all three suite runs. |
| S10 | text input: root cause, gating test 20 of 20, burst rate over 20 runs | pass | Section 4. Gating test (`CaptureBrowserScreenTest#textInputFromInputMethodAndKeyEvents`, one key at a time, after the input method is on the field): **20 of 20** fresh-process runs, emulator restarted every 6 runs. Burst diagnostic: **19 ok, 1 swapped (`Hw-Pas1s`), 0 lost** in 20 fresh-process runs. Logcat excerpts in section 4. |
| S11 | real site, logged out: login page with uBlock Origin; third-party trackers of M2a blocked | pass | Section 5; screenshot `docs/reports/assets/M3a-real-site-login.png`. Ad hiding on: 50 requests, 14 cancelled, **no request to any Google host**; ad hiding off: `ad.doubleclick.net` 1, `googleads.g.doubleclick.net` 2, `www.google.com` 6, `www.googletagmanager.com` 1 (10 tracker requests), plus the `www.fbsbx.com` frame they come from. Traffic log: 3 loads used of 12, listed below the table. |
| S12 | `daily` opens the shell, developer screens behind the menu, by inspection only; updated in place, data kept, never launched | pass | {{S12}} |
| S13 | re-scrub in place on a copy; nothing deleted | pass | `cp -a captures/20261002-181317-site-rescrub <tmp>/`; `tools/capture-tools.sh rescrub <tmp>/20261002-181317-site-rescrub --in-place` → `rescrub 20261002-181317-site-rescrub -> <tmp>/20261002-181317-site-rescrub: 1438 lines, 1 bodies, layer 1 {"cookies":280,"setCookies":1,"headers":0,"fields":0,"urlParams":110,"textValues":0}, layer 2 0/0 values eligible, replacements {}, verification hits 0` (the layer 1 counts are placeholders recognised again; nothing new was found because the rules have not changed). Afterwards the temporary directory holds only the copy (no `.partial-`, no `.previous-`), `sha256sum -c checksums.sha256` passes, `events.ndjson` is byte-identical to the copy in `captures/`, the file lists are identical, `session.json` says `{'source': '20261002-181317-site', 'passes': 2, 'rulesVersion': 5}`. `captures/` held 15 entries before and after. Vitest `rescrub.test.ts` adds 3 tests: refused for an original, idempotent with the same rules, and a value a later rule covers is removed from a copy whose original was deleted. |
| S14 | documents | pass | New: `docs/SHELL.md`, `docs/SHELL-CHECKLIST.md`, `THIRD-PARTY.md`. Updated: `docs/SETUP.md` (web shell scripts, daily build behaviour, the uBlock Origin download, emulator memory, `emu kill` and the daily app, AGP asset pattern), `docs/ENGINE.md` (API, bridge methods, packaging, section 10 quirks). Dry-run notes in section 6. |
| S15 | branch clean, nothing pushed, `main` untouched, no uBlock Origin files, recordings or secrets in git | pass | {{S15}} |

{{S3DETAIL}}

S11 traffic log (every load of the real site from this milestone, debug build, emulator, logged
out, nothing typed or tapped on the page):

| # | Time (2026-10-03, +03) | What |
|---|---|---|
| 1 | 02:37:22 | `RealSiteProbe`, first attempt: the shell screen never appeared (a test defect: Compose test frames advance only through the test API), so the page was most likely not requested; counted anyway. |
| 2 | 02:39:14 | `https://m.facebook.com/`, ad hiding on |
| 3 | 02:39:39 | the same page reloaded, ad hiding off |

## 3. Start-up contract

Measured with `tools/measure-shell-startup.sh <mode> 20 5` on the emulator: debug app, shell on
the mock on the host, start page `/shell/ads.html`; each run a cold start (`am force-stop`, saved
state removed); one unrecorded warm-up start after every emulator boot; the emulator restarted
every 5 runs (its memory, section 9). Times from process start (`Process.getStartElapsedRealtime`):

| Mode | twin-bridge ready (median, min to max) | uBlock Origin ready = both ready | first page shown | probes / passed unfiltered | `/__utm.gif` reached the mock |
|---|---|---|---|---|---|
| `ENSURE_BUILT_IN` (current contract) | 3,886 ms (3,076 to 7,397) | 5,322 ms (4,290 to 9,095) | 6,320 ms (5,045 to 10,076) | 20 / 0 | 0 of 20 starts |
| `INSTALL_EVERY_START` | 4,737 ms (3,915 to 14,079) | 6,378 ms (5,298 to 18,977) | 6,690 ms (5,578 to 19,516) | 20 / 0 | 0 of 20 starts |

Choice: `ENSURE_BUILT_IN`, the current contract (`AppEngine.DEFAULT_STARTUP_MODE`): about 1 s
faster to "both ready", a narrower spread, no failed or slow-recovery start in 20, and no
reinstall of either extension at every start. Installing at every start gains nothing, because
the bootstrap session already makes Gecko start both background scripts as soon as it is up.
(A first ENSURE series stopped after 10 runs when an emulator restart hung; those 10 runs, median
both-ready 5,453 ms, are not in the table. The complete series above was run afterwards.)

What "ready" means:

- **twin-bridge**: installed, and its background script has said hello over the native port
  (unchanged since M1).
- **uBlock Origin** (no bridge): it has cancelled a probe request. In the headless bootstrap
  session the engine loads a `data:` page whose only subresource is
  `http://127.0.0.1:65535/__utm.gif?twinbook_startup_probe=<id>` (EasyPrivacy blocks `/__utm.gif`
  everywhere; nothing listens on that port, so an unfiltered probe fails on the device and never
  leaves it). twin-bridge watches those requests with non-blocking listeners and answers
  `probe.result`: `blocked` when Gecko reports `NS_ERROR_ABORT`, else `passed`. The engine
  retries every 50 ms until `blocked`. uBlock Origin holds every tab request from the moment its
  background script runs until its lists are loaded, so in a normal start the first probe is held
  and answered `blocked` as soon as it filters: 40 of 40 cold starts needed exactly one probe.
  On its first install it does not hold requests; there the probes pass until it filters (seen
  once: 4 probes passed, ready after 8.3 s of probing). Either way site pages wait for it.
- `Engine.awaitReady()` returns only when both are ready (or uBlock Origin is switched off), and
  the shell loads no site page before that. If an already installed uBlock Origin does not filter
  within 10 s it is reinstalled once (the add-on start-up quirk of M1); never during its first
  install. After 60 s the shell shows an error with a way to turn ad hiding off.

## 4. Text input

**Root cause.** Key events from a keyboard device pass through the system's input method before
they reach the app (Android's window input pipeline hands every key event to the current input
method first). When the focus moves to another field, the on-screen keyboard (Gboard) restarts
its input on the new field, twice, about 50 ms apart. Keys that arrive before it has restarted on
the new field are lost or, at the boundary, swapped. GeckoView, the page and this app are not
involved: the same keys given to GeckoView directly are never lost.

**Evidence** (`TypingDiagnosticProbe`, mock typing page, 10 repetitions per variant, focus moved
away and back before each, `Hw-Pass1` sent as soon as GeckoView offers the password editor;
`tools/typing-diagnostic.sh probe`):

| Keys sent with | Delay after GeckoView's editor | ok | lost | swapped |
|---|---|---|---|---|
| `input keyboard text` (window input pipeline) | 0 ms | 0 | 10 | 0 |
| `Instrumentation.sendKeySync` (window input pipeline) | 0 ms | 0 | 10 | 0 |
| `GeckoView.dispatchKeyEvent` (past the pipeline and the input method) | 0 ms | 10 | 0 | 0 |
| `input keyboard text` | 300 ms | 9 | 1 | 0 |
| `input keyboard text` | 700 ms | 10 | 0 | 0 |
| `input keyboard text` | 1,500 ms | 10 | 0 | 0 |

Lost keys are always the first ones (`'-Pass1'`, `'w-Pass1'`, `'Pass1'`). Logcat of one lost
repetition (times in seconds):

```
44.469 GoogleInputMethodService.onStartInput(pkg=io.github.chabiroael.twinbook.debug inputType=480a1   <- still the previous field
44.479 twinbook-evidence: TYPING sending rep=2 via=input                                                <- GeckoView already offers the password editor
44.627 GoogleInputMethodService.onStartInput(pkg=io.github.chabiroael.twinbook.debug inputType=81      <- restart on the password field
44.678 GoogleInputMethodService.onStartInput(pkg=io.github.chabiroael.twinbook.debug inputType=81      <- and again, 50 ms later
45.017 twinbook-evidence: TYPING sent rep=2
       result=lost got='-Pass1'                                                                         <- 'H' and 'w' went to the old field's input
```

In the M2c logcat the planner saw the same double restart 290 ms apart. The flake was most
frequent on the first run after an install or boot: then Gboard is cold and its restart slowest.
The per-key test without the new wait failed 4 of 20, exactly the 4 runs that came first after an
emulator boot (runs 1, 7, 13, 19).

**Fixed in this repository:** the test. The gating test now waits until the input method itself
is on the password field (`curEditorInfo` in `dumpsys input_method` shows this app's package and
`inputType=0x81`, then 400 ms for the second restart), and types one key at a time, waiting for
each on the page: **20 of 20**. The burst stays as `keyEventBurstDiagnostic`, which reports and
never fails: **19 ok, 1 swapped, 0 lost** in 20 runs (it waits 500 ms after GeckoView's editor,
as the old test did). Its result is now judged from the set of values the field held: the page
logs every value in a request of its own, so arrival order is not typing order (the first
classification by last arrival had shown 5 false "lost").

**Not fixed, not in this repository:** Android's key pre-dispatch to the input method and
Gboard's restart. A user hits it only with a hardware keyboard (or the emulator's host keyboard),
typing within about 300 ms of tapping into a field: the first one or two characters can be lost.
Typing on the on-screen keyboard is not affected (its keys are delivered by the input method to
the new field after the restart). The owner types on the phone's on-screen keyboard.

## 5. uBlock Origin in this app

- **Fetched** at build time by `:engine:fetchUblockOrigin`: release `1.75.0`, asset
  `uBlock0_1.75.0.firefox.signed.xpi` from GitHub, into `~/.cache/twinbook/downloads`, SHA-256
  checked against `gradle/libs.versions.toml`, unpacked unchanged into the APK at
  `assets/extensions/ublock0/` (661 files). A cached file with the right checksum is reused, so a
  second build downloads nothing. Never committed. GPLv3, recorded in THIRD-PARTY.md and in the
  app's Settings, "Versions".
- **Installed** as a built-in extension (`uBlock0@raymondhill.net`), in the same start-up mode as
  twin-bridge. Packaging needed one change: AGP drops asset directories starting with `_`, and
  without `_locales/` Gecko rejected the extension as invalid; `androidResources.ignoreAssetsPattern`
  is set without that rule in `:app` and `:engine`.
- **Switched**: "Ad hiding" in Settings disables and enables it through GeckoView's
  `WebExtensionController` (`EnableSource.APP`), not a reinstall; the setting is applied before
  the first page of every start; switching on waits until it filters again (328 ms on the
  emulator), then the page reloads. Off means off for the whole app.
- **Updated**: its filter lists update themselves from their own servers (auto-update on). The
  extension itself changes only when the pinned version in `libs.versions.toml` changes;
  `ensureBuiltIn` then reinstalls it with its storage.
- **Dashboard**: Settings, "uBlock Origin dashboard", opens its options page inside the app in a
  session of its own; links from it to the web open in the browser.
- **Lists active** after the first run: uBlock filters, uBlock badware, privacy, unbreak and quick
  fixes, EasyList, AdGuard – Mobile Ads, EasyPrivacy, Online Malicious URL Blocklist, Peter Lowe's
  list (183,710 network and 55,129 cosmetic filters). "AdGuard – Mobile Ads" is off by default in
  uBlock Origin and selected only for a mobile environment.
- **Mobile environment, how verified**: uBlock Origin takes it from its background page's user
  agent (`/\bMobile\b/`); GeckoView's contains `Mobile`. Shown in this app by its own
  troubleshooting information (`Firefox Mobile: 157`, found by the instrumented test on its
  support page and in `docs/reports/assets/M3a-ublock-support.png`) and by the auto-selected
  AdGuard mobile list. Its facebook.com rules for the web-lite client are inside `!#if env_mobile`
  in the default "uBlock filters" list.
- **On the mock**: EasyList turns generic cosmetic filters off for local addresses
  (`@@://127.0.0.1$generichide`, found with uBlock Origin's logger inside the app); debug builds
  resolve `mock.twinbook.test` to the loopback interface for the hiding test.
- **On the real logged-out page** (`RealSiteProbe`, counts by host, type and outcome from
  `diag.netlog`, 20 s after each load):

| | Ad hiding on | Ad hiding off |
|---|---|---|
| requests | 50 | 50 |
| cancelled (`NS_ERROR_ABORT`) | 14: `www.fbsbx.com` sub_frame 1, `m.facebook.com` beacon 8, `m.facebook.com` XHR 3, `facebook.com` image 2 | 0 |
| Google hosts | none | `ad.doubleclick.net` XHR 1, `googleads.g.doubleclick.net` script 2, `www.google.com` image 2 and XHR 4, `www.googletagmanager.com` script 1 |
| `www.fbsbx.com` | frame cancelled | frame 1, beacon 2 |
| other | `z-m-static.xx.fbcdn.net` 32, `scontent.xx.fbcdn.net` 1, `m.facebook.com` main_frame 2 and manifest 1 | the same static files, `m.facebook.com` beacon 12, XHR 2, `www.facebook.com` csp_report 3 |

  The page rendered normally in both (title `Facebook - log in or sign up`). The Google
  advertising pixels that M2a saw coming through the `fbsbx.com` frame are gone with ad hiding on,
  because the frame itself is blocked.

## 6. What was built

Screens (app): the web shell (`Shell.kt`, `ShellScreen.kt`): splash, edge-to-edge GeckoView with
inset padding, a loading bar for loads over 400 ms, a 36 dp overflow button (Reload, Home,
Settings, Developer screens), a recovery view, back in the page's history (predictive back
enabled), no reload on configuration changes, the system colour scheme passed to Gecko; Settings
(ad hiding, strict mode, uBlock Origin dashboard, versions); the uBlock Origin dashboard screen;
`MainActivity` with a small screen stack: the `daily` build opens the shell, the debug build the
developer start screen. Reload is a menu action, not pull to refresh: the site's scrolling
container is invisible from outside, so a pull gesture would either fight its scrolling or reload
by accident and lose a draft. `ShellSettings`, `DevOverrides` (debug-only launch options),
`AndroidOpener` (default browser's package; `tel:`, `mailto:`, `geo:` to the system).

Engine: content blocker support (`BlockerConfig`, `BlockerState`, the start-up probe, enable and
disable, the reinstall rule), two start-up modes, `awaitReady` covering both extensions,
`configurationChanged`, system colour scheme; `EngineSession`: navigation policy
(`NavigationPolicy`, `NavigationRequest`, `NavigationDecision`), extra schemes per session, session
state with a flush after every load, `restoreState`, `recover` after a crash, back and forward from
Gecko's history list. Packaging of uBlock Origin.

twin-bridge: `strict.ts` and `data/strict-endpoints.json` (two endpoints with reasons),
`startupProbe.ts`, `netlog.ts` (`diag.netlog.*`); capture hooks switch strict mode off during a
capture. Layer 1 now recognises layer 2 placeholders as placeholders.

`:data`: `LinkRules` with `rules/links-v1.json` (own hosts, the redirect page, 27 tracking
parameter entries, each with a reason) and `ViewerId`. Capture tools: `rescrub --in-place`,
`replaceFinalizedSession`, `isViewerId`.

Scripts: `tools/mock-host.sh`, `tools/shell-persistence-test.sh`, `tools/measure-shell-startup.sh`,
`tools/typing-diagnostic.sh`; reworked `tools/daily-survival-test.sh`; `tools/emulator-stop.sh`
syncs the guest first; `tools/emulator-start.sh` ignores an emulator that is still going down and
takes `TWINBOOK_EMULATOR_GPU`.

Mock: `ShellPages.kt` (home with internal links, the redirect page `/l.php` with tracking
parameters, direct and new-window outbound links, `tel:`/`mailto:`/`geo:`, a link to `localhost`
for manual checks; feed; long page; form; dark-scheme page; ads page with two listed elements, a
control element and `/__utm.gif`; beacons page; the logging endpoints), `/typing.html`, a host mode
(`MockHost.kt`, `application` plugin) and a fixed port option.

Tests: `ShellScreenTest` (7), `LinkRulesTest` (4, 47 table cases), `ViewerIdTest` (2), Vitest
`strict.test.ts` (7), `startupProbe.test.ts` (4), 3 new in `rescrub.test.ts`, viewer id checks;
`CaptureBrowserScreenTest` gating test reworked and `keyEventBurstDiagnostic` added;
`TypingDiagnosticProbe` and `RealSiteProbe` (manual probes).

Documents: `docs/SHELL.md`, `docs/SHELL-CHECKLIST.md`, `THIRD-PARTY.md`; `docs/SETUP.md` and
`docs/ENGINE.md` updated.

Checklist dry-run on the mock (debug build, host mock): update in place: done for real on the
emulator (S12). First start: splash, then the page, `shell engine ready … 5,132 ms` and first page
in about 6 s. Ad hiding off and on from Settings: both work, the page reloads, on again filters
after one probe (328 ms). Outbound link: the `localhost` link opened Chrome with
`pkg=com.android.chrome`; the dry run found a defect, fixed: the first version used Android's
browser selector, which showed an "Open with" list including Calendar and Camera. Kill and reopen,
restart: `tools/shell-persistence-test.sh`. Rotation and dark mode: `ShellScreenTest`. Comment
draft: text typed into the mock form stayed after switching to the launcher and back, and also
after `am force-stop` and a relaunch (Gecko's session state holds form data). Back: tested. Not
dry-run: logging in, counting sponsored posts and anything on the logged-in site; a real phone;
Chrome's own first-run screen was not accepted (it would contact Google).

Commits on the branch (oldest last):

```
{{COMMITS}}
```

## 7. Measurements

| | Before (main) | After |
|---|---|---|
| Debug APK | 199,073,140 bytes | 204,181,565 bytes (+5.1 MB) |
| uBlock Origin in the APK | | 661 files, 15.7 MB unpacked, 5.25 MB compressed |
| Native libraries per ABI, compressed | | x86_64 87.2 MB, arm64-v8a 80.4 MB (unchanged GeckoView) |

Memory, total PSS of every process of the app, shell on the mock start page, 30 s after a cold
start, three samples each (`dumpsys meminfo` per process):

| | Total PSS | main | GPU | 3 content/extension processes | crash helper |
|---|---|---|---|---|---|
| Ad hiding on (uBlock Origin) | 729, 726, 729 MiB | 271 to 274 | 86 | 95 + 102 + 111 | 58 |
| Ad hiding off | 678, 678, 677 MiB | 230 to 231 | 86 | 95 + 99 + 105 | 58 |

uBlock Origin costs about 51 MiB, 43 of them in the main process. (The "on" samples were taken
while the instrumentation process was still alive; its 25 MiB are subtracted.)

Cold start to first page on the mock (20 cold starts, `ENSURE_BUILT_IN`): median 6,320 ms, 5,045
to 10,076 ms; both extensions ready at a median of 5,322 ms. For comparison M1 measured the
extension ready at about 3.9 s and the lab page at about 5.2 s with twin-bridge alone.

## 8. Deviations

1. **The mock under a host name for element hiding.** EasyList excepts `127.0.0.1` and
   `localhost` from generic cosmetic filters, so no default cosmetic filter can hide anything on
   the mock's address. Debug builds give Gecko a preference file (`network.dns.localDomains =
   mock.twinbook.test`); the hiding test loads the mock under that name. The daily build never
   reads that file.
2. **Real-site traffic counted with a twin-bridge diagnostic** (`diag.netlog.*`, host names,
   types and error codes only) inside a manual instrumented probe of the debug app. No other tool
   contacted the site.
3. **A mock on the host** (`tools/mock-host.sh`, `adb reverse`) for the device scripts, because
   the in-process mock dies with the app process. Same code as the in-process mock.
4. **Ad hiding off switches uBlock Origin off for the whole app**, not only for the site's hosts:
   the shell shows only the site, and GeckoView has no per-site switch for an extension.
5. **Strict mode lists the two endpoints marked "mobile site" in payloads.md section 12** and not
   `/a/bz` (the logged-out mobile telemetry of M2a), which section 12 does not name. Question 1.
6. **Host list includes `fbcdn.net` and `fbsbx.com`** besides `facebook.com`: both are the site's
   own domains in the recordings; with only `facebook.com` a photo opened on its own or an
   attachment would leave the app.
7. **The burst diagnostic waits 500 ms** after GeckoView's editor, like the old test, instead of
   sending at once: at once it fails 10 of 10 (section 4), which says nothing new.
8. **Emulator restarts inside long device runs** (start-up measurement, typing runs, before each
   suite run): qemu grows about 250 MB per app start and the host's OOM killer ended it twice.
   Restarts are never inside a measured interval or a test.
9. **Reworked `daily-survival-test.sh` writes its marker into the daily app's storage** with
   `run-as` (as the prompt asks); it overwrites the marker of M2a, nothing else.

## 9. Problems and risks

1. **The `daily` app's code went missing on the emulator, data intact; restored.** After the
   host's OOM killer ended the emulator (about 23:24), the next boot had `pm list packages` without
   `io.github.chabiroael.twinbook.daily` and `dumpsys package` showing `pkg=null`, the same
   `firstInstallTime=2026-10-02 10:43:38`, `lastUpdateTime=2026-10-02 21:02:24`, and boot log
   lines `PackageManager: Odd, missing scanned package io.github.chabiroael.twinbook.daily` and
   `Skipping PackageSetting … due to missing metadata`. Its APK directory from 21:02 was gone; its
   data directory (2,806 files: `files/captures` with 4 finalized sessions, `files/mozilla` with
   the GeckoView profile and `cookies.sqlite` of 18:15, the marker) was intact. The AVD's
   `emulator-user.ini` was written at 21:02:26, two seconds after that update, so the emulator
   was stopped right after it; `tools/emulator-stop.sh` used `adb emu kill`, which ends qemu
   without the guest writing its page cache. Most likely the 21:02 update (before this session)
   never reached the virtual disk; the OOM kill at about 23:24 may have contributed; I cannot tell
   which. Recovery: `tools/daily-install.sh` (the in-place update the prompt allows), at 23:33:
   `Success`, `firstInstallTime` unchanged, and a listing of all 2,806 files (name, size,
   modification time) identical before and after; checked again after the second OOM kill:
   identical. The login itself cannot be verified without launching the app; the cookie file is
   unchanged. Fix: `emulator-stop.sh` now runs `sync` in the guest first, and every daily update
   ends with `sync`. **Before the owner's next session on the emulator, nothing to do; on the
   phone this cannot happen (no `emu kill`).**
2. **Emulator memory.** qemu grows by about 250 MB per cold start of the app and a guest reboot
   does not give it back; at about 9 GB the host's OOM killer ends it (twice in this milestone),
   which is a power cut for the AVD. M2b's freeze was probably the same. Device sessions now
   restart the emulator regularly; docs/SETUP.md explains it. This limits long device tests and is
   a risk for the owner's emulator sessions.
3. **First start of the updated daily app on the phone.** twin-bridge is reinstalled (new
   version) and uBlock Origin installed for the first time: it compiles its lists (8 s on the
   emulator) and downloads the AdGuard mobile list. The splash stays meanwhile; the shell waits
   up to 60 s for it, then shows an error with "turn ad hiding off". The site is then restored or
   loaded with the existing cookies, so the owner should stay logged in. Strict mode is off.
4. **Cosmetic filtering of the logged-in site is untested by anyone but the owner.** uBlock
   Origin's facebook.com rules for web lite exist, but whether they still match the owner's
   markup is what the trial measures. Rules are matched by text such as `Sponsored`; a page in
   another language may differ.
5. **The ⋮ button covers a 36 dp corner of the page** (bottom right). On the mock it overlaps the
   right end of the last link. On the logged-in site it may cover a control; the checklist asks.
6. **Session state on disk holds form data** (a comment draft survives a process kill). It is in
   app-private storage, like any browser's session store; password fields are not saved by Gecko.
7. **Outbound links without a gesture are dropped.** If the site opens a link from a script after
   a delay longer than Gecko's user-activation window, nothing happens. Not seen on the mock.
8. **Hosts not in the recordings** (`fb.me`, `fb.com`, `messenger.com`, `instagram.com`, Meta
   account pages) open in the browser. If the site sends the owner through one of them (for
   example an account or security page), that step happens in the browser.

## 10. Questions for the planner

1. Strict mode: add `/a/bz` (the logged-out mobile telemetry seen in M2a) and the desktop
   endpoints of section 12 (`/ajax/bnzai`, `/ajax/comet_error_reports/`, `/ajax/qm/`), or keep
   the two web-lite beacons until the owner's trial shows strict mode does not break anything?
2. Should `fb.me` and `fb.com` (the site's short links) be own hosts? They are not in the
   recordings, so they open in the browser now.
3. The emulator's memory growth: accept regular restarts as the working rule, or should a later
   milestone try a different AVD (for example a newer emulator or system image)?

## 11. Suggestions for M3b and for the owner's trial

- Trial: follow docs/SHELL-CHECKLIST.md on the phone; the sponsored-post counts with ad hiding on
  and off decide whether M3b needs the structure probe for own cosmetic rules.
- Trial: note whether anything in the site needs a host the shell sends to the browser.
- M3b: file upload and the photo picker, downloads (the attachment host `fbsbx.com` already stays
  in the shell), camera and microphone limited to the site's hosts, full-screen video; a
  per-site exception list for outbound hosts, filled from the trial; a setting for the menu
  button's corner if the trial says it is in the way.
- M3b: run the shell tests and scripts on the real phone over wireless debugging; the emulator's
  memory growth makes long device runs fragile.
- Later: a release build will need the `diag.*` methods (now including `diag.netlog`) compiled
  out, as already planned for M12.
