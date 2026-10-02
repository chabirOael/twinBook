# M2b report: payload findings, sanitizer and fixtures

Branch `m2b-findings`, built on `main` at `a173123`. Nothing pushed, nothing merged. No request
was made to the real site, and the `daily` app was not touched.

## 1. Gate verdict

**Pass with reservations.** Over the 43 recorded feed stories, author name, author picture,
reaction count and comment count are found on one path each in 43 of 43. Time is found in
38 of 43; the 5 misses are the 5 ads, which carry no time. Text is found in 41 of 43 and the
primary attachment in 41 of 43; both are absent by design in the 2 stories without them.
Ads carry 4 independent signal families (every ad matched 3 or 4, no other edge any), 74 of
74 video delivery results have a playable progressive HD and SD URL plus a DASH manifest, and
pagination is understood (`count` 5, `cursor` = previous `end_cursor`, 7 of 7 consecutive
pairs). The biggest risk is M5's harvest: the document ids of the follow-up queries (feed
pagination, comments, notifications, video, profile refetch) appear in no recorded response
and must come from the site's script bundles.

## 2. Acceptance checks

| ID | Status | Evidence |
|---|---|---|
| F1 | pass | `env -i HOME=$HOME bash tools/check.sh` in a fresh clone of `m2b-findings`: exit 0; extension 16 test files, **`Tests 180 passed \| 1 skipped (181)`**, the skipped one being the leak test (no raw sessions in the clone); typecheck and esbuild clean; `lint: errors 0, warnings 1 (allowed 1), notices 0`; Gradle `check assembleDebug` **BUILD SUCCESSFUL in 41s**, JVM tests 29 (`:data` 1, `:capture` 6, `:mockserver` 22); last line `== check.sh: all checks passed`. In the working tree, with the raw sessions present: 181 passed, the leak test included. Extension tests were 116 before M2b; new: 5 recorder, 3 re-scrub and scan, 57 fixture tests. |
| F2 | pass | Redaction rules v1 → v5, 28 → 76 keys (`extension/data/redaction-rules.json`, a `why` per key). `tools/capture-tools.sh rescrub` run on all 4 sessions after each version; final run: `172602` 0 new values, `172927` 0, `181317` 1 (key `dtsg`), `183314` 53 remembered, 51 eligible (keys `ServerNonce`, `accountKey`, `accountKeyV2`, `compat_iframe_token`, `device_id`, `e2eeDeviceID`, `encrypted`, `encrypted_serialized_cat`, `data`, `link_react_default_hash`, `logoutToken`, `logout_hash`, `openDeviceID`, `secret`, `untrusted_link_default_hash`, `userKeyBase`, `x-dgw-deviceid`), 3 further occurrences scrubbed by layer 2 (`field:x-dgw-deviceid`), verification hits 0 on all 4. `tools/capture-tools.sh scan`: 13 / 96 / 16 / 116 keys with recurring opaque values; credential-like names 1 / 22 / 2 / 29; long unredacted strings below credential-like keys 0 / 0 / 0 / 13 (all route-map module references, a click-tracking parameter, messaging backup ids, a tracking blob; decided not credentials). Every key's decision with its reason: docs/findings/payloads.md, appendix. |
| F3 | pass | Cause: Gecko's form parser yields `undefined` for a body part without `=`; layer 1's `formFields` passed it to `text()`, whose `.replace` threw, and `metaLine` dropped the whole `request` line. `extension/test/captureRecorder.test.ts`: with the old code 4 of its 5 tests fail with `TypeError: Cannot read properties of undefined (reading 'replace')`; with the fix 5 of 5 pass. Fix: such fields are recorded as `[name, null]`, and a throwing body part now yields the line with `extraError` instead of losing it. Affected in the owner's session `172927`: 2 requests, both `POST /ajax/route-definition/` (rids 1065, 1066). Lost for them: the `request` line only (URL, method, timestamp, form fields including `route_url`); their headers, response body (127 KB and 148 KB) and observe lines were recorded. |
| F4 | pass | Reload: `CaptureBrowser.startCapture()` starts the recorder, then reloads the visible site (never both). Instrumented test `CaptureBrowserScreenTest#captureStartReloadsThePageSoItsDocumentIsRecorded` on the emulator with the mock: `OK (1 test)`, evidence `page reloaded (login.html requests 1 -> 2), session … has 1 main_frame request(s) and 1 document body`; the other site's page was not loaded. Handshakes: Vitest `long-lived connections` (fake WebExtension API): a `wss://` handshake gives `request`, `sendHeaders`, `headers` (101), `completed` lines with type, host and path, and no stream filter. `tools/capture-summary.mjs` has a new table "Long-lived connections"; on the owner's sessions it lists `websocket wss kaios-d.facebook.com /ws/<n> status 101`, 7 desktop sockets (`gateway.facebook.com /ws/{realtime,lightspeed,streamcontroller,rpsignaling}`, `web-chat-e2ee.facebook.com /ws/chat`) and the `/sw` scripts. CAPTURE-CHECKLIST.md updated. |
| F5 | pass | docs/findings/payloads.md, sections 1 to 14 (one line each in section 3 of this report and below): 1 transport, 2 request anatomy, 3 query catalogue (43 operations), 4 story anatomy (13 shapes), 5 ads and suggestions with rules v1 results, 6 video, 7 comments, notifications, profile, 8 navigation endpoints and document ids, 9 tokens and cookies, 10 page documents, 11 mobile site, 12 telemetry and third parties, 13 numbers, 14 open questions (10), plus the gate and the redaction appendix. |
| F6 | pass | Section 1 of this report; table in docs/findings/payloads.md "Gate verdict". |
| F7 | pass | `tools/capture-tools.sh fixtures`: 83 files, 13.5 MB, 209,655 strings (47,820 kept, 161,835 replaced, 2,320 URLs), 496 numbers remapped, 1,647 keys replaced; regeneration byte-identical (same SHA-256 over all files twice). Leak test: `raw sessions 4; raw non-structural strings 33600, identifier tokens 56666, remapped numbers 477, non-schema keys 13240; fixture files 83, strings and keys 577440, tokens 57100, numbers 68103; leaks 0`. Pattern scan: `e-mails 0, phones 0, foreign URLs 0, site host names 0`. |
| F8 | pass | `extension/test/fixtures.test.ts`: 57 tests. 1 manifest check; 46 response and preload files through the NDJSON filter in observe mode with the probe at random chunk boundaries (no parse failure, output identical to input); 10 tests of rules v1 in enforce mode (exactly the expected edges removed, 5 in all, other lines byte for byte, final-document rule); pattern scan; leak test (skipped in a fresh clone). All in `tools/check.sh`, committed fixtures only. |
| F9 | pass | `rules/ads-v1.json`, run by `extension/src/lib/adRules.ts`; class table in section 4. |
| F10 | pass | `tools/capture-tools.sh leakscan` over all 38 text files this branch adds or changes outside the fixture data, against every non-structural string (8+ characters), identifier token and remapped number of the 8 raw and re-scrubbed sessions: 178 matches, all reviewed. They are generic or structural (host names such as `facebook.com`, query and module names such as `CometModernHomeFeedQuery`, common words such as `position`), the site revision number in `fixtures/manifest.json` (asked for by the prompt), and a `000000` in a synthetic test session id; no e-mail or phone number except the `example.test` addresses of an M2a test. The fixtures pass their own leak test (F7). `git ls-files captures/` lists nothing. This report and the findings contain key names, type names, counts and sizes only. |
| F11 | pass | No request to the real site: no app session, no curl, no browser; the emulator ran only the debug app's mock test. The `daily` app: `firstInstallTime=2026-10-02 10:43:38`, `lastUpdateTime=2026-10-02 18:12:54`, `versionName=0.1.0-daily`, identical before (18:46) and after the work; it was neither launched nor installed. `tools/capture-pull.sh list` (read only) was run once to learn where the phone session came from. |
| F12 | pass | `git status`: clean. `git log --oneline --decorate -12` in section 6. `main` is still `a173123`; nothing pushed. |

## 3. Key findings

1. **Ad signals are unambiguous but deliberately renamed.** Sponsored edges carry
   `node.th_dat_spo` (type `SponsoredData`), an edge-level number `sposnsor_new_distac_action`,
   a `CometStorySponsoredLabelStrategy` header entry, and usually the
   `StoryAttachmentShareAdStyleRender` attachment style. None appears on any other edge.
   (findings §5)
2. **The feed request chooses the obfuscation.** Flags such as
   `__relay_internal__pv__GHLShouldChangeSponsoredDataFieldNamerelayprovider` (true) in the
   request variables make the server rename ad fields. Names can change with any deploy, so the
   rules also test type names. (§3, §5)
3. **Feed transport:** `text/html` NDJSON, no guard, zstd over HTTP/3. The first line holds
   edge 0, then one `$stream$` line per edge, deferred video ad breaks and dubbing per edge, and
   a final `page_info` line with `is_final`. 5 edges per page. (§1, §3)
4. **The first feed page is in the 4 MB desktop document** as `RelayPrefetchedStreamCache`
   results of `CometModernHomeFeedQuery` (one of the 5 ads was there). The document also carries
   `fb_dtsg`, `fb_dtsg_ag`, `lsd`, the revision fields and 27 preloader `queryID`s. (§10)
5. **Document ids of follow-up queries are not in any recorded response** (36 of 43 used ids
   never appear outside GraphQL). They must come from the script bundles. (§8, §14)
6. **Profile and search data come through `/ajax/route-definition/`**, as `preloader`
   documents, each line behind its own `for (;;);`. The stream filter fails open on 88 of 92
   of these documents today. (§1, §7, §8)
7. **The logged-in mobile site is a socket client:** a WebSocket to `kaios-d.facebook.com`
   carries everything. Recorded HTTP is the document, a service worker script, beacons and media,
   so ads there cannot be removed at the data layer. (§11)
8. **Video is easy to play:** every delivery result has progressive HD and SD MP4 URLs and an
   inline DASH manifest. Most DASH representations are AV1 (7,279), then VP9 (868), H.264 (24).
   (§6)
9. **Numbers are safe to re-serialize** (no integer above 2^53). Bytes are not: `\/` and
   `\uXXXX` escapes change, so only changed documents should be re-serialized. (§13)
10. **Tokens:** `fb_dtsg` lives 24 h (`/ajax/dtsg/`, called by a web worker), `lsd` rides as
    field and `X-FB-LSD` header. A replay also needs `X-FB-Friendly-Name`, `X-ASBD-ID`, the page
    fields, and probably the module bitmaps `__dyn`, `__csr` and others, which only the site's
    JavaScript can compute. (§2, §9)

## 4. Ad rules v1

Signals (paths from a feed edge) and their occurrence by class over 44 recorded edges:

| Signal | Family | Path or test | Sponsored (5) | Suggested (20) | Organic (19) |
|---|---|---|---|---|---|
| sponsored-data | sponsored-data | `node.th_dat_spo` is an object; or `node.sponsored_data` is an object; or any `__typename` `SponsoredData` | 5 | 0 | 0 |
| edge-sponsor-action | edge-placement | `sposnsor_new_distac_action` (or `sponsor_new_distac_action`) is a number | 5 | 0 | 0 |
| sponsored-label-strategy | sponsored-label | `node.comet_sections.context_layout.story.comet_sections.metadata.*.__typename` = `CometStorySponsoredLabelStrategy`, or that type name anywhere, or `__module_component_CometFeedStorySponsoredLabelStrategy_sponsoredLabel` | 5 | 0 | 0 |
| ad-attachment-style | ad-attachment | `node.attachments.*.styles.__typename` = `StoryAttachmentShareAdStyleRender` | 4 | 0 | 0 |
| follow-button (suggestion) | suggested | `CometFeedStoryFollowButtonStrategy` or `…CollabFollowButtonStrategy` | 0 | 19 | 0 |
| recommendation-label (suggestion) | suggested | `CometStoryRecommendationLabelStrategy` | 0 | 1 | 0 |
| preference-bumper (suggestion) | suggested | `IFRBroadTransientPreferenceSignalBumper` | 0 | 2 | 0 |
| showcase-unit (suggestion) | suggested | `node.__typename` = `ShowcaseFeedUnit` | 0 | 1 | 0 |

Decoys: none. On the 39 non-sponsored edges every ad-shaped key is null or false
(`th_dat_spo` null 183 times, `sponsored_data` null 2, `whatsapp_ad_context` null 38,
`is_ad_eligible_for_ad_pod` false 39).

Rule: an edge is an ad when signals of **at least 2 of the 4 families** match. Ad edges are
removed from `data.viewer.news_feed.edges`. A `$stream$` document whose edge is an ad is
dropped, and so is every later document whose `path` runs through a removed edge. A dropped
final document is replaced by `{"extensions":{"is_final":true}}`. Suggestion signals are
reported, never used to remove.

Result over every recorded feed response (enforce mode, NDJSON core): 8 pages and the
document's first page; **5 ads removed** (4 in GraphQL pages, 1 in the document preload), each
with 3 or 4 families. 0 organic or suggested edges flagged, 0 parse failures. The page with a
video ad (`172927` rid 804) dropped 11 documents (the edge, its ad breaks and dubbing
documents) and wrote 1 final marker. The owner reported no count of sponsored posts, so 5 is
the rules' count; it matches a hand check, since every one of the 5 carries every ad signal and
no other edge carries any.

## 5. The mobile site

What the recordings show: while scrolling, opening comments, watching videos and opening a
profile for 155 s, the logged-in mobile site sent the site's own hosts one beacon and one image
request, and no XHR, fetch or document. With a page load, it fetched a 138 KB document (30 inline
scripts, no JSON islands, `weblite` 10 times, `bloks` 21, `WebSocket` 4, `kaios` 2, no `graphql`
or Relay data), opened `wss://kaios-d.facebook.com/ws/<number>` (`101` over HTTP/1.1,
`permessage-deflate`), fetched `/sw` twice without a tab (a service worker), and sent 15 logging
beacons. Feed stories, comments and video references therefore travel as WebSocket frames.

What it means for the plan. Assumption A3 says the long tail uses the mobile site "cleaned of
ads", and M4 plans a "Bloks payload filter". With this client neither works at the data layer.
WebExtensions cannot read or rewrite socket frames, and hooking the page's `WebSocket` would
break the rule that nothing runs in the page's realm. What remains for the logged-in mobile
site: blocking beacons and the service worker by URL, filtering the document, and cosmetic
filtering (CSS hiding through uBlock Origin rules or injected styles), which needs selectors
that follow the site's markup. Alternatives the planner can weigh: accept cosmetic filtering for
the web fallback; use the desktop site as the fallback for surfaces where ads matter (heavy, and
the emulator froze loading it, but a phone did not); or look for another HTTP-based client for
the fallback (out of scope here, and it would need logged-out probing first).

## 6. What was built

Tools (all print names, counts, lengths and structure only):

- `tools/capture-tools.sh` (bundled from `extension/tools/captureToolsCli.ts`): `rescrub`
  (offline layer 1 + layer 2 with the current rules into `captures/<id>-rescrub`, verified),
  `scan` (opaque-value, credential-name and credential-parent scans), `findings` (every number
  of the findings document from re-scrubbed sessions, about 5 s), `keypaths` (where a key occurs
  in the feed edges), `fixtures` (sanitizer; `--census` lists enum candidates), `leakscan` (raw
  values, tokens, e-mails, phones in text files).
- `extension/src/lib/adRules.ts` and `rules/ads-v1.json`: ad rules as data, run as a
  `DocumentRule` by the existing NDJSON filter core.
- Recorder: `formFields` keeps value-less fields as `[name, null]`; `metaLine` keeps a line whose
  extra part throws (`extraError`); redaction rules v5.
- App: `CaptureBrowser.startCapture()` reloads the visible site's page after `capture.start`.
- `tools/capture-summary.mjs`: "Long-lived connections" table. `tools/capture-pull.sh`: names
  `ANDROID_SERIAL` for a phone; no emulator-only assumption in either script (both work from
  `ANDROID_SERIAL` or a session directory).

Tests: `extension/test/captureRecorder.test.ts` (5), `rescrub.test.ts` (3), `fixtures.test.ts`
(57), and the instrumented `CaptureBrowserScreenTest#captureStartReloadsThePageSoItsDocumentIsRecorded`.

Fixtures: `fixtures/` with 83 files (feed pages 1 to 9 with all 5 ads, post with comments, 4
comment pages, notifications 5, video 5, profile header, timeline and tiles, hover card, stories
tray, search typeahead 7 and first results, right column), `manifest.json`, `enum-allowlist.json`
(105 reviewed values), `README.md`.

Documents: `docs/findings/payloads.md` (new), `docs/CAPTURE.md`, `docs/CAPTURE-CHECKLIST.md`,
`docs/SETUP.md`, `docs/ENGINE.md` updated, this report.

The daily build: the owner should update it with `tools/daily-install.sh` (data kept) before
the next capture, on the emulator and on the phone. The update keeps value-less form fields,
reloads the page at capture start, and redacts the M2b keys at the source rather than only in
offline re-scrubs.

Commits (`git log --oneline --decorate -12`, before this report's commit):

```
543893d (HEAD -> m2b-findings) capture-tools leakscan: raw values, identifier tokens, e-mails and phone numbers in text files
e0fabc6 docs: capture format, setup and engine notes for the M2b tools, rules and fixtures
e9f42a7 sanitizer, structure-only fixtures, leak test and fixture tests
715cc34 docs/findings/payloads.md: logged-in payload findings and the M2b gate verdict
9fb60ed findings tooling, ad rules v1, redaction rules v5
91475d1 capture start reloads the current page; summary lists long-lived connections
50bf2aa redaction rules v3, offline re-scrub and opaque-value scan; keep form fields without a value
a173123 (origin/main, origin/HEAD, main) docs: mobile socket confirmed, emulator freeze diagnosed, M2b prompt amended
6a2f7eb docs: record owner capture, add M2b prompt
65a43aa Merge pull request #3 from chabirOael/m2a-capture-tooling
b0ad17d docs: accept M2a, update plan and owner checklist
bd17cf2 docs: add M2a report
```

## 7. Sanitizer design and limits

How structural strings are decided: one classifier, `extension/tools/fixtures/structural.ts`,
shared by the sanitizer and the leak test. Only the following strings are kept:

- type names and `__is*` values, incremental labels, `path` elements, `__dr`/`__jsr` module
  references;
- renderer, strategy, plugin and module names by shape;
- words of the schema (field and type names seen in the GraphQL data);
- the request protocol (field and header names, `/api/graphql/`, values of 11 client-constant
  fields such as `__crn`, `doc_id` and `fb_api_req_friendly_name`);
- ALL_CAPS values from a reviewed list of 105 (`fixtures/enum-allowlist.json`; gender,
  relationship, verification, education, color and opaque values were reviewed out);
- MIME types and codecs, a few lowercase schema constants, and redaction placeholders.

A value that occurs anywhere in the recordings as plain data is never kept, in any position.
Everything else is replaced from a counter, never from a hash, so a guess cannot be confirmed:
URLs become `https://<kind>.example.com/<n>`, identifiers keep length and character class,
text keeps its UTF-16 length, ids are remapped, and times are shifted to 2001. A replacement
of 4+ characters never equals an input value.

What the leak test covers: every response body of the raw sessions (GraphQL, `/ajax/*`, page
document islands, preloaded results), every form field and every URL parameter, in both the
original and the re-scrubbed copies. It collects every non-structural string of 4+ characters,
the identifier tokens inside them, every non-schema key, and every number the sanitizer remaps,
and checks that none occurs as a string, key, token or number in any fixture. It also scans for
e-mails, phone numbers, non-example URLs and the site's host names.

Every way personal data could still reach a fixture:

1. Lengths and shapes are real: a name keeps its length and capitalization pattern, a text its
   length; reaction, comment, share and view counts are real.
2. Relative times are real (differences between timestamps are kept).
3. A text consisting only of a schema word (for example `Video`) is kept as a schema word.
4. An allowlisted ALL_CAPS constant stays verbatim; a personal value that happens to equal one
   would stay too unless it also occurs as plain data (then it is replaced everywhere).
5. Substrings: the test matches whole values, identifier tokens and numbers, not arbitrary
   substrings of kept strings (kept strings are type, module, field and constant names).
6. Object keys that look like schema field names are kept; a map keyed by plain words, such
   as usernames without digits or punctuation, would keep them. None was seen.
7. Structure itself is real: array lengths (number of comments, photos, reactions shown) and
   which optional fields are present (for example that a profile has a certain section).
8. Values the redaction never caught and the classifier wrongly calls structural. The leak test
   cannot see these, since it uses the same classifier. The review of the 166 enum candidates and
   the module-name and vocabulary rules are the guard.

## 8. Deviations

- **The phone session was analysed.** `20261002-183314-site` (desktop with page load) is not
  in the emulator's daily app; it came from the owner's phone. The prompt allows any device.
- **Secret hardening took five passes, not one.** Rules v2 (camel case) were applied and
  re-scrubbed before any body was read. Later scans found more keys: v3 from the phone
  document's credential-like names, and v4/v5 from a parent-key check added after the
  name-based scan missed two values whose own key names are generic. So the first body reads
  (structure only) ran on copies that still held three credential values: a messaging crypto
  auth token, a response-verification token, and anonymous messaging credentials. They were
  found by the scans, never printed or copied, and are redacted in the v5 copies everything
  else was built from.
- **Console exposure during exploration.** One ad-hoc script printed three map keys that were
  route URLs (a public page's name and a story URL with a tracking parameter) to the agent's
  console. Nothing was written to any file. The findings tool now prints any non-identifier
  key as `<key>`.
- **Generic keys in the rules.** v5 redacts the string values of `data` and `encrypted`, and
  this also applies at capture time from now on. GraphQL `data` is never a string, so
  structure is unaffected; 37 string values in the four recordings were affected.
- **Rules v1 run in the NDJSON core in enforce mode, not through `filters.ts`.** The mock
  profile's stream core is wired to the M1 mock rule. Wiring rules v1 into `filters.ts` is M4's
  job.
- **No HTML document fixture.** The page document's data was fixtured as its preloaded results
  (NDJSON), and so were the route definitions' preloads. Hand-sanitizing a 4 MB HTML page with
  1,112 module definitions would risk leaks for little test value.
- **Search results page excluded:** its only response was cut at the 8 MiB cap and is not JSON.
- **The WebSocket handshake test uses the fake WebExtension API**, not the device mock, which
  has no WebSocket endpoint. The reload test runs on the device mock.
- **The leak test does more than asked:** identifier tokens, remapped numbers and non-schema keys,
  and the original sessions as well as the re-scrubbed copies.
- **The leak test passes only because of three rules** that make shared vocabulary consistent
  on both sides. Schema words and module names are kept everywhere, even under text keys. A
  value seen once as plain data is replaced everywhere. The enum allowlist is value-based. Under
  the literal rule ("when in doubt not structural") these words would have been replaced and the
  ad paths broken.
- **Parse repair:** bare layer 2 placeholders (765) are parsed as `0` in all tools and are `0` in
  the fixtures.
- **Fixture size:** 13.5 MB committed.

## 9. Problems and risks

- **Layer 2 breaks JSON where a secret is a number** (the viewer id as a bare number): 765
  places in two recordings. The data is still usable through the repair, but a finalize fix is
  needed (CAPTURE.md "Known defect").
- **Per-line guards on `/ajax/route-definition/`** make the stream filter fail open on 88 of 92
  documents. Profile and search data, including search results' video ad breaks, are not
  filterable until M4 handles them.
- **Document ids of follow-up queries** must come from script bundles (not recorded). This is
  the main M5 risk.
- **Ad field names are client-controlled** and can rotate. Type-name tests cover a rename, a
  schema change would not be covered.
- **Removing a `$stream$` edge leaves an index gap.** Whether Relay renders that correctly, and
  whether dropping the edge's deferred documents is safe, is untested on the real site. The M1
  mock does not model Relay.
- **One new account, one day:** 44 feed edges, 5 ads, 20 suggestions. Profile timeline, search
  and video-tab ads were not seen, and "follow button means suggested" is an inference.
- **The mobile web fallback cannot be filtered at the data layer** (section 5).
- **AV1 dominates DASH representations**; older phones need a software decoder or the
  progressive H.264 fallback.
- **Raw recordings and re-scrubbed copies on disk** hold other people's data (never in git). The
  emulator also keeps an unfinalized 14 MB session from the frozen desktop attempt, which the
  daily app deletes at its next start.

## 10. Questions for the planner

1. Mobile web fallback (section 5): accept cosmetic-only ad hiding for logged-in
   `m.facebook.com` in M3/M4, or re-plan the fallback?
2. Document ids for M5: may a future capture record the bodies of the site's script bundles
   (`static.xx.fbcdn.net/rsrc.php`, about 130 requests per desktop session), or should M5
   fetch the bundles itself at runtime to extract operation ids?
3. Fixtures: keep 13.5 MB in git as is, or trim (for example drop `extensions.sr_payload`,
   which no native screen needs) at the cost of byte-for-byte filter tests on whole responses?
4. Redaction rules v5 redact string values of `data` and `encrypted` at capture time. Accept
   the over-redaction, or prefer a context-aware rule (a new rule kind) in M3/M4?
5. Ask the owner to count "Suggested for you" and "Sponsored" posts during the next capture, so
   that the suggestion markers and the ad count can be confirmed?

## 11. Suggestions for M3, M4, M5 and M6

- **M3:** plan the web fallback without data-layer ad removal on the logged-in mobile site.
  Block `/ajax/weblite_*` beacons and evaluate cosmetic filters. Record a login, with the
  password typed outside the capture, to learn the lifetimes of `xs`, `c_user` and `datr` for
  "login survives restart". Have the owner update the daily build first.
- **M4:** use rules v1 as the starting point and keep type-name tests in every signal. Handle
  per-line guards (route definitions) in the NDJSON core. Add an island transform for
  `RelayPrefetchedStreamCache` entries in the page document (the first page holds ads). Cover the
  right column (`AdsSideFeedUnit`) and in-stream video ad breaks (`instream_video_ad_breaks_comet`).
  Test the index gap on a device before relying on edge removal. Telemetry candidates for the
  tracker modes: `/ajax/bnzai`, `/ajax/comet_error_reports/`, `/video/unified_cvc/`, `/ajax/qm/`,
  `recentVPVs` in feed variables.
- **M5:** read `fb_dtsg`, `fb_dtsg_ag`, `lsd`, the revision fields, the viewer id and 27
  preloader ids from the plain document, and refresh `fb_dtsg` through `/ajax/dtsg/` within 24 h.
  Add `X-FB-LSD`, `X-FB-Friendly-Name` and `X-ASBD-ID`. First test whether `/api/graphql/`
  accepts requests without `__dyn`, `__csr`, `__hsdp`, `__hblp` and `__sjsp`, and with the ad
  field-name flags set to false. Get pagination ids from the script bundles. The feed pages by
  `count` 5 with `cursor` = previous `end_cursor`.
- **M6:** build on the locators of findings §4 and the 13 shapes; key the normalizer on type
  names and strategy names, not on the obfuscated field names. Text ranges are UTF-16 offsets.
  Ads have no timestamp. Video: progressive HD/SD first, then the inline DASH manifest. The
  fixtures keep all of this, with the viewer id as `0` and times in 2001.
- **PLAN text to review:** A3 and M4's "Bloks payload filter" (the logged-in mobile site is a
  socket client), M5's harvest option (document ids are only partly in the document), and
  "Facts verified" (zstd/HTTP/3 logged in too, GraphQL `text/html`, no service-worker answers,
  web worker requests pass the filter).
