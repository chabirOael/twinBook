# Milestone M3a prompt: web shell core and uBlock Origin

You are the build agent for milestone M3a of twinBook. You work alone in the repository
at `/home/wael/twinBook`. A separate planner wrote this prompt and will review your
report. The planner has access to the same machine and will re-run your checks in a
fresh clone, so every claim in your report must be reproducible from the branch you
leave behind.

Read these first, in this order: `docs/PLAN.md`, `docs/SETUP.md`, `docs/ENGINE.md`,
`docs/CAPTURE.md` (sections 1 and 5 only), `docs/findings/payloads.md` (sections 11
and 12), `docs/reports/M2b.md` (the "M2c fix-up" section). This prompt covers M3a only.

## 1. Why this milestone exists

Until now the app has been a laboratory: an engine lab and a capture browser. M3a turns
it into the first thing the owner can use every day on a phone: a well-behaved shell
around the site's mobile version, with uBlock Origin built in.

What the earlier milestones established, and what it means here:

- Logged in, the mobile site is a "web lite" client. Its page is small, and all its
  content arrives through a WebSocket. No extension can filter that data. On this
  surface ads can only be hidden after they are drawn, by cosmetic filters. uBlock
  Origin ships maintained filters for exactly this site and client, in its mobile
  rules. So in M3a ad hiding is uBlock Origin's job, not twin-bridge's.
- You cannot see the logged-in site. You have no account and must not use the owner's.
  Therefore M3a does not restyle the site, does not hide its own header or tab bar,
  and adds no native tabs that would duplicate the site's navigation. The site keeps
  its own navigation. The shell provides what a browser tab cannot: reliable back
  behaviour, link hygiene, persistence, start-up without glitches, ad hiding and
  tracker blocking. Uploads, downloads, permissions, full-screen video and settings
  polish are M3b, after the owner has tried M3a.
- The owner's logged-in session lives in the `daily` build, on the emulator and on a
  real phone. After M3a the `daily` build opens straight into the shell. From then on,
  launching the `daily` build means loading the real site with the owner's account.

## 2. Starting state, verified by the planner on 2026-10-02

- `main` contains M0 to M2b including the M2c fix-up. `tools/check.sh` passes: 194
  extension tests, 30 JVM tests. The instrumented suite has 38 tests.
- Known flaky test: `CaptureBrowserScreenTest.textInputFromInputMethodAndKeyEvents`,
  the half that sends a burst of key events to a password field. It fails on about one
  full-suite run in three, typically the first run after a fresh install. Observed
  forms: the first keys are lost, or two keys arrive swapped. Logcat showed the
  on-screen keyboard restarting input twice, 290 ms apart, when the field got focus.
  Human-speed typing, one key at a time, never failed in 6 of 6 planner runs.
- The emulator is weak: 3 GB guest memory, software graphics. Heavy desktop pages under
  capture froze it once. Keep device work on the mock light, stop Gradle daemons before
  long emulator sessions, and never load the desktop site there.
- uBlock Origin facts checked today: release 1.75.0, published 2026-09-16, asset
  `uBlock0_1.75.0.firefox.signed.xpi`, 4.65 MB, at
  `https://github.com/gorhill/uBlock/releases/download/1.75.0/`. Its Gecko id is
  `uBlock0@raymondhill.net`. Its rules for the mobile site are part of its default
  "uBlock filters" list and are included only in a mobile environment, which it detects
  from the user agent. License GPLv3, the same as this repository.

## 3. Rules

- Preflight: run `git fetch origin` and confirm that `main` equals `origin/main` and
  the working tree is clean, then `git switch -c m3a-web-shell main`. If either is not
  true, stop and report. Do not commit on `main` or any other branch. Never push, never
  merge. Do not edit `docs/PLAN.md` or anything under `docs/prompts/`.
- **The owner's session.** Never launch the `daily` app, on any device. Never
  uninstall it, clear its data, or wipe or recreate the AVD. You may update it in place
  with `tools/daily-install.sh` only where this prompt says so. All your device work
  uses the debug build, the engine test app and the mock.
- **Real-site traffic budget.** At most 12 logged-out page loads of `m.facebook.com`
  from the debug build on the emulator, to check that the shell and uBlock Origin work
  with the real login page. No form submission, no typing, no tapping on the page, no
  login, no account. No other tool may contact the site. Never load the desktop site.
  Log every load and put the log in the report.
- Allowed network besides that: Maven, npm, and the uBlock Origin release download.
  uBlock Origin itself will fetch filter-list updates from its own sources when it
  runs. That is expected.
- No `sudo`, no global installs, no edits to shell profiles or global git config.
- New dependencies: uBlock Origin as described in 5.3. Anything else must be small and
  justified in the report. Still not allowed: Mozilla Android Components, a DI
  framework, Room, Media3.
- Personal data and secrets: nothing from `captures/` may appear in git, in output you
  quote or in your report. The repository is public.
- Evidence over assertion. A check you did not run is "not run" with the reason.
- **Run everything.** Before you report, run `tools/check.sh` from a clean clone, the
  whole instrumented suite three times in a row, and every device script. Report each
  result, including failures that do not repeat. Do not add retries inside tests.
- Priority order if you run short of time or context: 5.1, 5.2 and 5.3 with their
  checks first, then 5.4, then 5.5 and 5.6. Report honestly what was not reached.

## 4. Decisions already made

1. The shell shows one visible session on the mobile site with the mobile user agent.
   The site keeps its own navigation. No restyling of the site, no injected CSS of our
   own, no native tab bar in M3a.
2. uBlock Origin is a second built-in extension. Its files are downloaded at build
   time from the pinned release with a checksum, like the toolchain. They are not
   committed to git.
3. External links leave the app: they open in the user's default browser. Links that
   stay on the site's own hosts stay in the shell.
4. twin-bridge keeps its observe-only guarantee for capture. The one new thing it may
   do on the site's hosts is cancel requests to a short, fixed list of the site's own
   logging endpoints, and only when the owner turns on "strict" mode. Off by default.
5. The debug build keeps a developer start screen, so launching it loads no site by
   itself. The `daily` build opens the shell directly and keeps the developer screens
   behind a menu entry.
6. Tests never depend on the real site. The shell's site configuration, meaning hosts
   and start URL, is injectable, and instrumented tests point it at the mock.

## 5. Work to do

### 5.1 The shell

- A screen that shows the site in a GeckoView and feels like an app, not like a
  browser: no address bar. Edge-to-edge layout with correct insets for status bar,
  navigation bar and on-screen keyboard. A loading indicator that does not flicker.
- Start-up: the first load waits for the engine and both extensions. While waiting,
  show a plain splash, not a blank screen. Follow the rule in `docs/PLAN.md`: site
  pages load only after `Engine.awaitReady()`.
- Back: the system back gesture goes back in the page's history, and leaves the app
  only when there is nothing to go back to. Predictive back must not break.
- Reload: pull to refresh only when the page is scrolled to its top and only if it
  does not fight the page's own scrolling; otherwise a menu action. Say which you
  chose and why.
- A small overflow menu: reload, go to the home page, settings, and in debug and
  `daily` builds the developer screens.
- Persistence: the last page and its history survive process death and device
  restart. Cookies already do. Add a device script that proves it on the mock.
- A crashed or killed content process shows a recovery view with a reload action.
  Prove it with the engine's crash test page on the mock.
- Theme: follow the system light or dark setting and pass it to the engine, so pages
  that support a dark scheme use it.
- Orientation and configuration changes do not reload the page.
- Settings screen, minimal: ad hiding on or off, strict mode on or off, a way to open
  uBlock Origin's own dashboard, version information. Nothing else yet.

### 5.2 Link hygiene

- Navigation stays in the shell for the site's own hosts. Decide and document the
  exact host list from the recordings' metadata in `docs/findings/payloads.md`.
- The site wraps outbound links in a redirect page on one of its hosts, with the
  target in a URL parameter, and adds click identifiers to target URLs. Unwrap the
  redirect without loading it, strip known tracking parameters from the target, and
  open the result in the default browser. Keep the parameter list in one data file
  with a reason per entry.
- Custom schemes and intents: ignored as today, except `tel:`, `mailto:` and `geo:`,
  which are handed to the system.
- New-window requests follow the same rules.
- Unit tests for the URL logic with a table of cases, including nested redirects,
  encoded targets, targets that are themselves on the site's hosts, and malformed
  input.

### 5.3 uBlock Origin

- Fetch the pinned release at build time, verify its SHA-256, unpack it into the APK
  assets next to twin-bridge, and install it as a built-in extension. Extend
  `tools/setup-toolchain.sh` or the Gradle build, whichever fits, so a fresh clone
  builds with one command and a second build downloads nothing.
- Make sure it considers itself in a mobile environment, so its rules for the mobile
  site are active. State how you verified that.
- Start-up contract with two extensions. M1 found that an already installed extension
  starts its background script only once a session opens, and that Gecko can lose an
  add-on's start-up state. The engine works around both. Now measure, on the emulator,
  time from process start to both extensions ready for: the current contract, and
  installing the built-in extensions on every start. Keep the faster one that is
  reliable over 20 cold starts each, and say what "ready" means for uBlock Origin,
  which has no bridge. Requests must not escape filtering during start-up.
- The ad-hiding switch in settings turns uBlock Origin's filtering off and on for the
  site without reinstalling it.
- The dashboard entry opens uBlock Origin's own settings page inside the app.
- Prove on the mock that it works in this app: a request that a default list blocks
  never reaches the mock server, and an element that a default cosmetic filter hides
  is not displayed. Prove that both come back when ad hiding is switched off.
- Strict mode: twin-bridge cancels requests to the site's own logging endpoints named
  in `docs/findings/payloads.md` section 12 for the mobile site. Data file with a
  reason per entry, tests on the mock, off by default, and never active while a
  capture runs.
- Record uBlock Origin's version, license and source URL in the app's version
  information and in a `THIRD-PARTY.md` at the repository root.

### 5.4 Text input

Find the root cause of the key-event flake described in section 2. Work on the mock.
Determine whether keys are lost or reordered in GeckoView's input handling, in the
on-screen keyboard's restart, in the way the test injects keys, or in the app's own
view setup. Fix it if the cause is in this repository. If it is not, say exactly where
it is and what a user would have to do to hit it. The gating test types one key at a
time and waits for each key to reach the page. Keep the burst variant as a separate
diagnostic that reports its result without failing the suite, and report its failure
rate over 20 runs.

### 5.5 Carried over

- `tools/daily-survival-test.sh` must never launch the `daily` app: after M3a a launch
  loads the real site with the owner's account. Rework it to write and read its marker
  with `run-as`, update the app in place, and compare install times and a listing of
  the app's storage. Then run it.
- Let the offline re-scrub tool accept an already re-scrubbed copy as input and rewrite
  it in place through a temporary directory, so later rule changes can still be
  applied after the originals are deleted. Do not delete anything under `captures/`.
- Readers of sessions and fixtures accept the viewer id both as the quoted placeholder
  and as `0`. Do not change the fixtures.

### 5.6 Mock, documentation, owner checklist

- Extend the mock with what the shell tests need: internal links, an outbound link
  through a redirect page with tracking parameters, a page with an element a default
  cosmetic filter hides, a request a default list blocks, a long scrolling page, a
  form, a dark-scheme page that reports which scheme it got, and endpoints shaped like
  the site's logging beacons.
- `docs/SHELL.md` for future agents: structure of the shell, site configuration, link
  rules, how uBlock Origin is fetched, installed and switched, the start-up contract.
- Update `docs/SETUP.md` and `docs/ENGINE.md`.
- `docs/SHELL-CHECKLIST.md` for the owner, who is a developer but has not read the
  code. Steps to update the `daily` build on the phone, then what to try for fifteen
  minutes: log-in still valid after the update; scroll the home feed past about fifty
  posts and count the posts marked as sponsored that are still visible; the same with
  ad hiding switched off, for comparison; open an outbound link; kill the app and
  reopen it; restart the phone and reopen it; rotate the phone; type a comment draft
  without sending it; switch between light and dark. What to report back, without
  screenshots or personal data. Dry-run every step you can on the mock and say which
  ones you could not.

## 6. Acceptance checks

| ID | Check | Evidence required |
|---|---|---|
| S1 | `env -i HOME=$HOME bash tools/check.sh` passes from a clean clone; a second run downloads nothing | tail of output, test counts |
| S2 | The whole instrumented suite passes three times in a row | three summaries with counts per class; every failure listed |
| S3 | Device scripts pass: persistence, extension update, interrupted capture, reworked daily survival, and the new shell persistence script | last lines of each |
| S4 | Shell on the mock: start-up splash then page, back, reload, home, menu, rotation without reload, dark scheme reaches the page | instrumented tests, two screenshots under `docs/reports/assets/` |
| S5 | Process death and restart restore the last page and history; a crashed content process shows the recovery view and reloads | script and test output |
| S6 | Link rules: table-driven unit tests pass; on the mock an internal link stays, a wrapped outbound link is unwrapped, stripped and handed to the system without loading the redirect page | test output; the mock server's request log showing the redirect page was not requested |
| S7 | uBlock Origin is fetched with checksum, installed as built-in, reports a mobile environment; on the mock it blocks a listed request and hides a listed element; both return when ad hiding is off | test output, server log, version and checksum |
| S8 | Start-up contract measured for both variants over 20 cold starts each; the chosen one never lets a request through before filtering is ready | table of times, the test that proves the last point |
| S9 | Strict mode cancels the listed logging endpoints on the mock, is off by default, and is inactive during a capture; the capture observe-only tests still pass | test output |
| S10 | Text input: root cause stated with evidence; gating test passes 20 of 20; burst diagnostic rate over 20 runs reported | test output, logcat excerpts |
| S11 | Real site, logged out: the shell in the debug build shows the mobile login page with uBlock Origin active; requests to third-party trackers seen in M2a are now blocked | one screenshot, request counts with and without ad hiding, the traffic log |
| S12 | `daily` build: opens the shell directly, developer screens behind the menu, verified by code and manifest inspection only; updated in place on the emulator with data kept; never launched | install times before and after, storage listing, statement |
| S13 | Re-scrub in place works on a copy of a re-scrubbed session; nothing under `captures/` deleted | tool output on a temporary copy |
| S14 | `docs/SHELL.md`, `docs/SHELL-CHECKLIST.md`, `THIRD-PARTY.md` written; `docs/SETUP.md` and `docs/ENGINE.md` updated | file list, dry-run notes |
| S15 | Branch clean, nothing pushed, `main` untouched, no uBlock Origin files, recordings or secrets in git | `git status`, `git log --oneline --decorate -15`, a scan of tracked files |

## 7. Out of scope

Logging in. Any view of the logged-in site. Restyling the site or hiding its own
navigation. Native tabs and native screens. File upload, downloads, camera and
microphone permissions, full-screen video, media controls: these are M3b. Replay and
harvest. Changing the ad rules for GraphQL. Release builds.

## 8. Report

Write the report to `docs/reports/M3a.md`, commit it on `m3a-web-shell`, and print it in
full as your final message. Use exactly these sections:

1. **Outcome.** Complete, partly complete, or blocked, in three sentences at most.
   State plainly whether the owner can start using the `daily` build on the phone.
2. **Acceptance checks.** The table from section 6 with a status column
   (pass, fail, not run) and the evidence for each. Quote output.
3. **Start-up contract.** The measurements, the choice, and what "ready" means for
   each extension.
4. **Text input.** Root cause, evidence, what was fixed, what a user can still hit.
5. **uBlock Origin in this app.** How it is fetched, installed, switched and updated,
   which lists are active, how the mobile environment was verified, and what you
   observed on the real logged-out page.
6. **What was built.** Screens, engine changes, extension changes, scripts, mock
   additions, tests, documents, commits.
7. **Measurements.** APK size before and after, memory with the shell on the mock with
   and without uBlock Origin, cold start to first page.
8. **Deviations.** Everything done differently from this prompt, with the reason.
9. **Problems and risks.** Include what could go wrong when the owner updates the
   `daily` build and opens the shell for the first time with a logged-in session.
10. **Questions for the planner.** Only questions that need a decision.
11. **Suggestions for M3b and for the owner's trial.**
