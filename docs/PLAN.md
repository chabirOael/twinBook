# twinBook master plan

Status: M2b gate passed with reservations, 2026-10-03. Fix-up M2c issued. Next: M3.

This file is the single source of truth for the project. The planner (Claude, in the
planning conversation) owns it and updates it after every milestone report. Build
agents read it but do not edit it.

## 1. Product goal

twinBook is an Android app for using Facebook without ads and trackers, with a
native-feeling UI.

- Blocks Facebook feed ads and trackers below the page's JavaScript layer.
- Renders the main surfaces (feed, video, notifications, profile) as native Android UI
  built from Facebook's own data, instead of showing the mobile website.
- Smooth navigation and endless video scrolling.
- Does not reload Facebook's JavaScript bundles on every navigation.
- Everything not rebuilt natively stays reachable through an embedded web view of the
  mobile site, cleaned of ads.

## 2. Working assumptions (owner can overrule; affected milestones get reshaped)

| # | Assumption | Why |
|---|---|---|
| A1 | Engine is GeckoView (Firefox's engine), used directly. Mozilla Android Components modules are pulled in only where they save real work. | Only Android engine with response-stream rewriting from a WebExtension. WebView and Brave cannot do it. |
| A2 | Native screens are fed by Comet GraphQL (www.facebook.com, desktop user agent, hidden session). | Domain-level JSON. The mobile site is a Bloks presentation tree with no post semantics. |
| A3 | Long-tail screens use the mobile site (m.facebook.com) in a visible web session. Revised after M2b: logged in, that site is a "web lite" client fed through a WebSocket, so its ads can only be hidden cosmetically, by uBlock Origin's mobile filters. | Covers everything with no per-feature work. |
| A4 | License GPLv3, uBlock Origin bundled as a built-in extension. | Maintained filter lists for free. |
| A5 | v1 native scope: feed, video and Reels, notifications, profile and page view, search. Messages, marketplace, groups admin, settings, composer are web fallback. | Daily paths first. |
| A6 | Distribution through GitHub releases and F-Droid, not Google Play. | Meta terms and Play policy. |
| A7 | Development uses a secondary Facebook account. Credentials are typed only by the owner into Facebook's own login page. | Account and credential safety. |

## 3. Facts verified on 2026-10-01

- m.facebook.com serves a Bloks server-driven UI (StartFBWebBloks, bloks_payload,
  /async/wbloks/...). www.facebook.com with a desktop user agent serves Comet
  (React, Relay, GraphQL at /api/graphql/).
- Facebook's Comet prelude contains "Ghost Owl", an anti-tampering system that detects
  hooked XMLHttpRequest, fetch and JSON.parse inside the page and routes around them.
  Filtering must not run in the page's JavaScript realm.
- GeckoView 157.0 and Android Components 158.0b2 are current on maven.mozilla.org.
  GeckoView supports built-in WebExtensions, blocking webRequest, and native messaging.
- Facebook async requests carry rotating tokens (fb_dtsg, jazoest, lsd, __rev, __req,
  hsi, __s). A replaying client must read them from live traffic.
- Proven in M1 on GeckoView 157 against a local mock (docs/reports/M1.md): response
  streams are filtered from the extension background for XHR, fetch and documents,
  line by line, without delaying earlier lines; the page's native functions stay
  untouched (95 in-page integrity checks); sessions with different user agents share
  one cookie jar; headless sessions run at full speed; cookies with an expiry and
  extension storage survive a process kill; a rebuilt extension takes effect on app
  update with data kept.
- GeckoView start-up facts from M1: an already-installed extension starts its
  background script only when the first session opens; Gecko can lose an add-on's
  start-up state if the process dies right after install; `ensureBuiltIn` reinstalls
  whenever the version string differs.
- Measured in M1 on the emulator: debug APK 198 MB with two ABIs, about 80 to 87 MB
  per ABI compressed; memory 451 MiB PSS with no page and 558 MiB with one page;
  extension ready about 3.9 s and first page about 5.2 s after process start.
- Seen in M2a on the real site, logged out, from GeckoView 157 (docs/reports/M2a.md):
  both login pages render with GeckoView's own user agents, with no unsupported-browser
  page and no redirect into the native app. Every response from the site's own hosts
  came over HTTP/3 with zstd encoding and reached the stream filter already decoded.
  The mobile document carries its Bloks data in inline scripts, not in JSON script
  elements; the desktop document has 49 JSON script elements. The mobile site posts
  telemetry to `/a/bz`, the desktop site to `/ajax/bz`. On the mobile site the `__a`
  field carries a long opaque value. No service-worker fetch was seen while logged out.
  The logged-out mobile page loads Google advertising pixels through `fbsbx.com`.
- Seen in the owner's first logged-in capture, 2026-10-02, from metadata only: the
  desktop site made 31 GraphQL requests in 3 minutes, including four feed pagination
  responses of 0.6 to 2.1 MB delivered in 6 to 19 chunks, with content type
  `text/html`. All 85 own-host responses passed through the observing filter
  unchanged. The logged-in mobile site made no XHR or fetch at all while the owner
  scrolled, opened comments, watched videos and opened a profile. Its only own-host
  request was a beacon to `/ajax/weblite_load_logging/`. Its data therefore travels
  over a connection opened at page load, most likely a WebSocket, which the stream
  filter cannot see. Consequence under review: ads on the mobile site cannot be
  removed at the data layer; the web fallback would rely on cosmetic filtering.
- Confirmed by a second mobile capture with the page load, 2026-10-02: the logged-in
  mobile site opens a WebSocket to `kaios-d.facebook.com` and loads a service worker
  script. It is a "web lite" client. Its feed data never appears as HTTP responses.
- Emulator limit found 2026-10-02: reloading the desktop site under capture, with the
  mobile session also alive, froze the whole emulated system (3 GB guest memory,
  software graphics; Android's watchdog fired). The logged-in desktop document is
  about 3 MB. The app's own main thread was waiting on the render thread, so this was
  resource exhaustion, not an application deadlock. Heavy desktop pages must not be
  loaded on the emulator under capture. The owner offered a real phone for recording.
- Established by M2b from the owner's recordings (docs/findings/payloads.md): the feed
  arrives as newline-delimited documents, five posts per page, one streamed document
  per post, paged by cursor. Sponsored posts carry four independent signal families
  and no other post carries any; field names are obfuscated on request by flags in the
  query variables, so rules also test type names. The first feed page sits inside the
  4 MB desktop document. Every recorded video has progressive HD and SD sources and a
  DASH manifest, mostly AV1. Profile and search data arrive through route-definition
  responses with a guard on every line. A replay needs the client's own headers, the
  tokens, page fields, and probably module bitmaps that only the site's JavaScript
  computes. Document ids of follow-up queries are in the script bundles only. The
  anti-forgery token lives 24 hours and has a refresh endpoint.
- Not yet verified: whether a replayed request is accepted; whether `/api/graphql/` responses and
  service-worker traffic pass through the stream filter when logged in; cross-site
  replay against the real site. The owner's capture and M2b close the first two, M5
  the last.

## 4. Architecture in one page

```
+--------------------------- Android app (Kotlin, Compose) ---------------------------+
|  app/      native shell, feed, video, notifications, profile, settings              |
|  data/     domain model, GraphQL normalizer, ad classifier, Room cache, client API  |
|  engine/   GeckoView runtime, sessions, extension bridge (native messaging port)    |
+-------------------------------------------------------------------------------------+
        |  one GeckoRuntime, one cookie jar, two sessions
        |-- data twin session   hidden, desktop UA, www.facebook.com (Comet)
        |                       loaded to harvest tokens and query templates, then unloaded
        |-- web fallback session visible, mobile UA, m.facebook.com (Bloks)
        |
+--------------------------- built-in WebExtensions ----------------------------------+
|  uBlock Origin        URL blocking and cosmetic filtering                           |
|  twin-bridge (ours)   stream filters for GraphQL and Bloks responses, request       |
|                       recorder, query replay, native messaging to Kotlin            |
+-------------------------------------------------------------------------------------+
```

Repo layout target:

```
app/  engine/  data/          Android modules
extension/                    TypeScript WebExtension (twin-bridge)
rules/                        Facebook-specific JSON rules, remotely updatable
fixtures/                     sanitized recorded payloads used by tests
tools/                        environment, emulator, capture, sanitize scripts
docs/                         PLAN.md, SETUP.md, findings, prompts/M<N>.md, reports/M<N>.md
```

## 5. How the work is run

1. The owner asks the planner for the prompt of milestone N.
2. The planner writes a self-contained prompt: context, starting state, tasks,
   constraints, acceptance checks, and the report template.
3. The owner pastes it to a build agent (Claude Opus) working in this repo.
4. The agent does the work and writes its report to docs/reports/M<N>.md, and prints it.
5. The owner pastes the report to the planner.
6. The planner checks the report against the exit evidence, updates this file
   (status, decisions, deviations), and adjusts later milestones before the next prompt.

Standing rules for every build agent:

- Create branch `m<N>-<slug>` from main. The planner's documents (plan update and
  milestone prompt) are committed on main and pushed before a milestone starts, so the
  branch starts from a clean tree equal to `origin/main`. Commit locally in small
  commits. Never push, never merge, leave main untouched. After the planner accepts
  the report, the owner pushes the branch and merges it through a GitHub pull request.
- Do not edit docs/PLAN.md. Propose changes in the report.
- Never ask for, store or log Facebook credentials, cookies or tokens. Raw captures
  stay outside git. Only sanitized fixtures are committed.
- Never talk to Facebook beyond what the milestone prompt allows.
- Acceptance checks must be backed by evidence: test output, screenshots, measurements.
  A check that could not be run is reported as not run, with the reason.
- If a gate fails or the task is blocked, stop and report. Do not improvise a new
  architecture.

Standard report sections: outcome summary, acceptance checks with evidence, what was
built (files, commands), measurements, deviations from the prompt, open issues and
risks, questions for the planner, suggested changes to later milestones.

## 6. Milestones

Size is relative agent effort: S, M, L. A gate milestone can change the plan.

### M0. Toolchain and project skeleton (S)
- Goal: a building, testable, launchable empty app on this machine.
- Scope: JDK 17 or newer, Android SDK command-line tools, emulator image, all in the
  home directory without sudo. Gradle multi-module project (app, engine, data) with
  Compose. extension/ TypeScript workspace with a bundler and a test runner. One
  command to run all checks. Scripts to start a headless emulator, install, launch
  and take a screenshot. docs/SETUP.md.
- Owner input before start: raise WSL memory to 12 GB or more; make sure the user can
  open /dev/kvm.
- Exit evidence: debug APK builds; sample unit tests pass on JVM and in the extension
  workspace; emulator boots; app launches; screenshot.

### M1. Engine gate: GeckoView, built-in extension, bridge, stream filter (L, GATE)
- Goal: prove every engine capability the design depends on, without touching Facebook.
- Scope: GeckoView in `:engine` with a small coroutine API. twin-bridge installed as a
  built-in extension. Bridge with request and response correlation in both directions.
  Hermetic mock server inside the instrumented tests imitating Facebook's traffic
  shape. Streaming filter core (newline-delimited JSON, guard prefix, arbitrary chunk
  boundaries, prompt output, fail open) applied to XHR, fetch and main-document
  responses. Request recorder. In-page integrity checks proving natives are untouched.
  Two sessions with different user agents sharing cookies, and a headless session.
  Persistence of cookies and extension storage across process kill. Extension update
  with app data kept. Engine lab screen in the app.
- Probes that inform M5 and do not fail M1: replay from the extension background
  script, and replay from a same-origin anchor page with no site JavaScript, each with
  the exact cookies and headers the server receives.
- Measurements: APK size per ABI, memory with zero, one and two sessions, start-up
  times, filter cost on a 5 MB response.
- Exit evidence: checks G1 to G23 in docs/prompts/M1.md; docs/ENGINE.md.
- If the gate fails: stop. Fallback options are an in-app local proxy or reducing the
  product to cosmetic filtering. The planner decides.

### M2a. Capture tooling and real-site wiring (L)
- Goal: an app build in which the owner can log in and browse the real site while
  twin-bridge records what it sees, changing nothing.
- Scope: site profiles for the mock and the real hosts. Observe-only filter mode: the
  rule runs and reports, every byte is forwarded unchanged. Recorder for request
  metadata, form bodies and textual response bodies, sent losslessly over the bridge
  to app-private storage. Two-layer redaction: cookie and token values replaced at the
  source, then a taint pass at finalize that scrubs every remembered secret from the
  whole session; unfinalized sessions are deleted. Capture browser screen with a
  mobile-site and a desktop-site session. A login-safe `daily` build that tests never
  touch. Pull script. Owner checklist. HAR importer. Fix for the M1 line-loss defect.
- Allowed traffic: at most 12 logged-out page loads of the real site to prove the
  wiring. No login, no form submission, no other tool contacting the site.
- Exit evidence: checks C1 to C21 in docs/prompts/M2a.md; docs/CAPTURE.md and
  docs/CAPTURE-CHECKLIST.md.

### Owner step between M2a and M2b
- Follow docs/CAPTURE-CHECKLIST.md: start the emulator in a visible window, log in
  with the test account in the `daily` build, and record two captures, one on the
  mobile site and one on the desktop site, reloading the page after each start: scroll the feed past several sponsored posts, open comments, watch videos,
  open notifications, open a profile. About ten minutes. The owner does the browsing,
  so the logged-in account is never driven by an agent.
- Optional: HAR files from an older account, since a fresh account may see few ads.

### M2b. Payload findings and fixtures (M, GATE)
- Goal: know exactly what the site sends when logged in, and freeze it as fixtures.
- Before any body is read: re-apply layer 1 redaction offline to the pulled sessions
  with an extended key list (camel-case token names and anything found by a scan for
  long opaque values that recur across requests), and list every key and header that
  carries such values.
- Scope: build the sanitizer and pseudonymizer with the real shapes in hand, and
  commit fixtures. Third-party request records never enter fixtures. Findings document
  docs/findings/payloads.md: query friendly names and variable shapes, pagination
  cursors, ad and suggestion markers with counts, video URL fields, token fields, Bloks
  fetch endpoints and the sponsored subtree signature, proposed rules v1. Engine facts
  on real traffic: whether responses pass through a service worker, whether the stream
  filter saw them, encodings, line sizes, numbers that do not survive a JSON round
  trip, which login cookies have an expiry, request headers of the real client compared
  with what replay path B sends, candidate anchor URLs.
- No live replay and no request the site's own client did not make. Analysis only.
- Exit evidence: sanitized fixtures committed; findings document; no secrets in git.
- If the gate fails (Comet data not obtainable or not usable): native screens are
  dropped or re-based on Bloks; the product falls back to the clean web twin.

### M2c. Fix-up after the M2b review (S)
- Goal: a green instrumented suite and valid JSON after finalize.
- Scope: adapt the capture-browser typing test to the reload that now follows the
  start of a capture, run the whole instrumented suite and the device scripts, make a
  tainted bare number keep the JSON valid at finalize, and let the leak test run from
  the re-scrubbed copies alone so the original recordings can be deleted.
- Exit evidence: checks X1 to X6 in docs/prompts/M2c.md.

### M3. Web shell: first usable app (L)
- Goal: a daily-usable app with native chrome around the mobile site, with ads hidden
  as far as cosmetic filtering allows.
- Scope: Compose shell with bottom tabs and top bar hosting the mobile-site session.
  Login flow and session persistence. Back handling and deep navigation. Injected CSS
  to hide the site's own header and footer. uBlock Origin bundled as a second built-in
  extension with its mobile filter list, plus URL rules for the site's own logging
  beacons. External links in a separate clean session, redirect unwrapping,
  tracking-parameter stripping. File upload, downloads, camera and microphone
  permission prompts. Pull to refresh. Dark mode. Session kept alive across tab
  switches.
- Ads on this surface are hidden, not removed: the logged-in mobile site gets its data
  through a WebSocket that no extension can filter. The owner checks on the phone how
  many sponsored posts still show.
- From the M1 review: the first site load waits for the engine to report ready.
  Measure installing the extension on every start against the current start-up
  contract and keep the faster reliable one.
- From the M2a review: make text input robust. A burst of key events sent shortly after
  a field gains focus lost its first characters under load. Find the cause in
  GeckoView's input handling or the test, and fix whichever it is.
- Exit evidence: scripted on-device walkthrough on the mock with screenshots; login
  survives app restart; instrumented tests for navigation and link handling; the
  owner's count of visible sponsored posts on the phone.

### M4. Folded into M3 and M5
- The Bloks payload filter is dropped: there is no HTTP payload to filter on the
  logged-in mobile site. Cosmetic filtering and beacon blocking moved to M3.
- GraphQL ad rules moved to M5: rules v1 from M2b run on replayed responses before
  they reach Kotlin. Remaining rule work there: per-line guards of
  `/ajax/route-definition/`, the right column, in-stream video ad breaks, and toggles
  for suggested content.

### M5a. Data twin tooling (L)
- Goal: everything needed to harvest and replay, proven on the mock, plus a lab screen
  the owner can drive.
- Scope: harvest session, a hidden desktop session that loads the home page once and is
  then closed. While it loads, the extension passively collects what a replay needs:
  tokens, page fields, the module bitmaps the site's code computes, the revision, and
  the document ids of operations, read from the script bundles as they pass. Template
  store keyed by revision. Anchor page and replay client that add the client's own
  headers. Token refresh through the site's own refresh endpoint. Rules v1 applied to
  replayed responses. Rate limiting and backoff. Kotlin client API for feed page,
  comments, notifications, profile timeline, video. A replay lab screen in the `daily`
  build with explicit buttons, recording what it does in the capture format.
- No agent sends a replayed request to the real site. The mock gains a page that
  behaves like the desktop site as far as harvesting needs.
- Exit evidence: instrumented tests on the mock for harvest, template store, replay,
  refresh and rules; the lab checklist for the owner.

### Owner step between M5a and M5b
- On the phone: run the harvest, then a handful of replays from the lab screen, a
  few pages of feed, one comments page, notifications. Pull the session. Report whether
  the account showed any security prompt afterwards.

### M5b. Replay findings (M, GATE)
- Goal: decide whether the data twin works on the real site.
- Scope: compare replayed responses with the site's own; settle whether the module
  bitmaps are required and how stale they may be; confirm that ad rules hold on
  replayed data; fix what the owner's run exposed; memory and time of a harvest.
- If the gate fails: keep a desktop page alive on capable devices and read its own
  traffic, or reduce the product to the web shell of M3. The planner decides.

### M6. Domain model, normalizer, cache (M)
- Goal: turn raw GraphQL JSON into stable Kotlin models.
- Scope: models for story, actor, text with entities, attachment kinds (photo, album,
  link, video, shared story), feedback, comment, notification, profile header.
  Tolerant normalizer that marks unknown shapes as "show in web" instead of failing.
  Ad classifier shared with the M4 rules. Room cache with feed snapshot. Coverage
  report over the fixtures.
- Can run in parallel with M5. Needs only M2 fixtures.
- Exit evidence: JVM tests over all fixtures; coverage table by story type.

### M7. Native feed, read-only (L)
- Goal: the Home tab is native and feels like an app.
- Scope: Compose feed cards (header, expandable text, photo grid, link preview, shared
  post, counts). Endless pagination through the M5 client. Pull to refresh. Instant
  cold start from the snapshot. Image loading with disk cache and prefetch. Full-screen
  image viewer. Per-post fallback into the web session. Screenshot tests.
- Owner input before start: a real phone reachable over wireless debugging. The
  emulator renders with a software GPU, so its frame times are not evidence.
- Exit evidence: screenshot tests from fixtures; scroll frame-time numbers from the
  real phone; cold-start time to first rendered feed.

### M8. Feed interactions (M)
- Goal: do the everyday things without leaving native UI.
- Scope: reactions, comments sheet with read, paginate and write, share, save, hide
  post, open author. Mutations go through the replay client. Optimistic UI with
  rollback. Composer opens web fallback in v1.
- Owner input: confirm the test account may be used for real reactions and comments.
- Exit evidence: on-device scripted run of each action, verified by re-reading the data.

### M9. Video and Reels (L)
- Goal: non-stop video scrolling.
- Scope: Media3 player pool. Muted autoplay in feed. Video tab as a vertical pager
  with endless pagination and neighbour preloading. DASH with progressive fallbacks.
  Expired-URL recovery. Audio focus, background pause, basic controls, full screen.
- Owner input: the real phone already connected for M7.
- Exit evidence: on-device run through a fixed number of consecutive videos with no
  stall; time to first frame; dropped-frame numbers on the real phone.

### M10. Notifications, profile, search (L)
- Goal: the remaining daily paths are native.
- Scope: notifications list with badge and deep links into native or web targets.
  Profile and page view with header and timeline feed. Search with results list.
  Android intent filters for facebook.com links. No background polling in v1 unless
  the owner asks for it.
- Exit evidence: screenshot tests; on-device walkthrough.

### M11. Performance and caching (M)
- Goal: fast start, low memory, no repeated bundle loads.
- Scope: warm session at launch, keep-alive policy, engine cache sizing, verification
  that script bundles come from cache on second launch, prefetch tuning, baseline
  profile, code shrinking, ABI splits, memory budget on a low-end profile.
- Exit evidence: before and after table for cold start, memory, APK size, network
  bytes on second launch.

### M12. Hardening and release (M)
- Goal: something other people can install and that survives Facebook changes.
- Scope: remote update of rules with integrity check. Schema-drift canary script.
  Settings screen. Local diagnostic export without personal data. Onboarding and
  risk notice. Release signing, reproducible build notes, GitHub release workflow,
  F-Droid metadata, README and user docs.
- Exit evidence: signed release APK installed on a clean device; canary run; docs.

## 7. Owner inputs at a glance

| When | What |
|---|---|
| Before M1 | Permanent KVM fix (`sudo usermod -aG kvm $USER`, then restart WSL). Correct the git author email in `~/.gitconfig`. |
| After M2a, before M2b | Done 2026-10-02: four logged-in sessions, three from the emulator and one from the phone |
| After M3 | Use the app on the phone and count the sponsored posts that still show |
| Between M5a and M5b | Run the harvest and a few replays from the lab screen on the phone |
| Before M8 | Permission to post real reactions and comments from the test account |
| From now on | Real phone over wireless debugging, in use since 2026-10-02 |
| Before M12 | Signing key decision, app name and icon, release channel |

## 8. Conventions and decisions fixed so far

- Build and run instructions live in docs/SETUP.md. Every script in tools/ works from a
  shell with no profile. `tools/check.sh` is the single no-device gate.
- SDK packages are installed through the Android CLI that ships with command-line
  tools 23 (`android sdk install`). It updates itself. Accepted: the installer is not
  part of the product, package versions are still named explicitly, and the setup
  script verifies each package after install.
- Memory settings: Gradle heap 2 GB, Kotlin daemon 1 GB, 4 workers, emulator guest RAM
  3 GB. If a build needs more heap, lower the worker count before shrinking the AVD.
- Built-in extensions are packaged under `assets/extensions/<name>/` in the APK.
- `web-ext lint` policy from M1 on: errors always fail. Warnings fail unless listed in
  a committed allowlist with a reason per entry.
- The planner verifies every report by re-running the checks in a fresh clone of the
  milestone branch.
- Engine rules from M1: load site pages only after `Engine.awaitReady()`. Built-in
  extension versions are stamped per build. The engine opens a bootstrap session at
  start and reinstalls the extension if its background has not started after 10 s.
  Accepted as the start-up contract until M3 measures the alternative.
- Debug builds pack native libraries compressed. Release packaging is decided in M11.
- The `diag.*` bridge methods are compiled out of release builds when the release
  build type is set up in M12.
- Native UI must never wait for the engine: cached content renders first, the engine
  comes up behind it.
- The owner's logged-in session lives in the `daily` build of the app. No agent may
  uninstall it, clear its data, or wipe or recreate the AVD. Tests use the debug build
  and the engine test app only.
- Capture conventions from M2a: format in docs/CAPTURE.md; the real-site profile is
  locked to observe or off and has no replay, no header rewriting and no content
  script; its listeners exist only while a capture runs. Redaction keys live in
  `extension/data/redaction-rules.json`. `__a` is treated as a secret key. Third-party
  identifiers stay in raw records, which never leave the machine. Capture overhead of
  about 15 to 20 ms per request is accepted for the owner's session.
- After accepting a report, the planner commits its plan update on the milestone
  branch, so the owner's pull request carries the work and the plan together.
- The GitHub repository is public. Fixtures are structure-only and are committed only
  when the leak test finds nothing. Raw recordings, findings and reports never contain
  personal data.
- Every milestone's acceptance includes the whole instrumented suite
  (`tools/connected-test.sh`) and the device scripts, not only the tests of the new
  work. M2b changed behaviour that an older device test depended on and nobody ran it.
- Decisions after M2b: fixtures stay in git as they are; string values under the keys
  `data` and `encrypted` are redacted at capture time; at the next capture the owner
  counts sponsored and suggested posts; document ids are taken from script bundles at
  run time, no further recording is needed for that.
- Known gap: the final revision of `tools/setup-toolchain.sh` has not been run against
  an empty home directory. Its JDK and command-line-tools steps were. Revisit in M12.

## 9. Status

| Milestone | Status | Report |
|---|---|---|
| M0 | accepted 2026-10-02, merged into main through pull request 1 | docs/reports/M0.md |
| M1 | gate passed, accepted 2026-10-02, merged into main through pull request 2 | docs/reports/M1.md |
| M2a | accepted 2026-10-02, merged into main through pull request 3 | docs/reports/M2a.md |
| Owner capture | first pass done 2026-10-02: sessions `20261002-172602-site` (mobile) and `20261002-172927-site` (desktop). Neither contains a page document. A short supplementary capture with a reload is requested. | none |
| M2b | gate passed with reservations, accepted 2026-10-03 subject to fix-up M2c. Branch `m2b-findings`. | docs/reports/M2b.md |
| M2c | prompt issued 2026-10-03, docs/prompts/M2c.md, same branch | pending |
| M3 to M12 | not started | none |

## 10. Change log

- 2026-10-01: outline created.
- 2026-10-01: environment checked for M0. WSL memory raised to 11 GB visible. KVM
  device present but user not yet in group kvm; owner asked to fix. Toolchain versions
  pinned in the M0 prompt: Gradle 9.8.0, AGP 9.4.1, Kotlin 2.4.20, Compose BOM
  2026.09.00, JDK 21, emulator image android-36 google_apis x86_64. Provisional
  application ID io.github.chabiroael.twinbook. M0 prompt issued.
- 2026-10-02: M0 report reviewed and accepted. Planner re-ran `tools/check.sh` from a
  fresh clone with an empty environment (pass), booted the emulator with KVM (34 s),
  ran the 3 instrumented tests (pass), launched the app and inspected the screenshot
  (all five items shown). Real-phone input moved from M9 to M7 because the emulator
  uses a software GPU. Agent questions answered in section 8.
- 2026-10-02: owner pushed `m0-skeleton` and merged it into main through GitHub pull
  request 1. Git author email corrected by the owner for future commits. M1 scope
  widened to include persistence, extension update, headless sessions, document
  filtering and replay probes; size raised to L. GeckoView pinned to
  157.0.20260924084938 (minSdk 26, per-ABI artifacts available). M1 prompt issued.
- 2026-10-02: M1 report reviewed and accepted. Planner re-ran in a fresh clone:
  `tools/check.sh` (60 extension tests, 19 JVM tests, lint clean), the 28 instrumented
  tests, the persistence script and the extension-update script, all pass; lab
  screenshot inspected. Gate verdict: pass. Decisions: start-up contract accepted,
  replay path B primary, diag methods compiled out at M12, debug packaging stays
  compressed. M2 split into M2a (tooling, no account), an owner capture step, and M2b
  (findings), because the M1 app cannot load the real site and an agent should not
  drive a logged-in account. Defect to fix in M2a: `NdjsonStreamFilter.push` can lose
  the bytes of the current line if `processLine` throws unexpectedly.
- 2026-10-02: owner merged M1 through pull request 2. M2a prompt issued. The sanitizer
  moved from M2a to M2b so it is built against real shapes. Added the login-safe
  `daily` build after noticing that instrumented test runs uninstall the debug app,
  which would have destroyed the owner's login.
- 2026-10-02: local main synced with GitHub at the owner's request. From now on the
  planner's documents are committed on main and pushed before each milestone starts.
- 2026-10-02: M2a report reviewed and accepted. Planner re-ran in a fresh clone:
  `tools/check.sh` (116 extension tests, 29 JVM tests, lint clean), the instrumented
  suite, the daily-build survival test and the interrupted-capture test; read the
  observe-only code path; scanned the pulled logged-out capture independently and found
  no unredacted cookie or keyed token. Decisions: `__a` stays redacted, third-party
  identifiers stay in raw records, capture overhead accepted. Planner patched the owner
  checklist: reload after starting a capture, two separate captures, privacy note.
- 2026-10-02: one flaky test seen during the M2a review.
  `CaptureBrowserScreenTest.textInputFromInputMethodAndKeyEvents` failed once in the
  planner's first full-suite run (the password field received `Pass1` for `Hw-Pass1`),
  then passed in a second full run, 5 of 5 alone, and 6 of 6 with human-like key
  timing. Treated as a load-dependent timing issue, assigned to M3. The checklist now
  tells the owner how to type the password safely. The daily build survived both full
  runs, the M1 device scripts and an in-place update: same install time, marker intact.
- 2026-10-02: owner merged M2a (pull request 3) and recorded two logged-in sessions.
  Planner inspected metadata only. The desktop session is rich enough for the M2b gate.
  The mobile session shows no data requests, which points to a socket-based "web lite"
  client. Neither session has a page document because no reload followed the start of
  the capture; M2b makes the capture browser reload automatically. M2b prompt issued,
  with a secret-hardening step before any body is read and a structure-only rule for
  fixtures because the repository is public.
- 2026-10-02: owner's supplementary mobile capture `20261002-181317-site` confirms the
  WebSocket data channel of the mobile site. The desktop attempt froze the emulator;
  diagnosed from Android's ANR and watchdog records as memory and graphics exhaustion
  in the guest. The owner is now in group `kvm`, so KVM access is permanent. M2b prompt
  amended: new session listed, desktop document optional and expected from a real
  phone, new lead on where document ids come from.
- 2026-10-03: M2b report reviewed. Planner re-ran `tools/check.sh` in a fresh clone
  (180 tests pass, leak test skipped there by design), ran the leak test with the raw
  recordings present (pass), and made an independent leak check with its own method:
  of 9,663 fixture strings, 926 also occur in the recordings, all of them type names,
  enum constants, header names, codec strings, resource hashes, document ids, and
  placeholders; no non-ASCII text, no URL, no name, no account identifier. Gate
  accepted as pass with reservations. One defect found: the older capture-browser
  typing test fails every time since capture start reloads the page, and the full
  instrumented suite was not run. Fix-up M2c issued on the same branch. Plan revised:
  A3 reworded, M4 folded into M3 and M5, M5 split into tooling, an owner step and a
  gate, in the same pattern as M2.
