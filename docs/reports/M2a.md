# M2a report: capture tooling and real-site wiring

Branch `m2a-capture-tooling`, 14 commits on top of `main` (`6cc2905`). Build agent: Claude
Opus 5.5, working alone on 2026-10-02. Nothing pushed, nothing merged, `main` untouched.

## 1. Outcome

Complete: all 21 checks pass, on the mock and on the logged-out real site (4 of the 12
allowed page loads used). The `daily` build is ready for the owner to log in and follow
`docs/CAPTURE-CHECKLIST.md`; its data survived the full test suite, the M1 device scripts, an
in-place update and an emulator restart. Two points for the planner before or during the owner
step: capture makes requests slower (section 6), and one logged-out beacon carried an
undocumented opaque value, now redacted as a precaution (sections 3 and 9).

## 2. Acceptance checks

### Tooling, proven on the mock

| ID | Status | Evidence |
|---|---|---|
| C1 | pass | Fresh clone of the branch at `27b72d3`, `env -i HOME=$HOME bash tools/check.sh`: `Test Files 13 passed (13)`, `Tests 116 passed (116)` (extension), `lint: errors 0, warnings 1 (allowed 1)`, JVM `CaptureStoreTest 3`, `TaintScrubberTest 3`, `DataStatusTest 1`, `CapturePagesTest 4`, `MockServerTest 8`, `ScenariosTest 10` (29, 0 failures), `BUILD SUCCESSFUL`, `== check.sh: all checks passed`. The tail is quoted in section 4's last paragraph. |
| C2 | pass | New tests in `extension/test/ndjsonFilter.test.ts` force a throw outside `processLine`'s handlers (a decision whose `action` getter throws) at every chunk boundary and with 40 random cuts. On the old code: `× loses no byte when processing line 0 throws, at every chunk boundary`, same for lines 2 and 4 and the multi-chunk case, `AssertionError: cut at 1: expected '{"data":{"node":{"id":"4"…' to be 'for (;;);{"data":…'`, `Tests 4 failed \| 23 passed (27)`. After the fix (`dd54480`): `Tests 27 passed (27)`. |
| C3 | pass | `ObserveModeTest`, all 12 M1 scenarios over XHR and fetch, enforce then observe. Each line like `C3 ads-all/xhr: observe page sha256 04a3cfc3b8333ef2 == unfiltered 04a3cfc3b8333ef2 (enforce d032094602e4fd64); decisions enforce {documents=5, kept=2, dropped=1, replaced=2, failedOpen=0, guard=true} observe {…same…}`; `large/xhr`: `observe b3c44ba6… == unfiltered b3c44ba6… (enforce b44a7d02…); decisions {documents=5001, kept=4899, dropped=100, replaced=2…}` both; `malformed`: `failedOpen=1` both; `no-ads`: all three hashes equal. Documents: `C3 document: observe islands byte-identical to the input; changed enforce 1 observe 1`. |
| C4 | pass | `CaptureRecorderTest#recordsEveryRequest…`: `C4 12 server requests, all 12 found in the capture (12 request records, 8 bodies)`, then per request `GET /test.html -> 200; body sha256 73d7886660d7 matches (259 bytes, 1 chunks)`, `POST /api/graphql/ -> 200; body sha256 9ff50902a23e matches (1332 bytes, 5 chunks)` (×2), `GET /static/test-page.js -> 200; metadata only (script)`, `POST /report -> 204; metadata only` (×3), `GET /document`, `GET /secrets/page`, `POST /secrets/form`, `GET /secrets/json`, `POST /async/wbloks/fetch/`, all `-> 200; body sha256 … matches`. Method, URL (equal outside layer 1 placeholders), status and body hash are compared with the mock server's own log of what it served. |
| C5 | pass | Same test, before finalize: `C5 before finalize: 24 header records, 14 cookie headers, e.g. [Cookie: persist_plain=!R*************!; … mock_sess=!R*****************************!; c_user=!R************!]; form fields {fb_dtsg=!R…!, lsd=!R…!, jazoest=!R**!, __rev=1007000000, __req=1, …}; json url …/secrets/json?run=…&fb_dtsg_ag=!R…!; no cookie or keyed token value in any record`. Asserted: `Set-Cookie: mock_sess=!R…!; Path=/; HttpOnly; SameSite=Lax; Max-Age=3600`. Also in Vitest (`observeOnly.test.ts`) against the real `filters.ts`/`capture.ts` with a fake WebExtension API. |
| C6 | pass | `C6 scan of 12 finalized files for 4 planted secrets in all encodings: 0 hits; secrets page now: window.__boot = ["!T:field:fb_dtsg!", !T:cookie:c_user!];` The dtsg token sat unkeyed in the HTML before it was first seen as a form field. `taint {"minTaintLength":8,"rememberedValues":13,"eligibleValues":11,"replacements":5,"byLabel":{"cookie:mock_sess":2,"field:fb_dtsg":1,"cookie:c_user":2},…,"verifyHits":0}`. |
| C7 | pass | `tools/capture-kill-test.sh` on `27b72d3`: `CAPTURE_ID probe-open-… left open`, `no process of io.github.chabiroael.twinbook.debug is running`, `probe-open-1790929623029 NOT-finalized 88KiB`, `REFUSED: session probe-open-1790929623029 is not finalized; it cannot be pulled (it is deleted at the next app start)`, `pull refused (exit 3); nothing was copied`, `W twinbook-app: deleted unfinalized capture sessions at start: [probe-open-1790929623029]`, `== capture-kill-test: PASS`. |
| C8 | pass | `CaptureRecorderTest#losslessUnderLoad`: `C8 200 responses (one of 5500000 bytes, 199 of 4000 bytes): 200 records with body hashes verified against the served bytes and the stored files; capture page time 4777 ms vs off [1400, 1145] ms; finalize 513 ms; transport {"failed":false,"batches":199,"bytes":9470664,"items":1442,"pressureEvents":0,"retries":0,…}`. Earlier runs: 3269 and 4231 ms on, 594 to 1159 ms off. |
| C9 | pass | `tools/capture-pull.sh --debug`: `list` shows `probe-finalized-1790927953980 finalized 104KiB` and `probe-open-1790927959526 NOT-finalized 88KiB`; `pull probe-finalized…`: `checksums: 6 files verified, FINALIZED matches`, `== pulled … -> captures/probe-finalized-1790927953980`, summary `layer 2: 8 values remembered, 7 eligible, 5 replacements, verification hits 0`; `pull probe-open…`: `REFUSED: … not finalized`, `exit 3`; `pull-all` pulled 4 more and refused the open one. `git status --short` afterwards: only my own uncommitted script fix, nothing from `captures/`. |
| C10 | pass | `CaptureBrowserScreenTest` (3 tests) in the full run on `27b72d3`, and 3 of 3 repeated runs of the text test: `C10 text input: email via InputConnection.commitText reached the page as 'ime-user@example.test'; password via keyboard key events (input keyboard text) reached it as 'Hw-Pass1'`; `scheme link: load ignored (scheme 'fb'), still on …/nav.html…, app in the foreground`; `intent link: load ignored (scheme 'intent')…`; `newwin: new window loaded in the same session: …/blank.html?newwin=1`; `open: … /blank.html?opened=1`; `back: returned to …/nav.html… twice`; `permissions: geolocation denied:1, notification denied, denials counted 2`; `dialogs: alert closed, confirm OK then Cancel -> [true, false], prompt -> typed answer`; `reload: the page loaded again`. Screenshot: `docs/reports/assets/m2a-c10-capture-browser-mock.png` (capture running, both fields filled, on-screen keyboard up). |
| C11 | pass | Full `tools/connected-test.sh` on `27b72d3`: `BUILD SUCCESSFUL`; `CaptureBrowserScreenTest 3`, `EngineLabScreenTest 3`, `BridgeTest 7`, `CaptureRecorderTest 3`, `DocumentFilterTest 1`, `ObserveModeTest 3`, `ReplayProbeTest 1`, `SessionsTest 2`, `StreamFilterTest 14`: 37 tests (the 28 of M1 plus 9 new), 0 failures. `tools/persistence-test.sh`: `PERSIST verify: PASS`, `== persistence-test: PASS`. `tools/extension-update-test.sh`: `version A: 0.1.0.228236756`, `version B: 0.1.0.95608873`, `== extension-update-test: PASS`. |

### Login-safe install

| ID | Status | Evidence |
|---|---|---|
| C12 | pass | `adb shell pm list packages`: `package:io.github.chabiroael.twinbook.engine.test`, `package:io.github.chabiroael.twinbook.debug`, `package:io.github.chabiroael.twinbook.daily`; `versionName=0.1.0-daily firstInstallTime=2026-10-02 10:43:38`, `versionName=0.1.0-debug firstInstallTime=2026-10-02 11:05:40`. Launcher labels "twinBook daily" and "twinBook debug". |
| C13 | pass | `tools/daily-survival-test.sh`: before `marker: survives-1790928067`, `firstInstallTime=2026-10-02 10:43:38`; after `connected-test.sh` (`BUILD SUCCESSFUL in 2m 35s`), after `persistence-test.sh` and `extension-update-test.sh` (both PASS), after `daily-install.sh` (`updating … in place (data kept)`, `lastUpdateTime=2026-10-02 11:05:01`): same marker, same `firstInstallTime`; `== daily-survival-test: PASS`. The marker and `firstInstallTime` were unchanged again after a second full suite, both M1 scripts, C7, and an emulator restart in window mode (`survives-1790928067`, `firstInstallTime=2026-10-02 10:43:38`). |

### Real site, logged out, within the traffic budget

| ID | Status | Evidence |
|---|---|---|
| C14 | pass | `docs/reports/assets/m2a-c14-mobile-site.png` (m.facebook.com login page, Mobile site selected, capture running) and `docs/reports/assets/m2a-c14-desktop-site.png` (www.facebook.com desktop login page, Desktop site selected). User agents as captured: `https://m.facebook.com/ \| UA: Mozilla/5.0 (Android 16; Mobile; rv:157.0) Gecko/157.0 Firefox/157.0` and `https://www.facebook.com/ \| UA: Mozilla/5.0 (X11; Linux x86_64; rv:157.0) Gecko/20100101 Firefox/157.0`. |
| C15 | pass | Session `20261002-110721-site`: `Last session …: FINALIZED, 15 files, 1.4 MB, 100 taint replacements`; pull: `checksums: 16 files verified, FINALIZED matches`; summary table in section 3. Observing filter attached to both documents and to 12 XHR responses; `responses observed 14, documents probed 61, responses where bytes out != bytes in: 0`. |
| C16 | pass | Cookie names with attributes, no values (section 3). Layer 1: `{"cookies":167,"setCookies":26,"headers":12,"fields":3,"urlParams":316,"textValues":22}` (546). Layer 2: `49 values remembered, 41 eligible, 100 replacements` (`field:nonce` 92, `cookie:datr` 6, `field:token` 2), `verifyHits 0`. Residual scan of every stored file for keyed values left unredacted: `JSON keyed token value 0`, `input tag value 0`, `key=value 0`. |
| C17 | pass | Tests: `ObserveModeTest#siteProfileRefusesEnforce`: `filter.setMode site enforce -> mode_not_allowed`, `replay.fetch https://www.facebook.com/api/graphql/ -> forbidden_host (nothing sent)`, `filter.describe … "siteListening":false`. Vitest `observeOnly.test.ts` (8 tests): no content script matches any site URL; no `executeScript`, `insertCSS`, `contentScripts.register`, `userScripts`, `browser.tabs`, `scripting.` anywhere in `src/`; no blocking response with `cancel`, `redirectUrl` or `responseHeaders`; `requestHeaders` returned only in `replay.ts`; without a capture no blocking listener covers any site URL; during a capture the only one is `onHeadersReceived ["blocking","responseHeaders"]`, and every listener returns `{}` or nothing; a site document and a guarded site XHR reach the page byte for byte; scripts and media of a site page get no stream filter. Excerpts below the tables. |
| C18 | pass | Traffic log in section 3: 4 page loads, all logged out, no form submitted, nothing typed, no button or link on any site page tapped. |

### Documentation and housekeeping

| ID | Status | Evidence |
|---|---|---|
| C19 | pass | New: `docs/CAPTURE.md`, `docs/CAPTURE-CHECKLIST.md`. Updated: `docs/ENGINE.md` (layout, API, bridge methods, profiles and modes, taps, tests, mock endpoints, quirks 13 to 16), `docs/SETUP.md` (build types, capture scripts, section 7 "Protecting the owner's session"). Dry-run notes below. |
| C20 | pass | Vitest `harImport.test.ts`: a synthetic HAR built from the mock's pages (HTML with the token unkeyed and an `lsd` input, a GraphQL form post, a base64 JSON body echoing cookie values, a login post with `email`/`encpass`, a redirect with `fb_dtsg_ag`, a third-party image); every planted value searched in all seven encodings: 0 hits; checksums and FINALIZED verified. CLI smoke run: `imported 2 entries … 2 bodies, layer 1 {"cookies":4,…,"fields":1,…,"textValues":1}, layer 2 4/4 values eligible, replacements {"cookie:c_user":2}, verification hits 0`; `scan: no planted value in 6 output files`. |
| C21 | pass | See section 4's last paragraph (git status, log, scan). |

C17 excerpts. `extension/manifest.json` (built `dist/manifest.json` has the same plus the stamped version):

```json
"permissions": ["webRequest", "webRequestBlocking", "nativeMessaging", "geckoViewAddons", "storage",
  "http://127.0.0.1/*", "http://localhost/*", "*://facebook.com/*", "*://*.facebook.com/*", "*://*/*"],
"content_scripts": [{ "matches": ["http://127.0.0.1/anchor", "http://localhost/anchor"], "js": ["anchor.js"], "run_at": "document_idle" }]
```

`extension/src/lib/profiles.ts`:

```ts
export const SITE_PROFILE: SiteProfile = Object.freeze({
  name: "site",
  urlPatterns: Object.freeze(["*://facebook.com/*", "*://*.facebook.com/*"]),
  allowedModes: Object.freeze(["observe", "off"] as FilterMode[]),
  defaultMode: "observe",
  replay: false,
  rewriteHeaders: false,
  rule: "probe",
  ...
```

`filters.ts`: the site's blocking listener is added by `setSiteListening(true)` when a capture
of the site starts and removed when it stops; `onHeaders` ends with `return {}` on every path;
in observe mode `this.filter.write(data)` writes the original buffer before a copy goes to the
core and the recorder. `replay.ts`: `assertReplayAllowed(request.url)` throws `forbidden_host`
before any fetch unless the host's profile has `replay: true`; the header-rewrite listener's
URL filter is built only from profiles with `rewriteHeaders: true` (the mock).

C19 dry-run notes, step by step through `docs/CAPTURE-CHECKLIST.md`:

1. `tools/emulator-stop.sh`, then `tools/emulator-start.sh --window`: booted in 33 s, qemu
   running without `-no-window`, the known `wayland` message only. The daily data survived.
2. `tools/daily-install.sh`: first install printed `installing … for the first time`, `Success`;
   later runs `updating … in place (data kept)`.
3. `tools/daily-launch.sh`, tap Capture browser: start screen, then the capture browser; the
   address line showed `https://m.facebook.com/` once the engine was ready (load 4 of the log).
4. Login: not possible without an account. Done on the mock instead: `CaptureBrowserScreenTest`
   types into a login-like form through an input method and through keyboard key events
   (screenshot C10). The emulator AVD already has `hw.keyboard=yes`, which the host keyboard
   needs; I did not type into the window myself.
5. Start capture on daily: status `Recording 20261002-111151-site: 9 records, 0.0 MB, 25
   redactions, 0 errors`.
6, 7. Browsing and the site switch: done on the mock (C10 test) and on the logged-out real site
   (loads 2 and 3, debug build).
8. Discard on daily: `Last session 20261002-111151-site: NOT finalized (discarded)` and
   `capture-pull.sh list` empty. New capture, Stop and finalize:
   `Last session 20261002-111209-site: FINALIZED, 1 files, 0.0 MB, 0 taint replacements`.
9. `tools/capture-pull.sh list` / `pull 20261002-111209-site` (default mode, daily app):
   `checksums: 2 files verified, FINALIZED matches`; `node tools/capture-summary.mjs` ran on the
   real-site session (section 3).
10. The kill and restart path is C7; the Discard path was step 8.
11. `tools/har-import.sh` CLI smoke run (C20).

What the dry run changed in the checklist: the Discard rule for security checks, the note that
links into the Facebook app do nothing, and the pull lines to report.

## 3. Real-site observations

Session `20261002-110721-site`, debug build, logged out: load 2 (mobile site, reload with
capture on) and load 3 (desktop site). 429 records, 77 requests (31 to own hosts, 46 third
party), 14 bodies, 1.4 MB.

| Aspect | Observation |
|---|---|
| Requests by host and type | own: `m.facebook.com` main_frame 1, xmlhttprequest 4, beacon 14, web_manifest 1; `www.facebook.com` main_frame 1, xmlhttprequest 8, beacon 1; `facebook.com` image 1. Third party: `z-m-static.xx.fbcdn.net` script 17, `static.xx.fbcdn.net` script 16 (+ stylesheet, image, font 1 each), `scontent.xx.fbcdn.net` image 1, `www.fbsbx.com` sub_frame 1 and beacon 2, `googleads.g.doubleclick.net` script 2, `www.google.com` image 2, `www.googletagmanager.com` script 1, `www.instagram.com` xmlhttprequest 1 |
| Content types | own: `application/x-javascript` 16, `image/png` 11, `text/html` 2, `image/gif` 1, `application/json` 1; third party mostly `application/x-javascript` 33 |
| Encodings | own: `zstd` 31 of 31; third party: `zstd` 37, `br` 2, identity 7. Bodies arrive in the stream filter decoded: stored files are plain HTML and `for (;;);{…}` text |
| Protocols | every own-host response `HTTP/3.0`; third party `HTTP/3.0` 29 and `HTTP/2.0` 17; TLS `TLSv1.3 TLS_AES_128_GCM_SHA256` |
| Cache, service worker | 18 third-party scripts `fromCache=true` (second load); every request had a tab id, none `-1`: no service-worker fetch seen while logged out |
| Documents | mobile HTML 157,181 bytes with 0 JSON islands (Bloks data lives in inline scripts); desktop HTML 457,997 bytes with 49 JSON islands, all probed |
| Observing filter | attached to both documents and to 12 XHR responses (4 `m.facebook.com/a/bz`, 7 `www.facebook.com/ajax/bz`, 1 `/ajax/webstorage/process_keys/`); 61 documents probed; no candidate key found (logged out); bytes out equal bytes in on all 14; beacons (`sendBeacon`) are not tapped |

Section 2 leads of the prompt:

| Lead | Result |
|---|---|
| m.facebook.com with a mobile UA serves Bloks (`StartFBWebBloks`, `bloks_payload`, `/async/wbloks/…`) | confirmed: both markers in the mobile document; 11 requests to `/async/wbloks/log/` (beacons). No `/async/wbloks/fetch/` while logged out |
| redirect to `/unified/login_via/app/` and a custom-scheme navigation | not seen: the document stayed at `https://m.facebook.com/`, the string is absent from every body, and the navigation policy denied no load in either session (no `ignored a load` in logcat). With GeckoView's Firefox-for-Android UA the site served the login page directly |
| www.facebook.com with a desktop UA serves Comet (`__comet_req`, `/api/graphql/`) | `__comet_req` confirmed (`__comet_req=15` on `/ajax/bz`, `__hs=…comet_loggedout_pkg…`); `/api/graphql/` neither requested nor named in a body while logged out: unconfirmed |
| telemetry posts to `/ajax/bz` and `/ajax/qm/` | confirmed: 7 and 1 (desktop); the mobile site posts to `/a/bz` instead (4 XHR, 3 beacons) |
| third-party advertising pixels from Google hosts through `fbsbx.com` | confirmed: a `www.fbsbx.com` frame on the mobile page, from which `googleads.g.doubleclick.net`, `www.google.com` and `www.googletagmanager.com` were contacted (5 requests); doubleclick set an `IDE` cookie |
| rotating tokens `fb_dtsg`, `lsd`, `jazoest`; `__user`, `__s`, `__hsi`, `__dyn`, `__csr`, `__rev`, `__req` | all present while logged out: `fb_dtsg` (7 URLs, value redacted), `lsd` (25 URLs, 2 forms, `"LSD",[],{"token":…}` in both documents), `jazoest` (26 URLs, 1 form), `DTSGInitialData` named in a document, `__user` 15, `__s`, `__hsi`, `__rev`, `__spin_r` 7 each, `__dyn`, `__csr` 4 each, `__req` 11 |

How the site treats this browser: both login pages render normally in GeckoView 157 with its
own user agents; no interstitial, no "unsupported browser" page, no app redirect. One
unexpected field: the mobile `/a/bz` beacon carried `__a=` with an opaque value of about 100
characters instead of the usual `1`. It is not in the prompt's lists; I added `__a` to the
secret keys afterwards (section 7). The capture taken before that change has it unredacted;
it is logged-out, anonymous data and stays outside git.

Redaction on this capture (C16). Set-Cookie, name: value state; attributes:

```
m.facebook.com fr: value redacted (69 chars); expires=Thu, 31-Dec-2026 08:07:30 GMT; Max-Age=7776000; path=/; domain=.facebook.com; secure; httponly; SameSite=None   (19 times, both hosts)
googleads.g.doubleclick.net IDE: value redacted (64 chars); expires=Sun, 01-Oct-2028 08:07:31 GMT; path=/; domain=.doubleclick.net; Secure; HttpOnly; SameSite=none
googleads.g.doubleclick.net test_cookie: empty value; domain=.doubleclick.net; path=/; expires=Fri, 01-Aug-2008 22:45:55 GMT; SameSite=none; Secure
www.fbsbx.com _gcl_au: value redacted (7 chars); expires=Thu, 01-Jan-1970 00:00:01 GMT; Max-Age=-1790928450; path=/; domain=www.fbsbx.com; httponly
```

Cookie request headers: `datr`, `fr`, `sb`, `m_pixel_ratio`, `wd` (31 each), `dpr` (9), all
values redacted. No response inside the capture set `datr`, `sb` or `wd`; they date from load 1
(`wd` and `m_pixel_ratio` look script-set).

Traffic log (C18). Times UTC (machine clock UTC+3).

| # | Time | Build | What | Purpose |
|---|---|---|---|---|
| 1 | 08:06:04 | debug | tapped "Capture browser" on the start screen; the session loaded `https://m.facebook.com/` (mobile UA), capture off | first-load wiring, mobile screenshot |
| 2 | 08:07:27 | debug | tapped "Start capture", then the app's "Reload"; `https://m.facebook.com/` again | C14, C15 mobile capture |
| 3 | 08:08:04 | debug | tapped "Desktop site": first load of `https://www.facebook.com/` (desktop UA) | C14, C15 desktop capture |
| – | 08:08:50 | debug | "Stop and finalize"; app force-stopped at 08:09:19 | |
| 4 | 08:11:13 | daily | checklist dry run: tapped "Capture browser"; `https://m.facebook.com/` (mobile UA), then Start capture, Discard, Start capture, Stop and finalize; app force-stopped at 08:12:31 | C19 dry run of the owner's steps |

Four page loads of the 12 allowed. Site-initiated requests from those pages (beacons, scripts,
the fbsbx frame) are part of them. I did not submit any form, type into any field, or tap any
button or link on a page of the site; every tap was on the app's own controls (start screen,
site switch, Reload, capture buttons), located through `uiautomator dump`. No other tool
contacted the site: no curl, no scripts, no browser automation. The only other code path that
names a site URL, the `replay.fetch` refusal test, is refused before anything is sent.

## 4. What was built

Site profiles and filter modes (`extension/src/lib/profiles.ts`, `filters.ts`,
`background.ts`). Profiles `mock` and `site`. Modes `enforce`, `observe`, `off` per profile
through `filter.setMode`; `site` is limited to observe and off, defaults to observe, has no
replay, no header rewrite, no content script, and its blocking listener exists only while a
capture of it runs. Observe mode in both filter cores (decisions counted, input forwarded,
nothing serialized) and in the tap (original buffer written first). Probe rule
(`src/lib/probe.ts`, candidates in `extension/data/probe-keys.json`).

Recorder (`extension/src/capture.ts`, `src/lib/record.ts`). Seven non-blocking metadata
listeners on `*://*/*`, registered for the duration of a capture, recording every request of a
captured profile's pages with every details field; request bodies (form fields parsed, raw text
up to 256 KiB) and textual document/XHR response bodies of own hosts up to 8 MiB each, with
SHA-256 and a chunk log; `security` lines from `getSecurityInfo`; `observe` lines with probe
reports.

Redaction. Layer 1 in `src/lib/redact.ts` with `extension/data/redaction-rules.json` (11
credential headers, 28 secret keys, one `why` each), placeholders of the same length. Layer 2:
`capture/src/main/kotlin/…/TaintScrubber.kt` (finalize) and `extension/src/lib/taint.ts` (HAR
importer), one specification (docs/CAPTURE.md section 5), 14 shared vectors in
`extension/test/vectors/taint.json` run by both. Finalize (`CaptureStore.kt`, `Finalizer`):
scrub every file, verification scan, session.json, checksums, atomic `FINALIZED`; any failure
deletes the session.

Transport (`src/lib/captureTransport.ts`, `engine/…/capture/CaptureRecorder.kt`).
`capture.write` bridge requests answered after the write, one batch in flight, sequence numbers
with duplicate detection, retry with backoff, backpressure by suspending recorded streams above
16 MiB queued. The session is deleted if the extension restarts or cannot answer
`capture.stop`. Unfinalized sessions are deleted at app start (`TwinBookApp`, main process
only; GeckoView's other processes skip it).

Capture format: `docs/CAPTURE.md`.

Capture browser (`app/…/CaptureBrowser.kt`, `CaptureBrowserScreen.kt`, `Prompts.kt`,
`MainActivity.kt`, `AppEngine.kt`). Start screen with "Capture browser" and "Engine lab".
Mobile/Desktop switch with one kept-alive session each, Back, Reload, read-only URL, Start,
Stop and finalize, Discard, live counters, last-session state. First load after
`Engine.awaitReady()`. Navigation safety in `EngineSession` (all sessions): allowed schemes
only, new windows in place, all permissions denied. Alert, confirm, prompt and single-choice
selects as a panel over the page; login, card, address, file, date, colour and auth prompts
dismissed; `loginAutofillEnabled(false)`, GeckoView autofill off.

Build types: `daily` (`.daily`, debuggable, label "twinBook daily") next to `debug`;
`testBuildType = "debug"`; `uninstallDaily` and `uninstallAll` fail on purpose.

Scripts: `tools/daily-install.sh`, `daily-launch.sh`, `capture-pull.sh`, `capture-summary.mjs`,
`har-import.sh`, `app-instrument.sh`, `capture-kill-test.sh`, `daily-survival-test.sh`.

Mock additions (`mockserver/…/CapturePages.kt`, `MockServer.kt`, `Http.kt`): response status,
hash and length per request; `/log`; the secrets page with `MockSecrets(run)`; the Bloks-shaped
`/async/wbloks/fetch/`; `login.html`; `nav.html`; `/bulk` and `bulk.html`.

HAR importer: `extension/tools/harImport.ts`, `harImportCli.ts`, `build-tools.mjs`,
`tools/har-import.sh`.

Tests added: Vitest `redact`, `taint`, `captureTransport`, `probe`, `observeOnly` (with
`fakeBrowser.ts`), `harImport`, observe-mode cases in both filter suites, the C2 cases (116
total, was 60). JVM: `CaptureStoreTest`, `TaintScrubberTest`, `CapturePagesTest` (29 total, was
19). Instrumented: `ObserveModeTest`, `CaptureRecorderTest`, `CaptureBrowserScreenTest`,
`CaptureProbe` (manual) (37 total, was 28).

Commits (oldest first): `dd54480` line-loss fix; `ebaef83` extension profiles, observe,
recorder, layer 1; `a65518a` capture store, finalize, engine recorder, navigation safety, mock
pages; `e12ac57` app, daily build, scripts, tests; `2d07c0b` dialog and URL-state fixes;
`d58d633` pull script stdin fix; `e7c1112` survival-test fix; `aee0aff` HAR importer and docs;
`44fc55f` `__a` redaction and C14 screenshots; `98faf17` ENGINE.md; `a0820c1` doc wording;
`186cad7` secrets counter fix; `27b72d3` C10 focus wait; then this report.

C1 and C21 output. Fresh clone at `27b72d3`, `env -i HOME=$HOME bash tools/check.sh`, tail:

```
$ git log --oneline -1
27b72d3 C10 test: wait for the field's focus before typing
$ env -i HOME=$HOME bash tools/check.sh
> npm run typecheck && npm run test && npm run build && npm run lint:ext
 Test Files  13 passed (13)
      Tests  116 passed (116)
lint: errors 0, warnings 1 (allowed 1), notices 0
BUILD SUCCESSFUL in 55s
== check.sh: all checks passed
JVM: capture.CaptureStoreTest 3, capture.TaintScrubberTest 3, data.DataStatusTest 1,
     mockserver.CapturePagesTest 4, mockserver.MockServerTest 8, mockserver.ScenariosTest 10
     (29 tests, 0 failures, 0 errors)
```

```
State at 27b72d3, before the report commit:
$ git status --short --branch
## m2a-capture-tooling
?? docs/reports/M2a.md          (this report, committed next)
$ git log --oneline --decorate -12
27b72d3 (HEAD -> m2a-capture-tooling) C10 test: wait for the field's focus before typing
186cad7 capture.stop: count the secrets before clearing them
a0820c1 docs: clarify body offsets; summary prints (none) for empty tables
98faf17 docs: ENGINE.md for profiles, modes, capture, navigation safety
44fc55f redact __a; logged-out real-site screenshots
aee0aff HAR importer; CAPTURE.md, capture checklist, SETUP.md login protection
e7c1112 daily-survival-test.sh: open the daily app once before writing the marker
d58d633 capture-pull.sh: keep adb off the loop's stdin
2d07c0b fix capture browser dialogs and URL state; tests pass on the emulator
e12ac57 app: start screen, capture browser, daily build; device scripts for capture
a65518a capture store, finalize pass, engine capture recorder, navigation safety, mock pages
ebaef83 extension: site profiles, observe mode, capture recorder with layer 1 redaction
$ git rev-parse main origin/main
6cc2905fbc42fbe1effccd43bb4d498ab87fbc00
6cc2905fbc42fbe1effccd43bb4d498ab87fbc00
$ git ls-files | grep -E "\.har$|^captures/|events\.ndjson|\.res$|FINALIZED" | wc -l
0
$ strings unique to the real capture (6 opaque values), searched in every commit main..HEAD
matches: 0
$ git grep for cookie/token shapes outside synthetic test values
0
$ git log --oneline -S <20-char fragment of the real __a value> main..HEAD | wc -l
0
```

## 5. Redaction design and limits

Layer 1 runs in the extension before a record is queued: cookie values (request and
response), credential headers, URL query and fragment values of secret keys (every recorded
URL, and Referer, Location, Origin, Content-Location), form fields of secret keys, and keyed
values in text bodies (JSON at any escaping level, JavaScript literals, `key=value`,
`<input name value>`). Placeholders keep the length. Every replaced value is remembered in
extension memory with a label, plus its URL-decoded and JSON-unescaped forms.

Layer 2 runs at Stop: the extension hands the app its remembered values in the `capture.stop`
answer, after every record has been acknowledged; the app scrubs every file and runs a
verification scan. Values of at least 8 characters, not in the stop list, not one repeated
character, are searched in seven encodings (raw, `encodeURIComponent`, form encoding,
JSON-escaped, JSON-escaped with `\/`, JSON-escaped twice, HTML-escaped) and replaced, longest
match first, by `!T:<label>!`.

Rule for short values: values under 8 characters are never searched for across the session,
because short strings (4-digit `jazoest`, `en_US`, `1`) match unrelated data. They are
redacted only where layer 1 sees them under their key, cookie name or header.

Ways a secret could still reach a pulled session:

1. A value that layer 1 never sees under a known key, cookie or header in this session. It is
   neither redacted where it appears nor remembered for layer 2. Example from M2a: the
   logged-out `__a` beacon value (now a key). Logged in, unknown credential-bearing headers
   or keys would behave the same.
2. Encodings outside the seven: base64-wrapped values, double URL-encoding, lowercase percent
   escapes where the remembered form used uppercase, `\uXXXX` escapes of ASCII characters in
   JavaScript strings, HTML numeric entities, a value split across string concatenation.
3. A secret cut at the 8 MiB body cap or the 256 KiB request-body cap: the partial occurrence at
   the end no longer matches.
4. Short values (under 8 characters) outside their key context.
5. Response bodies are hashed before redaction (`sha256`). For a high-entropy token this
   reveals nothing; for a tiny body whose only unknown part is a short value it could be
   brute-forced.
6. Third-party request metadata is recorded with only layer 1 applied to its URLs and
   cookies: identifiers in third-party URLs (ad and analytics IDs) are not credentials and are
   kept.
7. A password or code typed while a capture runs. Login fields (`pass`, `encpass`, `password`,
   `email`, `contact_point`) are redacted under their keys, including inside JSON parameters,
   but a field with another name would not be. The checklist forbids typing credentials during
   a capture and says to Discard first.
8. Not data in the session, but in memory: during finalize the app holds the values; a heap
   dump of the debug or daily process at that moment would contain them. Nothing logs them.
9. WebSocket frames are not recorded at all (only the handshake's metadata), so nothing leaks
   through them, but nothing is learned from them either.

Known over-redaction: CSP nonces (`nonce` key) were replaced 92 times in the logged-out
documents; any non-credential cookie value of 8 characters or more is scrubbed wherever it
appears.

## 6. Measurements

On the emulator (x86_64, 3 GB guest, software GPU), mock server in the test process.

| Measurement | Capture off | Capture on |
|---|---|---|
| 5.5 MB response, fetch to arrayBuffer, three runs each (three test runs) | 121, 137, 149 / 117, 164, 118 / 107, 99, 76 ms | 153, 154, 129 / 101, 177, 163 / 113, 773, 144 ms |
| 200 responses (199 × 4 KB, 1 × 5.5 MB, 6 in parallel), page time | 594 to 1400 ms (8 runs) | 3269, 4231, 5324, 4777 ms |
| PSS, all processes of the engine test app, around the 5.5 MB runs | 626, 630, 645 MiB | 726, 777, 789 MiB |
| PSS around the 200-response load (on: right after the load, before stop) | 547, 560, 589, 632 MiB | 657, 661, 680, 749 MiB |
| Finalize | | 9.5 MB session: 513 to 687 ms; 75 KB session: 87 to 97 ms; real logged-out 1.4 MB session: under 3 s from the tap to FINALIZED |
| Transport, 200-response load | | 1442 items in 199 to 204 batches, 0 retries, 0 backpressure events, at most 7.4 MB queued |

The single large response costs little. Many small responses cost a lot: 3 to 5 times the
page time, about 15 to 20 ms of extra wall time per request at 6 in parallel (the blocking `onHeadersReceived` round trip
to the extension, seven metadata events, the body copy, hashing and base64). Memory rises by
about 100 to 150 MiB while capturing.

Size on disk of the logged-out capture `20261002-110721-site`: 1,408,784 bytes in 16 files
(`events.ndjson` about 780 KB for 429 records, 14 bodies 617 KB, the rest metadata). The
daily dry-run session: 10,283 bytes. APKs: debug 199,073,140 bytes, daily 198,495,476 bytes.

## 7. Deviations

1. Layer 2 has two implementations, Kotlin for finalize and TypeScript for the HAR importer,
   with one specification and shared vectors (allowed by the prompt). Layer 1 has one, in
   TypeScript, used by the extension and the importer. Reason: finalize must not depend on the
   extension process, and the importer runs on the host.
2. New pure-JVM Gradle module `:capture` (store, taint, finalize), so all of it runs in
   `tools/check.sh`.
3. The real-site profile has no listener at all outside a capture: observe mode runs on the
   site only while a capture of it runs. Simpler to prove "observe only" when nothing runs.
4. Host permission `*://*/*` added so third-party request metadata of the site's pages can be
   recorded; only non-blocking listeners use it.
5. Secret keys beyond the prompt's start list: generic tokens, login fields (`pass`, `encpass`,
   `password`, `email`, `contact_point`), `nonce`, `machine_id`, and `__a` after seeing the
   logged-out beacon value. `__a` is usually `1`; it now records as `*`.
6. Page dialogs are a panel over the page instead of an Android dialog window, and
   single-choice `<select>` prompts are supported too.
7. C10's "on-screen keyboard" is exercised through `InputConnection.commitText` (what an input
   method does) and the "host keyboard" through key events from a keyboard device
   (`input keyboard text`). Nobody typed on the physical host keyboard in the emulator window.
8. C14 and C15 used the debug build. The daily build loaded the site once, in the checklist
   dry run, and holds one nearly empty finalized session from it (`20261002-111209-site`, also
   pulled) plus logged-out cookies of the site.
9. Load 1 was outside the capture because the capture browser loads the site when it opens,
   before Start capture can be pressed (the owner's flow: log in first, then capture).
10. Recorded body hashes are of the bytes as they arrived, before redaction (needed for C4/C8
    and to verify against servers).
11. The M1 `recorder.request` bridge event still exists, restricted to the mock profile, with
    unredacted mock values (M1 tests use it).
12. `PageState.url` now follows location changes only; `loadingUrl` holds the last load start.
13. Node API types come from a small local declaration file (`extension/types/node-shim.d.ts`)
    instead of adding `@types/node`. No dependency was added.
14. The commit that added `__a` first contained a 20-character fragment of the real beacon
    value in a unit test; I replaced it with a synthetic value and folded the fix into that
    commit (unpushed branch). No commit on the branch contains it now.

## 8. Problems and risks

- **Capture slows browsing.** About 15 to 20 ms per request on the emulator with the mock;
  the real site makes many requests. Pages will feel slower while capturing. If it becomes
  unusable, Discard and report.
- **Lost keystrokes right after a tap.** In one full run, key events injected immediately after
  tapping a field lost their first three characters while focus moved; with a wait for focus,
  3 of 3 runs passed. A human pausing after the tap should not hit it; worth a look in the
  owner's session if the login field misses characters.
- **Secrets in unknown places** (section 5, items 1 and 2). Logged-in traffic will have fields
  M2a never saw. The residual scan in `capture-summary.mjs` only knows the listed keys.
- **Extension restart during a capture** deletes the session (by design); the owner then
  repeats steps 5 to 8.
- **Memory**: +100 to 150 MiB PSS while capturing, on a host that peaks near its limit with
  Gradle running. The owner's session runs without Gradle.
- **Signing key**: updating the daily app depends on `~/.android/debug.keystore` staying the
  same.
- **KVM access** is still the temporary `chmod 666`; after a WSL restart the owner needs the
  command in the checklist.
- **The site may treat the emulator or GeckoView's UA as suspicious** and ask for a security
  check at login. The checklist covers it; repeated checkpoints would block M2b.
- **Mobile document probe sees nothing**: Bloks pages keep their data in inline scripts, not
  JSON islands; the probe covers islands, NDJSON and guarded JSON only.
- **Finalize reads `events.ndjson` byte by byte**; fine for megabytes, slow (tens of seconds)
  for hundreds of megabytes.
- **Third-party identifiers** (ad and analytics IDs in third-party URLs) are kept in the
  records.

## 9. Questions for the planner

1. `__a`: keep it redacted (logged-out mobile beacon carried an opaque ~100-character value),
   or treat it as shape-only as the prompt's examples suggest?
2. Should third-party URLs in the records have identifier-looking query values redacted too
   (they are not credentials), or kept for M2b's tracker analysis?
3. Is the capture overhead (section 6) acceptable for the owner step, or should the next step
   drop the per-request `security` lines and some metadata events to lighten it?

## 10. Suggestions for the owner's session and for M2b

For the owner's session:

- Watch the `errors` counter and note its final value; note logouts and security checks.
- Count sponsored posts on each site; a fresh account may see few. Scroll slowly.
- If the login form misses characters, click into the field, wait a second, then type.
- Report the `FINALIZED` line and the pull lines only.

For M2b, first:

1. Whether a service worker answers anything when logged in: `tabId == -1`, `fromCache`, and
   `originUrl` of own-host requests, and whether those responses have `observe` lines.
2. `www.facebook.com` XHR bodies: is `/api/graphql/` used, its content type, guard, line
   sizes (from `chunks`), incremental payloads; the probe's candidate counts per query.
3. The desktop document's 49+ JSON islands (sizes, which carry feed data) and the mobile
   document's inline Bloks data (the probe does not see it).
4. `/async/wbloks/fetch/` responses on the mobile site when logged in.
5. Every key and header that carries opaque values (the `__a` lesson): list them and decide
   which are credentials before any fixture is cut; check `!T:` label counts for surprises.
6. Login cookies' `Max-Age`/`Expires` (Set-Cookie attributes are kept) for M3's
   "login survives restart".
