# Milestone M2b prompt: payload findings, sanitizer and fixtures

You are the build agent for milestone M2b of twinBook. You work alone in the repository
at `/home/wael/twinBook`. A separate planner wrote this prompt and will review your
report. The planner has access to the same machine and will re-run your checks in a
fresh clone, so every claim in your report must be reproducible from the branch you
leave behind.

Read these first, in this order: `docs/PLAN.md`, `docs/SETUP.md`, `docs/CAPTURE.md`,
`docs/ENGINE.md` (sections on filters and replay), `docs/reports/M2a.md` (sections 5
and 8 to 10). This prompt covers M2b only.

## 1. Why this milestone exists

The project owner logged in to the real site with a secondary account in the `daily`
build and browsed while twin-bridge recorded, in observe-only mode. Those recordings
are on this machine under `captures/`, outside git. M2b turns them into knowledge and
into test material:

- a findings document that tells later milestones exactly what the site sends and how
  to recognise ads in it;
- a sanitizer that converts recordings into fixtures containing structure only;
- fixtures that later milestones test against;
- a gate verdict: can the native feed, video, notifications and profile screens be
  built on the site's GraphQL data, as `docs/PLAN.md` assumes?

This milestone is analysis and tooling. It makes no request to the real site at all.

## 2. The material and what is already known

Logged-in sessions, pulled and finalized (checksums verified, taint verification
clean):

| Session | Site | What the planner saw in its metadata |
|---|---|---|
| `20261002-172602-site` | mobile | 140 requests in 137 s, of which 126 images and 13 media from `fbcdn.net` hosts. Only two requests to the site's own hosts: a beacon to `/ajax/weblite_load_logging/` and one image. No document, no XHR, no body. |
| `20261002-172927-site` | desktop | 524 requests in 180 s. 85 XHR to `www.facebook.com` with bodies, 10.5 MB: 31 to `/api/graphql/`, 17 `/ajax/bulk-route-definitions/`, 14 `/ajax/bootloader-endpoint/`, 7 `/ajax/bnzai` (3 of them with no tab), 7 `/ajax/comet_error_reports/`, 3 `/video/unified_cvc/`, 3 `/ajax/route-definition/`, 2 `/ajax/navigation/`, 1 `/ajax/relay-ef/`. No document. 151 XHR for `video/mp4` to `scontent` hosts. The observing filter attached to all 85 and changed nothing. The recorder reported 2 errors: `request: TypeError: can't access property "replace", s is undefined`. |

| `20261002-181317-site` | mobile, with page load | 287 requests in 79 s. One main document of 138 KB, recorded. One WebSocket handshake to `kaios-d.facebook.com`, path `/ws/<number>`. Two script requests for `/sw` on `m.facebook.com`, which looks like a service worker. 15 beacons to `/ajax/weblite_load_logging/` and `/ajax/weblite_resources_timing_logging/`. No XHR and no fetch. |

An attempt to record the desktop site with a page load on the emulator froze the whole
emulated system: 3 GB of guest memory and software graphics were not enough for a
desktop page reload under capture with two sessions alive. Android's own watchdog
fired. That session was never finalized and no longer exists. The unfinalized files
showed a main document of about 3 MB. The owner may record that session on a real
phone instead and pull it into `captures/` before you start.

Analyse every finalized `site` session in `captures/` whose id starts with
`20261002-17` or is later, whichever device it came from. Ignore the earlier logged-out
and mock sessions except where a comparison helps, for example the logged-out desktop
document for the structure of a page document. If no logged-in desktop document
exists, say so and work with what exists.

GraphQL queries seen in the desktop session, by friendly name, with response sizes:
`CometNewsFeedPaginationQuery` ×4 (0.6 to 2.1 MB, 6 to 19 chunks),
`ProfileCometTimelineFeedRefetchQuery` ×3, `FBUnifiedVideoContainerQuery` ×2,
`FBUnifiedVideoRootWithEntrypointQuery`, `CometSinglePostDialogContentQuery`,
`CometNotificationsDropdownQuery`, `StoriesTrayRectangularQuery`,
`ProfileCometTilesFeedPaginationQuery`, `CometHovercardQueryRendererQuery`,
`useInstreamAdsHaloFetcherQuery`, several `CometHomeContact…` queries, three mention
data-source queries, and the mutations `FBUnifiedVideoSeenStateMutation`,
`CometNotificationsPushTurnOnMutation`, `CometRecordProductUsageMutationMutation`.
GraphQL responses arrive with content type `text/html; charset="utf-8"`. The probe
counted the candidate keys `sponsored_data` 38 times, `client_token` 17, `ad_id` 11.
A key that is present with a null value still counts, so these are not ad counts.

Two leads to settle:

- **The mobile site's data channel.** While the owner scrolled, opened comments,
  watched videos and opened a profile, the mobile site made no XHR or fetch at all.
  The session with the page load shows why: the page opens a WebSocket to
  `kaios-d.facebook.com` and registers what looks like a service worker. The logged-in
  mobile site is a "web lite" client whose data travels in WebSocket frames, which the
  recorder and the stream filter cannot see. Confirm this from the recording, describe
  what the 138 KB document and the handshake show, and state plainly what can and
  cannot be filtered on the mobile site.
- **Where document ids come from.** M5 would like to obtain tokens and the document
  ids of queries without running the desktop site's JavaScript application on the
  device, because that application is heavy. Find out which recorded responses carry
  document ids, for example route definitions and bootloader responses, and which
  tokens are available from a plain page document.
- **Requests with no tab.** Three `/ajax/bnzai` posts had no tab id. Find out what made
  them and whether such responses pass through the stream filter.

## 3. Rules

- Preflight: run `git fetch origin` and confirm that `main` equals `origin/main` and
  the working tree is clean, then `git switch -c m2b-findings main`. If either is not
  true, stop and report. Do not commit on `main` or any other branch. Never push, never
  merge. Do not edit `docs/PLAN.md` or this prompt.
- **No traffic to the real site.** No app session on the site, no curl, no browser,
  no replay. You do not need the `daily` app at all. Do not open its capture browser.
- **Protect the owner's session.** Never uninstall the `daily` app, clear its data, or
  wipe or recreate the AVD. See `docs/SETUP.md`, "Protecting the owner's session".
- **Personal data.** The recordings contain other people's names, posts, photo links
  and identifiers. They stay under `captures/`. Nothing from them may appear in git,
  in the findings document, in your report, in commit messages, in test names or in
  log output you quote: no names, no post text, no profile or photo URLs, no user or
  post identifiers, no cursor or token values. Describe structure: key paths, type
  names, enum values, counts, sizes, lengths. When you need an example, use the
  sanitized fixture, never the raw value.
- **Secrets.** Do step 5.1 before you read any response body. If you come across a
  value that looks like a live credential, do not copy it anywhere. Add its key to the
  rules, re-scrub, and mention the key name only.
- **The repository is public on GitHub.** Treat every committed byte as published.
- No `sudo`, no global installs, no edits to shell profiles or global git config.
- Allowed new dependencies: none are expected. Justify any exception in the report.
- Evidence over assertion. A check you did not run is "not run" with the reason.
- This is a gate. If the data cannot support the design, do not bend the analysis to
  make it fit. Say what is missing and what it would take.
- Priority order if you run short of time or context: 5.1, 5.2, 5.3 and 5.4 first,
  then 5.5 and 5.6, then 5.7. Report honestly what was not reached.

## 4. Decisions already made

1. Raw recordings never enter git. Fixtures are structure-only, as defined in 5.5, and
   are committed only if the leak test of 5.5 passes with zero findings.
2. Third-party request records never enter fixtures.
3. The ad rules proposed here are a first version for M4. They need at least two
   independent signals before a post counts as an ad, so that a decoy field on an
   organic post cannot cause a removal.
4. The replay path for M5 is a same-origin anchor page with no site JavaScript. M2b
   only collects what M5 needs to know. It replays nothing.

## 5. Work to do

### 5.1 Secret hardening, before any body is read

- Extend the layer 1 key list in `extension/data/redaction-rules.json` with camel-case
  and other spellings of token names that the current exact-name list would miss, for
  example `accessToken`, `sessionToken`, `csrfToken`, each with its reason.
- Write an offline tool that re-applies layer 1 and then the layer 2 taint pass to an
  already pulled session with the current rules, writing a new session directory next
  to the original and leaving the original untouched. Work only from the re-scrubbed
  copies afterwards.
- Write a scan that lists, for a session, every JSON key, form field, URL parameter
  and header whose values are long, opaque and recur across requests. It prints key
  names, value lengths, counts and where they occur. It never prints a value. Run it,
  decide for each key whether it is a credential, a session-shaped field or neither,
  and record the decision with the reason in the findings document. Re-scrub again if
  the rules changed.

### 5.2 Recorder fixes

- Find and fix the cause of `can't access property "replace", s is undefined`, with a
  test that reproduces it. State which requests were affected in the owner's session
  and what was lost for them.
- Make the capture browser reload the current page automatically when a capture
  starts, so page documents are always recorded. Neither of the owner's first two
  captures contains one. Update `docs/CAPTURE-CHECKLIST.md` to match.
- Make sure WebSocket and other long-lived connection handshakes are recorded as
  metadata with their type, host and path, and that their existence is visible in
  `tools/capture-summary.mjs`.
- Recordings may come from the emulator or from a real phone. The device scripts take
  the device from `ANDROID_SERIAL`. Check that `tools/capture-pull.sh` and
  `tools/capture-summary.mjs` make no emulator-only assumption.
- Do not install anything on the `daily` build yourself. Say in the report whether the
  owner should update it with `tools/daily-install.sh` before any further capture.

### 5.3 Findings document

Write `docs/findings/payloads.md` for the agents who will build M3 to M10. Organise it
so that each later milestone can find its part. Cover at least the following, each
with counts from the recordings and with key paths written out in full.

1. **Transport.** GraphQL endpoint, content type, guard prefix, how a response is
   split into documents and lines, the fields that mark incremental delivery and the
   end of a response, line sizes, chunk counts and timing, encodings, protocol.
   Whether every response the page consumed passed through the stream filter, including
   the requests with no tab.
2. **Request anatomy.** Every form field of a GraphQL request: which are constant in
   the session, which change per request, which look derived from others. Request
   headers that are specific to the site's client. The same for the other `/ajax/`
   endpoints, briefly. Compare with what replay path B sent in M1
   (`docs/reports/M1.md` section 3) and list what a replayed request would be missing.
3. **Query catalogue.** For each friendly name: purpose, document id, variable names
   and types, top-level response shape, how pagination works, which labels its
   incremental documents carry.
4. **Feed story anatomy.** Where a story keeps its id, actors, text and text ranges,
   attachments by kind, counts, time, audience, and group or page context. The
   renderer type names the site attaches to each part, with counts. Which parts are
   always present and which are optional. How many distinct story shapes occur.
5. **Ads and suggestions.** Every signal that separates sponsored stories, suggested
   content and organic stories, with a table of how often each signal occurs in each
   class. Whether any field looks like a decoy: present on organic stories with a
   shape that imitates an ad. Proposed rules v1 as data, in the form the M1 rule
   interface can run, and the result of running them over every recorded feed edge:
   how many kept, how many flagged, by which signals. Compare with the number of
   sponsored posts the owner reports, if the planner supplied it; otherwise state the
   count your rules found.
6. **Video.** Where playable URLs, streaming manifests, qualities, durations,
   thumbnails and captions live. How the site's client fetched media in the recording:
   request pattern, ranges, hosts. What an Android player would need from this.
7. **Comments, notifications, profile.** Structure of each as far as recorded, and
   which queries are missing for a complete screen, for example comment pagination.
8. **Navigation endpoints.** What `/ajax/bulk-route-definitions/`,
   `/ajax/route-definition/`, `/ajax/navigation/` and `/ajax/bootloader-endpoint/`
   return and whether a native client needs any of it.
9. **Tokens and cookies.** Where each token appears, what can be said about rotation
   from the recording, cookie names with their attributes and lifetimes. Limits of the
   recording for this question, since values are redacted.
10. **Page documents**, if any session has them: size, the script elements that carry
    data, where the first feed stories and the tokens sit.
11. **The mobile site.** Everything the recordings show about how the logged-in mobile
    site gets its data, and what that means for filtering it.
12. **Telemetry and third parties.** Every own-host endpoint that looks like logging,
    with request counts and sizes, and every third-party host contacted.
13. **Numbers.** Whether any number in a GraphQL body would change if the body were
    parsed and serialized again by JavaScript: integers above 2^53, unusual formats.
    This decides whether the enforce filter may re-serialize a document.
14. **Open questions**, each with what would answer it.

### 5.4 Gate verdict

State whether the recorded GraphQL data is sufficient to build the native screens.
Apply these criteria and report the numbers:

- For feed stories: the share of recorded stories for which author name, author
  picture, time, text, the primary attachment and the reaction and comment counts can
  each be located by a path that holds across stories of the same shape.
- Ads: at least two independent signals exist for sponsored stories.
- Video: a playable source is present for recorded videos.
- Pagination: the cursor mechanics of the feed query are understood well enough to
  request the next page.

Give a verdict of pass, pass with reservations, or fail, and name the biggest risk.

### 5.5 Sanitizer and fixtures

- A sanitizer tool that turns a re-scrubbed session into fixtures. Fixtures are
  structure-only:
  - JSON keys, structure, array lengths, booleans and nulls are kept.
  - String values are replaced by synthetic values unless they are structural. Decide
    what is structural with an explicit, reviewed allowlist approach: type names,
    enum-like constants, renderer and strategy names, labels of incremental documents,
    and similar. When in doubt a string is not structural.
  - Replacements are deterministic and keep what tests need: the same input value maps
    to the same output value within a fixture set, lengths in UTF-16 units are kept for
    text that has ranges pointing into it, URLs become URLs on a reserved example
    domain that keep the kind of resource and nothing else, identifiers keep their
    character class and length.
  - Numbers that are identifiers or timestamps are remapped consistently. Counts may be
    kept.
  - Request fixtures keep field names, the friendly name, the document id and the shape
    of the variables, with values treated as above.
- A leak test, run by `tools/check.sh` whenever the raw session is present on the
  machine and reported as skipped when it is not: no string of four or more characters
  that occurs as a non-structural value in the raw session occurs anywhere in the
  fixtures. Report its numbers. Also scan fixtures for e-mail addresses, phone numbers,
  and URLs outside the reserved domain.
- Fixtures under `fixtures/`, grouped by query, with a manifest recording provenance
  without personal data: session id, date, the site revision field, engine version.
  Include every query of section 2 that a native screen needs, and enough feed pages to
  cover every story shape and every ad you found.
- A document describing the fixtures and how to regenerate them:
  `fixtures/README.md`.

### 5.6 Tests on the fixtures

- The existing stream filter, in observe mode with the probe rule, runs over every
  GraphQL fixture: no parse failure, output identical to input.
- The proposed rules v1 run over the feed fixtures in enforce mode on the mock
  profile: the expected stories are removed, the rest is byte-identical, and the final
  document rule holds.
- These tests run in `tools/check.sh` from a fresh clone, using only committed
  fixtures.

### 5.7 Summary tooling

Extend `tools/capture-summary.mjs`, or add a sibling, so the numbers in the findings
document can be regenerated from a session with one command.

## 6. Acceptance checks

| ID | Check | Evidence required |
|---|---|---|
| F1 | `env -i HOME=$HOME bash tools/check.sh` passes from a clean clone | tail of output, test counts |
| F2 | Rules extended; re-scrub tool run on every analysed session; opaque-value scan run; decisions recorded | tool output with key names, lengths and counts only |
| F3 | Recorder error reproduced by a test and fixed; affected requests identified | test output, list by endpoint |
| F4 | Capture start reloads the page; handshakes of long-lived connections are recorded and summarised | tests on the mock |
| F5 | `docs/findings/payloads.md` covers the 14 topics of 5.3 | section list with one line each on what was found |
| F6 | Gate verdict with the numbers of 5.4 | the numbers |
| F7 | Sanitizer produces fixtures; leak test finds nothing; pattern scans find nothing | leak test and scan output |
| F8 | Fixture tests of 5.6 pass from a fresh clone | test summary |
| F9 | Proposed rules v1 as a data file, with the table of 5.3 item 5 | file path, the table |
| F10 | No personal data and no secret in git, in the findings, or in this report | description of the scans you ran over your own branch and their output |
| F11 | No traffic to the real site; `daily` app untouched | statement, and the app's install time before and after |
| F12 | Branch clean, nothing pushed, `main` untouched | `git status`, `git log --oneline --decorate -12` |

## 7. Out of scope

Any request to the real site. Replay. Enforcing a filter on the real site. The Kotlin
normalizer and models, which are M6. Native UI. uBlock Origin. Decoding the mobile
site's socket protocol beyond identifying it.

## 8. Report

Write the report to `docs/reports/M2b.md`, commit it on `m2b-findings`, and print it in
full as your final message. The owner pastes it into a chat, so the rules on personal
data apply to it without exception. Use exactly these sections:

1. **Gate verdict.** Pass, pass with reservations, or fail, with the numbers of 5.4
   and the biggest risk, in five sentences at most.
2. **Acceptance checks.** The table from section 6 with a status column
   (pass, fail, not run) and the evidence for each.
3. **Key findings.** The ten facts a planner must know before writing M3 to M7, each
   in one or two sentences, with a pointer into the findings document.
4. **Ad rules v1.** The signals, the class table, the rules, and the result of running
   them over the recordings.
5. **The mobile site.** What the recordings show and what it means for the plan to use
   the mobile site as the web fallback with ads removed.
6. **What was built.** Tools, tests, fixtures, documents, commits.
7. **Sanitizer design and limits.** How structural strings are decided, what the leak
   test covers, and every way personal data could still reach a fixture.
8. **Deviations.** Everything done differently from this prompt, with the reason.
9. **Problems and risks.**
10. **Questions for the planner.** Only questions that need a decision.
11. **Suggestions for M3, M4, M5 and M6.** What the data says each should do
    differently from `docs/PLAN.md`.
