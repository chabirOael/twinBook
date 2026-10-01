# twinBook master plan

Status: outline version 1, approved to start. M0 prompt issued 2026-10-01.

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
| A3 | Long-tail screens use the Bloks mobile site (m.facebook.com) in a visible web session. | Covers everything with no per-feature work. |
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
- Not yet verified: logged-in payload shapes, stream filtering of streamed GraphQL
  responses on GeckoView specifically, real memory numbers. Milestones M1 and M2 close
  these.

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

- Work on branch `m<N>-<slug>`. Commit locally in small commits. Never push. Never
  merge into main. The branch is merged only after the planner accepts the report
  and the owner agrees.
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

### M1. Engine gate: GeckoView, built-in extension, bridge, stream filter (M, GATE)
- Goal: prove every engine capability the design depends on, without touching Facebook.
- Scope: GeckoView runtime and a session rendering a page. twin-bridge installed as a
  built-in extension. Native messaging round trip in both directions. A local mock
  server that imitates Facebook's traffic shape: POST XHR, chunked newline-delimited
  JSON, `for (;;);` prefix. Stream filter that removes marked nodes. Request-body
  capture. A test page that asserts it received filtered data and that its native
  functions are untouched. Two sessions with different user agents sharing cookies.
- Measurements: APK size per ABI, memory with one and two sessions, cold start.
- Exit evidence: instrumented tests green on the emulator; measurement table.
- If the gate fails: stop. Fallback options are an in-app local proxy or reducing the
  product to cosmetic filtering. The planner decides.

### M2. Capture tooling and payload findings (M, GATE)
- Goal: know exactly what Facebook sends when logged in, and freeze it as fixtures.
- Scope: debug-only recorder in twin-bridge for GraphQL and Bloks request and response
  pairs. Pull script. Sanitizer that strips cookies and tokens and pseudonymizes names
  and IDs. HAR importer for desktop captures. Scripted capture sessions on the
  emulator: feed scroll, comments, video tab, notifications, a profile, in both the
  Comet and the Bloks session.
- Deliverable docs/findings/payloads.md: query friendly names and variable shapes,
  pagination cursors, ad and suggestion markers with counts, video URL fields, token
  fields, Bloks fetch endpoints and the sponsored subtree signature, proposed rules v1.
- Owner input before start: log in with the test account in the M1 build on the
  emulator. Optional but valuable: HAR files from an older account, since a fresh
  account may be shown few ads.
- Exit evidence: sanitized fixtures committed; findings document; no secrets in git.
- If the gate fails (Comet data not obtainable or not usable): native screens are
  dropped or re-based on Bloks; the product falls back to the clean web twin.

### M3. Clean web twin: first usable app (L)
- Goal: a daily-usable wrapper with native chrome around the cleaned mobile site.
- Scope: Compose shell with bottom tabs and top bar hosting the web fallback session.
  Login flow and session persistence. Back handling and deep navigation. Injected CSS
  to hide the site's own header and footer. uBlock Origin bundled. External links in a
  separate clean session, redirect unwrapping, tracking-parameter stripping. File
  upload, downloads, camera and microphone permission prompts. Pull to refresh. Dark
  mode. Session kept alive across tab switches.
- Exit evidence: scripted on-device walkthrough with screenshots; login survives app
  restart; instrumented tests for navigation and link handling.

### M4. Data-layer ad and tracker filtering (M)
- Goal: no sponsored posts, in web fallback and in captured GraphQL data.
- Scope: rule engine in twin-bridge driven by rules/*.json from the M2 findings.
  Two-signal scoring before a node is dropped. GraphQL stream filter and Bloks payload
  filter. Toggles for suggested posts, suggested reels, people you may know. Balanced
  and strict tracker modes for Facebook's own telemetry endpoints. Local counter and
  debug log of dropped and near-miss items.
- Exit evidence: fixture tests showing every known ad removed and no organic post
  removed; on-device scroll of a fixed number of screens with zero sponsored posts;
  before and after request counts.

### M5. Data twin: token harvest and query replay (L, GATE)
- Goal: fetch Facebook data on demand without a live Comet page.
- Scope: hidden desktop-UA session that loads Comet once. Template store mapping
  friendly query names to document IDs and variable shapes. Token store that follows
  rotation. Replay client in the extension background. Kotlin client API with typed
  calls for feed page, comments page, notifications, profile timeline. Rate limiting,
  backoff, handling of revision-refresh signals, re-harvest when templates go stale.
  Comet page unloaded after harvest.
- Exit evidence: on-device test that replays several consecutive feed pages and one
  comments page with valid data while no Comet page is loaded; memory before and after
  unload.
- If the gate fails: keep the Comet page alive and read its own traffic instead of
  replaying, at a memory cost. The planner decides.

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
- Exit evidence: screenshot tests from fixtures; on-device scroll with frame-time
  numbers; cold-start time to first rendered feed.

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
- Owner input before start: a real phone reachable over wireless debugging.
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
| Before M0 | Raise WSL memory, KVM access |
| Before M2 | Test account login in the M1 build; optional HAR files |
| Before M8 | Permission to post real reactions and comments from the test account |
| Before M9 | Real phone over wireless debugging |
| Before M12 | Signing key decision, app name and icon, release channel |

## 8. Status

| Milestone | Status | Report |
|---|---|---|
| M0 | prompt issued 2026-10-01, docs/prompts/M0.md | pending |
| M1 to M12 | not started | none |

## 9. Change log

- 2026-10-01: outline created.
- 2026-10-01: environment checked for M0. WSL memory raised to 11 GB visible. KVM
  device present but user not yet in group kvm; owner asked to fix. Toolchain versions
  pinned in the M0 prompt: Gradle 9.8.0, AGP 9.4.1, Kotlin 2.4.20, Compose BOM
  2026.09.00, JDK 21, emulator image android-36 google_apis x86_64. Provisional
  application ID io.github.chabiroael.twinbook. M0 prompt issued.
