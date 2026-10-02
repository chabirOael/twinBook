# Milestone M2a prompt: capture tooling and real-site wiring

You are the build agent for milestone M2a of twinBook. You work alone in the repository
at `/home/wael/twinBook`. A separate planner wrote this prompt and will review your
report. The planner has access to the same machine and will re-run your checks in a
fresh clone, so every claim in your report must be reproducible from the branch you
leave behind.

Read these first, in this order: `docs/PLAN.md`, `docs/SETUP.md`, `docs/ENGINE.md`,
`docs/reports/M1.md` (sections 7 to 11). This prompt covers M2a only.

## 1. Why this milestone exists

M1 proved, against a mock, that twin-bridge can filter response streams below the
page's JavaScript. The next question is what the real site actually sends to a
logged-in user. That knowledge will come from the project owner browsing the real
site in the app for ten minutes while twin-bridge records what passes through,
changing nothing. M2a builds everything needed for that session. A later milestone,
M2b, analyses the recordings.

Three things make this milestone delicate.

- **The owner's account.** After M2a the owner logs in with a real account in a build
  you produce. Nothing you build may put that account at risk: no modified bytes, no
  extra requests, no rewritten headers on the real site. Observe only.
- **Secrets.** The recordings would naturally contain session cookies and anti-forgery
  tokens. They must be removed before the data can be pulled off the device. A build
  agent will read these recordings in M2b, and nobody but the owner's own browser
  session should ever hold a working credential.
- **Durability of the login.** Once the owner has logged in, that app install must
  survive everything agents do afterwards: test runs, reinstalls, new builds.

You will not log in, you have no account, and you must not create one.

## 2. Starting state, verified by the planner on 2026-10-02

- M1 is merged. Local `main` equals `origin/main`. The plan update and this prompt are
  already committed there, and the working tree is clean.
- `tools/check.sh`, the 28 instrumented tests, `tools/persistence-test.sh` and
  `tools/extension-update-test.sh` all pass on `main`.
- `/dev/kvm` is accessible through a temporary permission change. If it is not when
  you start, do everything that needs no device, then report.
- Memory is tight. Host peak in M1 was 10.2 GB of 11 GB with swap in use. Stop Gradle
  daemons before long emulator sessions.

What the planner observed on the real site on 2026-10-01, logged out, from a desktop
machine. Treat these as leads to confirm, not as facts:

- `https://m.facebook.com/` with a mobile user agent serves a server-driven UI called
  Bloks: markers `StartFBWebBloks`, `bloks_payload`, requests to `/async/wbloks/...`.
  It redirected to a `/unified/login_via/app/` URL and tried to navigate to a custom
  URL scheme to open the native app.
- `https://www.facebook.com/` with a desktop user agent serves the React application
  called Comet: marker `__comet_req`, GraphQL at `/api/graphql/`, telemetry posts to
  `/ajax/bz` and `/ajax/qm/`.
- The logged-out mobile page loaded third-party advertising pixels from Google hosts
  through `fbsbx.com`.
- Requests carry rotating anti-forgery tokens in form fields: `fb_dtsg`, `lsd`,
  `jazoest`, and session-shaped fields such as `__user`, `__s`, `__hsi`, `__dyn`,
  `__csr`, `__rev`, `__req`.

## 3. Rules

- Preflight: run `git fetch origin` and confirm that `main` equals `origin/main` and
  the working tree is clean, then `git switch -c m2a-capture-tooling main`. If either
  is not true, stop and report. Do not commit on `main` or any other branch. Never
  push, never merge.
- Do not edit `docs/PLAN.md` or this prompt.
- **Real-site traffic budget.** You may load the real site's logged-out pages from the
  app on the emulator, only to prove the wiring: at most 12 page loads in total across
  `m.facebook.com` and `www.facebook.com`. You must not submit any form, type into
  any field, tap any button on those pages, create an account, or log in. You must not
  contact the site from any other tool: no curl, no scripts, no browser automation.
  Keep a log of every load you make and put it in the report.
- **Observe only on the real site.** For every real-site host there must be no code
  path that writes modified bytes, rewrites a header, blocks a request, makes a request
  of its own, or runs a content script. Replay is disabled there. This is enforced by
  configuration and proven by tests, not by intention.
- No `sudo`, no global installs, no edits to shell profiles or global git config.
- Allowed new dependencies: none are expected. If you need one, it must be small,
  justified in the report, and not one of: Mozilla Android Components, uBlock Origin,
  Room, Media3, a DI framework.
- Raw recordings never enter git. `captures/` and `*.har` are already ignored. Synthetic
  test data that you write yourself is fine to commit.
- Evidence over assertion. A check you did not run is "not run" with the reason.
- If something on the real site behaves in a way that blocks the wiring, for example
  the page refuses the browser, do not work around it by altering traffic. Record what
  happens and report.
- Priority order if you run short of time or context: section 5.1 to 5.6 and their
  checks first, then 5.7, then 5.8. Report honestly what was not reached.

## 4. Decisions already made

1. Recording happens in the twin-bridge background script through the same
   `webRequest` machinery M1 built. Bodies are copied from the response stream while
   the original bytes are forwarded untouched.
2. The extension cannot write files. Records travel over the bridge to Kotlin, which
   writes them to app-private storage.
3. Redaction has two layers, both mandatory, described in 5.3.
4. The owner logs in to a separate, login-safe install of the app, described in 5.5.
   Automated tests never install, uninstall or clear that install.
5. The sanitizer that turns recordings into committed fixtures is not part of M2a. It
   is built in M2b with real shapes in hand.
6. Tracking protection stays at the engine defaults and no content blocker is added.
   The point of the recording is to see everything the site does.

## 5. Work to do

### 5.1 Carried over from the M1 review

- **Line-loss defect.** In `NdjsonStreamFilter.push`, the pending bytes are cleared
  before `processLine` runs. If `processLine` throws from a place its own handlers do
  not cover, the bytes of that line are in neither the output nor the pending buffer,
  so the pass-through fallback loses them. Fix it and add a unit test that forces such
  a throw and shows the output is byte-identical to the input.
- **Lossless events.** The bridge replays only the last 64 events to new collectors.
  Recording will produce far more. Whatever transport you choose for records must be
  lossless, ordered per record, and must apply backpressure instead of dropping.

### 5.2 Site profiles and observe-only mode

- Replace the single hard-wired target in the extension with site profiles: the mock
  profile with everything M1 does, and a real-site profile for the site's hosts. The
  manifest gains the host permissions the real-site profile needs. Request metadata
  may be logged for every host the pages contact, so third-party trackers become
  visible. Bodies are recorded only for the site's own hosts.
- Add a filter mode `observe`: the rule runs and its decisions are counted and
  reported, but the output is the input, byte for byte, with no re-serialization. The
  real-site profile is locked to `observe` or `off`. The mock profile can use
  `enforce`, `observe` or `off`.
- In M2a there are no real ad rules. For the real-site profile the observing rule is a
  probe that reports, per document, its size, its top-level keys, and which of a
  configurable list of candidate key names occur anywhere inside it. Keep the list in
  one data file. Start it with `sponsored_data`, `ad_id`, `is_sponsored` and
  `client_token`. These are guesses to be checked in M2b.

### 5.3 Recorder and redaction

Record, for requests made by pages of the site:

- For every request: method, URL, resource type, timing, status line, protocol if
  available, request and response headers, sizes, cache and redirect information, and
  every other field `webRequest` exposes that could show whether a service worker or
  the cache answered. M2b needs to find out whether responses can bypass the stream
  filter.
- Request bodies of form posts, parsed into fields.
- Response bodies for main documents and for XHR and fetch responses with textual
  content types, from the site's own hosts, with a size cap per body that you choose
  and document. Record that a body was truncated when it is. Static script and style
  files and all media are metadata only.
- For streamed responses, the chunk boundaries and arrival times, so M2b can study
  line sizes and delivery timing.

Redaction layer 1, at the source, before a record leaves the extension:

- `Cookie` request headers and `Set-Cookie` response headers keep cookie names and
  attributes. Values are replaced by a placeholder that keeps the length.
- Other credential headers are treated the same way.
- Form fields and JSON string values whose key matches a secret pattern are replaced
  the same way. Keys and structure stay. Start the pattern list with the token names
  in section 2 that are credentials: `fb_dtsg`, `lsd`, `jazoest`, plus generic names
  such as access tokens and session keys. Keep it in one data file with a comment per
  entry. Do not redact shape-only fields such as `__rev` or `__req`.

Redaction layer 2, taint, before a session can leave the device:

- Every secret value seen in layer 1 is remembered in memory for the session, together
  with a stable label such as `cookie:c_user` or `field:fb_dtsg`.
- When the owner stops a capture, a finalize pass replaces every occurrence of every
  remembered value anywhere in the session's stored data, including bodies recorded
  before the value was first recognized, with a placeholder naming its label. Short
  values that would cause false matches are handled by a rule you define and document.
- A session is marked finalized only when that pass completes. The remembered values
  are never written to disk.
- A session that was not finalized, for example because the process died, is deleted
  at the next app start and can never be pulled.

One implementation of the redaction logic must serve the extension, the finalize pass
and the HAR importer of 5.8, or at least one specification with shared test vectors
if a single implementation is not possible across languages.

Write the capture format down in `docs/CAPTURE.md`: directory layout, record schema,
versioning, placeholder syntax, limits.

### 5.4 Capture browser screen

A development screen in the app, reachable from a simple start screen that also keeps
the M1 engine lab.

- A visible session in a GeckoView that fills most of the screen.
- Site switch with two positions: mobile site (mobile user agent, `m.facebook.com`)
  and desktop site (desktop user agent, `www.facebook.com`). Use one session per
  position, kept alive when switching.
- Back, reload, and a read-only display of the current URL.
- Capture control: start, stop with finalize, and live counters for records, bytes,
  redactions and errors. The state of the last session: finalized or not.
- The first load waits for `Engine.awaitReady()`.
- Navigation safety: only `http` and `https` loads are allowed. Custom schemes and
  intents are ignored without leaving the app. Requests to open a new window load in
  the same session. Permission requests are denied. No password saving and no autofill.
- Text input must work with both the on-screen keyboard and the host keyboard, since
  the owner types the login in the emulator window.
- JavaScript dialogs and file pickers: implement alert, confirm and prompt in the
  plainest way. A file picker is not needed.

### 5.5 Login-safe install

- Add a second debuggable build of the app, named `daily`, with its own application ID
  suffix, that can be installed next to the debug build. It is the build the owner logs
  in to. It includes the capture browser.
- No Gradle task or script that runs tests may install over, uninstall or clear the
  `daily` build. Instrumented tests keep targeting the debug build and the engine test
  app.
- Scripts: one to build and install or update the `daily` build keeping its data, one
  to launch it. Updating must keep cookies and extension storage, as M1 proved for the
  engine test app.
- Document in `docs/SETUP.md`, in a short section titled "Protecting the owner's
  session", every action that would destroy the login: uninstalling the `daily` app,
  clearing its data, wiping or recreating the AVD, starting the emulator with a wipe
  option. State that agents must never do these.

### 5.6 Pull script

`tools/capture-pull.sh`: lists sessions on the device, copies finalized sessions from
the `daily` app's private storage into `captures/` in the repository, verifies their
checksums, prints a summary per session, and refuses sessions that are not finalized.
A second mode works against the debug build for tests.

### 5.7 Mock additions and tests

Extend the mock so every part above is testable without the real site:

- A page that plants known secrets the way the real site does: a token inside the HTML
  document, the same token later sent as a form field; cookies set with attributes; a
  cookie value that also appears inside a later JSON body.
- An endpoint shaped like the mobile site's fetch endpoint: a single JSON document
  behind the `for (;;);` guard.
- A page with a login-like form for the text-input check.
- A link with a custom scheme, a link that opens a new window, a page that asks for a
  permission.

### 5.8 Owner checklist and HAR importer

- `docs/CAPTURE-CHECKLIST.md`, written for the project owner, who is a developer but
  has not read the code. Exact steps: start the emulator in a visible window, install
  and launch the `daily` build, open the capture browser, log in on the mobile site,
  start capture, what to do on the mobile site, switch to the desktop site, what to do
  there, stop capture, how to tell it finalized, what to report back. Activities to
  include on each site: scroll the home feed slowly past at least five sponsored posts,
  open the comments of two posts, open the video tab and watch three videos, open
  notifications, open one profile. Include what to do if the site shows a security
  check, and a reminder to use the secondary account. Dry-run every step yourself,
  using the mock for the logged-in parts.
- HAR importer: a tool that converts a HAR file exported from desktop Firefox into the
  capture format, applying both redaction layers, and writes only the redacted result.
  Test it with a synthetic HAR built from the mock.

## 6. Acceptance checks

Tooling, proven on the mock.

| ID | Check | Evidence required |
|---|---|---|
| C1 | `env -i HOME=$HOME bash tools/check.sh` passes from a clean clone | tail of output, test counts |
| C2 | Line-loss defect fixed | the new unit test, shown failing on the old code and passing on the new |
| C3 | Observe mode: for every M1 scenario the page receives exactly the unfiltered input, while the reported decisions equal those of enforce mode | test summary with hashes |
| C4 | Recorder completeness: every request the mock server saw in a scripted session is in the capture with matching method, URL, status and body hash | comparison output |
| C5 | Redaction layer 1: cookie and token values absent from records as they leave the extension; names and attributes present | test summary |
| C6 | Redaction layer 2: a secret that appears in an HTML body before it is first recognized is absent after finalize; a scan of the finalized session for every planted secret finds nothing | scan output |
| C7 | A capture interrupted by a process kill cannot be pulled and is deleted at next start | script output |
| C8 | Lossless transport under load: at least 200 responses including one of 5 MB, all recorded, hashes verified | counts and timing |
| C9 | Pull script copies a finalized session, verifies checksums, refuses an unfinalized one; `git status` stays clean | script output |
| C10 | Capture browser on the mock: text input from on-screen and host keyboard, custom scheme ignored, new window loads in place, permission denied, dialogs work, back and reload work | instrumented tests, one screenshot |
| C11 | All M1 instrumented tests and device scripts still pass | summaries |

Login-safe install.

| ID | Check | Evidence required |
|---|---|---|
| C12 | `daily` and debug builds are installed side by side | package list |
| C13 | Data placed in the `daily` build survives a full run of `tools/connected-test.sh`, the M1 device scripts, and an update of the `daily` build | script output with a marker value before and after |

Real site, logged out, within the traffic budget.

| ID | Check | Evidence required |
|---|---|---|
| C14 | The capture browser shows the mobile site's login page with the mobile user agent and the desktop site's with the desktop user agent | two committed screenshots under `docs/reports/assets/` |
| C15 | A capture of those loads finalizes and pulls. Summary: records by host and type, content types, encodings, protocols, which of the section 2 leads were confirmed, whether the observing filter attached to documents and to XHR, and that it changed nothing | summary table |
| C16 | Redaction on that capture: cookie names with attributes and no values, count of token redactions, taint scan clean | redaction report |
| C17 | Observe-only guarantee for the real-site profile: no enforce mode, no replay, no header rewriting, no content script | tests, and the relevant manifest and configuration excerpts |
| C18 | Traffic log: every real-site page load you caused, with time and purpose, and a statement that no form was submitted and nothing was typed or tapped | the log |

Documentation and housekeeping.

| ID | Check | Evidence required |
|---|---|---|
| C19 | `docs/CAPTURE.md` and `docs/CAPTURE-CHECKLIST.md` written; `docs/ENGINE.md` and `docs/SETUP.md` updated | file list, checklist dry-run notes |
| C20 | HAR importer converts a synthetic HAR with planted secrets; none survive | test summary |
| C21 | No recording, cookie, token or HAR in git; branch clean; nothing pushed; local `main` untouched | `git status`, `git log --oneline --decorate -12`, a scan of tracked files |

## 7. Out of scope

Logging in. Any analysis of logged-in data. The sanitizer and fixtures. Real ad rules.
Enforcing any filter on the real site. Replay against the real site. uBlock Origin.
The product's navigation shell and native UI. Release builds.

## 8. Report

Write the report to `docs/reports/M2a.md`, commit it on `m2a-capture-tooling`, and
print it in full as your final message. Use exactly these sections:

1. **Outcome.** Complete, partly complete, or blocked, in three sentences at most.
   State plainly whether the build is ready for the owner to log in.
2. **Acceptance checks.** The four tables from section 6 with a status column
   (pass, fail, not run) and the evidence for each. Quote output.
3. **Real-site observations.** What the logged-out loads showed: the C15 summary, the
   leads confirmed or contradicted, anything about how the site treats this browser,
   and the traffic log of C18.
4. **What was built.** Site profiles, filter modes, recorder, redaction, transport,
   capture format, capture browser, build types, scripts, mock additions, commits.
5. **Redaction design and limits.** Both layers as built, the rule for short values,
   and every way you can think of that a secret could still reach a pulled session.
6. **Measurements.** Recording overhead on the mock: time and memory with capture on
   and off for the 5 MB response and for the 200-response load. Size on disk of the
   logged-out capture.
7. **Deviations.** Everything done differently from this prompt, with the reason.
8. **Problems and risks.** What broke, what is fragile, what could go wrong during
   the owner's session.
9. **Questions for the planner.** Only questions that need a decision.
10. **Suggestions for the owner's session and for M2b.** What the owner should watch
    for while browsing, and what the analysis should look at first.
