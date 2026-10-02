# twinBook engine

How the browser engine layer works: the `:engine` module (GeckoView), the twin-bridge
WebExtension, the bridge between them, the streaming filters and the mock server used to test
all of it. Written for an agent with no prior context. Build and run instructions are in
`docs/SETUP.md`; the plan is in `docs/PLAN.md`.

## 1. The idea in one paragraph

The site's web client detects hooks on `XMLHttpRequest`, `fetch` and `JSON.parse` inside the
page. So twinBook never touches the page's JavaScript realm. It filters responses one layer
below, in the network stream: the twin-bridge extension's background script attaches a
`webRequest.filterResponseData` stream filter to selected responses. The page receives
already-filtered bytes and sees only untouched native functions. The app (Kotlin) talks to the
extension over one native messaging port.

```
 app (Kotlin)                                  GeckoView runtime
 +-----------------------------+               +-------------------------------------+
 | Engine (one per process)    |  native port  | twin-bridge background script       |
 |   Bridge  <-----------------+---------------+-> BridgeClient                       |
 |   EngineSession (tab) ...   |  JSON msgs    |   filters.ts   webRequest + StreamFilter
 |   CaptureRecorder -> store  |               |   capture.ts   capture recorder     |
 +-----------------------------+               |   recorder.ts  mock GraphQL fields  |
                                               |   replay.ts    fetch for the app    |
                                               | anchor.js (content script, anchor   |
                                               |   page only, no site JS there)      |
                                               +-------------------------------------+
```

## 2. Layout

```
engine/src/main/kotlin/.../engine/
  Engine.kt            runtime, extension install and recovery, sessions, tracking protection
  EngineSession.kt     one tab: profile (UA), load, back, reload, attach/detach a GeckoView,
                       page state, navigation safety, permission denial, prompt delegate
  GeckoResults.kt      GeckoResult.await()
  bridge/Bridge.kt     app side of the bridge protocol (no GeckoView dependency)
  capture/CaptureRecorder.kt  capture sessions: capture.start/stop/discard, capture.write
capture/                 pure JVM module: CaptureStore, TaintScrubber, Finalizer (docs/CAPTURE.md)
engine/src/androidTest/   instrumented gate tests and probes (see section 8)
engine/build.gradle.kts   also builds extension/ and packages it into the module's assets
extension/
  manifest.json        base manifest; build.mjs stamps the version (section 7)
  src/background.ts    wires everything; filter modes; diagnostic handlers
  src/anchor.ts        anchor-page content script (replay path B, mock only)
  src/config.ts        GraphQL path, native app name, build marker
  src/filters.ts       webRequest wiring of the stream filters and body taps (section 5)
  src/capture.ts       capture recorder: metadata listeners, bodies, capture.* handlers
  src/recorder.ts      M1 request reporter (mock GraphQL POSTs only)
  src/replay.ts        replay executor (paths A and B), header rewrite; mock hosts only
  src/lib/             pure library code, unit-tested with Vitest:
    bridge.ts          BridgeClient (extension side of the protocol)
    ndjsonFilter.ts    streaming NDJSON filter core
    htmlIslandFilter.ts streaming filter for <script type="application/json"> islands
    mockAdRule.ts      the M1 mock ad rule (replaced by the rule engine in M4)
    adRules.ts         ad rules as data (rules/ads-v1.json, M2b proposal) as a DocumentRule
    profiles.ts        site profiles (mock, site) and filter modes
    probe.ts           observe-only probe rule for the real site
    redact.ts          layer 1 redaction; taint.ts layer 2 (docs/CAPTURE.md section 5)
    record.ts          record helpers, body recorder; captureTransport.ts lossless transport
    formFields.ts      form-field extraction for the M1 reporter
    bytes.ts, replayResult.ts, jsonGuard.ts
  data/                redaction-rules.json, probe-keys.json (one data file each)
  tools/               HAR importer (Node; tools/har-import.sh); offline capture tools
                       (tools/capture-tools.sh): re-scrub, scan, findings, fixtures
  test/                Vitest tests (fakeBrowser.ts: a fake WebExtension API for wiring tests)
  lint.mjs, lint-allowlist.json   web-ext lint policy
mockserver/            pure JVM module: the hermetic mock of the site's traffic shape
app/                   start screen, engine lab (EngineLab*.kt, assets/lab/), capture browser
                       (CaptureBrowser*.kt, Prompts.kt), TwinBookApp, AppEngine
```

## 3. The `:engine` API

All of it is in package `io.github.chabiroael.twinbook.engine`. GeckoView calls must happen on
the main thread; functions documented "Main thread only" enforce nothing, they just require it.
Suspend functions switch to the main thread themselves where needed.

### Engine

```kotlin
val engine = Engine.start(context, EngineConfig(debug = BuildConfig.DEBUG))   // main thread
val ready: ReadyInfo = engine.awaitReady()       // extension installed and bridge connected
val session = engine.newSession(UserAgentProfile.DESKTOP, "data-twin")       // main thread
engine.setTrackingProtection(TrackingProtection.STRICT)                     // main thread
```

| Member | What it does |
|---|---|
| `Engine.start(context, config)` | Creates the one `GeckoRuntime` of the process, installs twin-bridge, connects the bridge. Later calls return the same engine; the first config wins. |
| `Engine.getOrNull()` | The running engine, if any. |
| `Engine.GECKOVIEW_VERSION` | e.g. `157.0 (20260924084938)`. |
| `runtime` | The `GeckoRuntime`, for anything not wrapped. |
| `bridge` | The [Bridge](#bridge). |
| `extension: StateFlow<ExtensionState>` | `Installing`, `Installed(id, version, webExtension)` or `Failed(message)`. |
| `awaitReady(timeoutMs)` | Suspends until installed and connected (hello received). Returns `ReadyInfo(extensionId, installedVersion, reportedVersion, marker)`. Throws on install failure or timeout. |
| `newSession(profile, name)` | Opens an `EngineSession`. Headless until attached. |
| `setTrackingProtection(level)` | `DEFAULT` restores GeckoView's defaults; `STRICT` sets ETP strict, anti-tracking STRICT, strict social tracking protection, cookie behaviour 5. New sessions get `useTrackingProtection = (level == STRICT)`. |
| `describeContentBlocking()`, `describeDefaultContentBlocking()` | Current and initial settings as JSON. |
| `timings: StateFlow<EngineTimings>` | `processStartElapsed`, `engineStartElapsed`, `extensionInstalledElapsed`, `bridgeConnectedElapsed`, `extensionReinstalled` (elapsedRealtime clock). |
| `scope` | Main-thread coroutine scope for engine work. |

`EngineConfig(debug, extensionLocation, extensionId, nativeApp, backgroundStartTimeoutMs,
configureRuntime)`: `debug = true` sends web console output to logcat (tag `GeckoConsole`) and
enables remote debugging. `configureRuntime` can adjust the `GeckoRuntimeSettings.Builder`.

### EngineSession

| Member | What it does |
|---|---|
| `profile` | `MOBILE` (GeckoView mobile UA and viewport) or `DESKTOP` (desktop UA and viewport). Fixed at creation. |
| `geckoSession` | The underlying `GeckoSession`. |
| `page: StateFlow<PageState>` | `url`, `title`, `loading`, `progress`, `loadCount` (finished loads), `lastLoadSucceeded`, `firstContentfulPaint`, `crashed`. |
| `load(url)` | Main thread only. |
| `loadAndWait(url, timeoutMs)` | Loads and suspends until the load stops (first waits for a new session's initial about:blank stop, see quirks). |
| `attach(view)` / `detach()` | Show in / remove from a `GeckoView`. Detached sessions keep running. |
| `isHeadless` | True when not attached. |
| `userAgent()` | The UA string this session sends. |
| `goBack()`, `reload()` | Main thread only. `page.canGoBack` / `canGoForward` follow GeckoView. |
| `promptDelegate` | JavaScript dialogs and other prompts (null dismisses them). |
| `close()` | Main thread only. |

`page.url` comes from location changes only (Gecko reports a page start even for a load the
session then denies); `page.loadingUrl` is the last load start.

Navigation safety, in every session: only `http`, `https`, `about`, `resource`, `data` and
`blob` load (`EngineSession.ALLOWED_SCHEMES`); anything else, such as `fb://` or `intent://`, is
denied and never leaves the app (`page.blockedLoads`, `page.lastBlockedScheme`). A request for
a new window (`target=_blank`, `window.open`) loads in the same session
(`page.newWindowsInPlace`). Every content, Android and media permission request is denied
(`page.permissionsDenied`).

Every session is kept *active* (`setActive(true)`), also when headless, so its timers and
network run at full speed. Measured: a 100 ms interval in a headless session ticked 29 times in
3 s, max gap 103 ms.

All sessions share one cookie jar (one runtime, no `contextId`, no private mode).

### Bridge

```kotlin
engine.bridge.send("name", JSONObject())                          // event to the extension
val result: JSONObject = engine.bridge.request("method", params)  // request, suspends
engine.bridge.handle("app.method") { params -> JSONObject() }     // answer extension requests
engine.bridge.events.collect { event -> }                         // events from the extension
engine.bridge.state                                               // Connected / Disconnected
engine.bridge.extension                                           // hello's extension object
```

- `send` and `request` work at any time, from any thread. While disconnected, messages are
  queued and flushed in order after the next welcome.
- `request` throws `BridgeException(code, message)`: `timeout` (default 30 s), `disconnected`
  (port died after the request was sent), `no_handler`, `handler_error`, or the code the
  extension's handler threw.
- A handler returns a `JSONObject` (or any value, wrapped as `{"value": ...}`). Throw
  `BridgeException` to answer with a specific error code.
- A request from the extension for a method with no handler waits until one is registered.
- `events` replays the last 64 events to new collectors, and a slow collector can miss events
  (buffer 1024, oldest dropped). Match events on content, not on arrival, and never use events
  for data that must not be lost: capture records travel as `capture.write` requests.
- Key order of JSON objects is not preserved across the bridge (GeckoView converts through
  `GeckoBundle`). Values are.

## 4. Bridge protocol

One native messaging port, opened by the background script with
`browser.runtime.connectNative("twinbook")`. Every message is a JSON object with a `type`.

| type | Direction | Fields | Meaning |
|---|---|---|---|
| `hello` | ext → app | `protocol` (1), `extension` {`id`, `version`, `marker`, `startedAt`} | First message on every port. Re-sent every 500 ms until welcomed; after 20 tries the port is replaced. |
| `welcome` | app → ext | `protocol`, `engine` {`geckoview`, `trackingProtection`} | Answer to hello. A repeated hello gets another welcome but queued messages are flushed only once. |
| `event` | both | `name`, `data` | Fire and forget. |
| `request` | both | `id`, `method`, `params` | Ids are `k<n>` from the app and `e<n>` from the extension. |
| `response` | both | `id`, `ok: true`, `result` | |
| `response` | both | `id`, `ok: false`, `error` {`code`, `message`} | |

Ordering and queueing:

- The extension queues events and requests until welcome; the app queues until hello. Both
  flush in order. Requests time out while queued too (30 s default, both sides).
- On disconnect, requests sent on that port fail with `disconnected`; unsent messages stay
  queued; the extension reconnects with backoff 100 ms ... 5 s.

### Requests the extension answers

| Method | Params | Result |
|---|---|---|
| `extension.info` | | `id`, `version`, `marker`, `startedAt`, `permissions` |
| `filter.setEnabled` | `enabled` | `enabled`. M1 switch: the mock profile in `enforce` (true) or `off`. |
| `filter.setMode` | `profile` (`mock` \| `site`), `mode` (`enforce` \| `observe` \| `off`) | `profile`, `mode`; error `mode_not_allowed` for `site` + `enforce` |
| `filter.describe` | | `modes`, `siteListening`, `profiles` (patterns, allowed modes, replay, rewrite, rule) |
| `capture.start` | `sessionId`, `profiles` | `startedAt`, `extensionStartedAt`, `formatVersion`, `limits`; error `already_capturing` |
| `capture.stop` | `sessionId` | after every record is acknowledged: `ok`, `counters`, `redactions`, `transport`, `errorSamples`, `secrets` [{`value`, `label`}] (memory only, for layer 2) |
| `capture.discard` | `sessionId` | `discarded` |
| `capture.status` | | `active`, `sessionId`, `counters`, `transport` |
| `storage.get` | `keys` (array or null) | `items` from `storage.local` |
| `storage.set` | `items` | `stored` (keys) |
| `replay.configure` | `rewrite`: null or {`origin`, `referer`, `userAgent`} | Header rewrite for path A requests |
| `replay.fetch` (mock hosts only, else error `forbidden_host` before anything is sent) | `via`: `background` \| `anchor` \| `anchor-extension-fetch`; `request` {`url`, `method`, `headers`, `body`, `credentials`} | `via`, `status`, `statusText`, `url`, `headers`, `body` (text), `sentHeaders` (as Gecko reported them in onSendHeaders, only for URLs containing `twinbook_probe=`) |
| `replay.anchors` | | `count`, `urls` of connected anchor pages |
| `diag.echo` | anything | the params |
| `diag.notes` | | `notes`: every `diag.note` event received |
| `diag.emit` | `name`, `count`, `data` | emits `count` events `name` with `data` + `seq` |
| `diag.callApp` | `method`, `params` | `result` of the extension calling the app |
| `diag.early` | | outcome of the `engine.info` request the extension sends at startup |

`diag.*` exist for the instrumented tests; they only echo and count.

### Requests the app answers

| Method | Result |
|---|---|
| `engine.info` | `geckoview`, `trackingProtection`. The extension calls it at startup, before the port exists. |
| `capture.write` | params `sessionId`, `seq`, `items` ([{`l`: line} or {`f`: file, `b`: base64, `last`}]), `counters`. Answered `{written, seq}` once on disk; a repeated `seq` is ignored; an unknown session answers `{dropped: true}`. |

### Events from the extension

| Name | Data |
|---|---|
| `bridge.startup` | extension info; emitted at background start, before the port exists |
| `filter.stats` | `requestId`, `url` (layer 1 applied for the site), `profile`, `mode`, `kind` (`ndjson` \| `document`), `chunks`, `coreFailed`, `busyMs` (time spent in the filter), `elapsedMs`, `firstDataMs`, `stats` (counters, see section 5) |
| `filter.error` | `requestId`, `url`, `kind`, `error` {`kind`: `utf8` \| `parse` \| `rule` \| `internal` \| `attach` \| `stream`, `index`, `message`, `sample`} |
| `recorder.request` (mock only, values not redacted) | `requestId`, `url`, `method`, `type`, `documentUrl`, `timeStamp`, `source` (`formData` \| `raw` \| `none`), `fieldNames` (all, in order), `fields` {`fb_api_req_friendly_name`, `doc_id`, `variables`, `fb_dtsg`, `lsd`, `jazoest`, `__rev`, `__req`} |
| `anchor.ready`, `anchor.gone` | `url` |

### Events the extension listens to

| Name | Use |
|---|---|
| `diag.note` | recorded for `diag.notes` (tests) |

## 5. Filters

### Which requests: site profiles and modes

Site profiles (`src/lib/profiles.ts`) decide what twin-bridge may do where:

| Profile | Own hosts | Modes | Replay, header rewrite | Content script | Listeners |
|---|---|---|---|---|---|
| `mock` | `127.0.0.1`, `localhost` | enforce (default), observe, off | yes | anchor page only | always |
| `site` | `facebook.com`, `*.facebook.com` | observe (default), off | no | none | only while a capture of the site runs |

Host permissions in the manifest: the mock and site patterns, plus `*://*/*` so request metadata
of third parties contacted by the site's pages can be recorded (metadata listeners only, none of
them blocking).

Modes: `enforce` writes the core's output (M1). `observe` writes the original bytes first,
unchanged, then gives a copy to the core, whose decisions are counted and reported
(`filter.stats`, and an `observe` capture line) and whose output is discarded; the cores also
skip re-serialization in observe mode. `off` attaches no core.

One StreamFilter ("tap") per response at most, carrying a core and/or a capture body recorder:

- Mock GraphQL (`xmlhttprequest`, path `/api/graphql/`): attached in a blocking
  `onBeforeRequest`, NDJSON core with the mock ad rule.
- Documents (`main_frame`, `sub_frame`, `text/html`) of a profile's own hosts: attached in a
  blocking `onHeadersReceived`, HTML island core (mock ad rule or probe).
- Site XHR and fetch responses with a textual content type: attached in `onHeadersReceived`,
  NDJSON core with the probe, always observe. `filterResponseData` works from
  `onHeadersReceived` for XHR too (the capture tests record 200 XHR bodies that way).
- Any response whose body the running capture wants (docs/CAPTURE.md section 2.2).
- Never: responses with status 1xx, 3xx or 204; the extension's own requests (origin
  `moz-extension://`).

The blocking `onHeadersReceived` listener always returns `{}`: it never cancels, redirects or
changes headers. The only listener that changes anything is replay's `onBeforeSendHeaders`,
registered for the mock's hosts and acting only on the extension's own requests.

Gecko hands the stream filter the decoded body: with `Content-Encoding: gzip` the filter sees
plain bytes (verified with the `gzip` scenario: `bytesIn` equals the uncompressed size), and
the real site's `zstd` responses over HTTP/3 arrive decoded too (M2a capture).

### NDJSON stream filter (`src/lib/ndjsonFilter.ts`)

`new NdjsonStreamFilter(rule, { onError })`, then `push(chunk) -> bytes` per chunk and
`end() -> bytes` once. `rule(doc, index)` returns `KEEP`, `DROP` or
`{ action: "replace", value }`.

Guarantees, each covered by Vitest tests and by device tests:

- Splits on the newline byte, which never occurs inside a multi-byte UTF-8 sequence, so chunk
  boundaries may fall anywhere: inside a line, inside a character, inside the guard. Tested
  with a cut at every byte offset, random multi-cuts and one byte per chunk.
- Prompt output: a line is decided and emitted in the same `push` that completes it; only the
  unfinished tail is held. The whole response is never held.
- Kept lines are forwarded byte for byte, never re-serialized. Only replaced lines are
  serialized (`JSON.stringify`).
- Blank lines pass unchanged and are not documents. CRLF endings are preserved, also on
  replaced lines. A last line without newline is flushed by `end()`.
- A `for (;;);` or `for(;;);` guard (after optional BOM and whitespace) before the first
  document is preserved, also when the first document is replaced or dropped (the guard then
  prefixes the next document). A guard on a later line is not a guard (that line fails open).
- Fail open: invalid UTF-8, invalid JSON, a rule that throws, or a replacement that cannot be
  serialized: the original line passes unchanged, `onError` reports it, filtering continues
  with the next line. An unexpected internal error switches to pass-through for the rest of the
  stream without losing or reordering bytes (M2a fixed a case where the bytes of the line being
  processed were lost; covered by a test that forces such a throw at every chunk boundary). In
  `filters.ts` an exception around the core writes the chunk unchanged and stops using the core
  for that response; the tap keeps passing bytes (and recording, if a capture wants the body).
- Observe mode (`{ observe: true }`): decisions are counted, every line is forwarded unchanged,
  replacements are not serialized.
- Stats: `bytesIn`, `bytesOut`, `documents`, `kept`, `dropped`, `replaced`, `failedOpen`,
  `guard`, `passThrough`.

Cost measured on the emulator: a 5.9 MB, 5,001-line response took 26 to 68 ms of filter time
(`busyMs`), and the page got it in a median 194 ms with the filter on versus 148 ms off.

### Document filter (`src/lib/htmlIslandFilter.ts`)

Works on bytes. Everything outside `<script type="application/json" ...>` islands passes byte
for byte as soon as it cannot be the start of an island. An island is held until its closing
tag, parsed, handed to the transform, and re-serialized only if the transform changed it
(`<` escaped as `<` so the JSON can never close its script element). Fail open as above.
Islands larger than 32 MiB, or unterminated at the end, pass through unparsed.

### Ad rules v1 (`src/lib/adRules.ts`, `rules/ads-v1.json`)

The M2b proposal for M4, not wired into `filters.ts` yet. `createAdRule(rules)` returns a
`DocumentRule` for one response: an edge is an ad when signals of at least `minFamilies`
families match; ad edges are removed from `data.viewer.news_feed.edges`, a `$stream$` document
whose edge is an ad is dropped, documents whose `path` runs through a removed edge are dropped,
and a dropped final document becomes `{"extensions":{"is_final":true}}`. Signals and the
evidence for them are in docs/findings/payloads.md section 5; the fixture tests run the rules
over the recorded feed pages.

The real site's `/ajax/route-definition/` responses put a `for (;;);` guard in front of every
line; the core accepts a guard only before the first document, so those lines fail open (M2b
finding; M4 must handle it before filtering profile and search data).

### Mock rule (`src/lib/mockAdRule.ts`)

An edge whose `node.mock_sponsored === true` is an ad. Ad edges are removed from every `edges`
array of a document (and of document islands). An incremental document (one with a `path`
array) whose `data` is an ad edge is dropped; if it was final (`extensions.is_final`), it is
replaced by `{"extensions":{"is_final":true}}`. M4 replaces this file with the rule engine;
the filter core does not change.

### Integrity guarantee

No content script and no injected script touch any page except the anchor page. The test page
(`mockserver/.../www/test-page.js`) verifies 95 checks while filtering is active: the
functions it relies on stringify as native code in its realm and through a fresh iframe's
`Function.prototype.toString`, are unchanged since page start, have the same descriptors,
names and lengths as the iframe's copies; `toString` identities hold; no extra or missing
globals compared with the iframe; no injected element or script; no extension URL in the DOM;
no foreign resource loaded.

## 6. Replay paths (probed in M1 for M5)

- Path A, `via: "background"`: `fetch()` from the background script with
  `credentials: "include"`. A blocking `onBeforeSendHeaders` listener can rewrite `Origin`,
  `Referer` and `User-Agent` of the extension's own requests (`replay.configure`).
- Path B, `via: "anchor"`: `content.fetch()` in the anchor page, a same-origin page with no
  scripts, loaded in a headless session. The anchor content script refuses to run if the page
  has any `<script>` element, connects a runtime port named `anchor` to the background, and
  executes requests it receives. `content.fetch` must be called as a method of `content`.
- `via: "anchor-extension-fetch"` is the content script's own `fetch` (extension principal),
  kept only for comparison.

The observations from the mock are in `docs/reports/M1.md` section 3.

## 7. Extension versions and updates

Gecko records a built-in extension by ID and version. `GeckoViewWebExtension.ensureBuiltIn`
reinstalls only when the packaged version string differs from the installed one (it compares
with `!=`, so a lower version also reinstalls). An unchanged version means a changed extension
in a new APK would be ignored.

So `extension/build.mjs` stamps every build: `manifest.json` holds `x.y.z`, and `dist/` gets
`x.y.z.N` where N is derived from a SHA-256 of the manifest and both bundles (1 to 268435456,
no leading zero). Same content gives the same version (Gradle up-to-date checks keep working);
any change gives a new one. `-Ptwinbook.extensionMarker=<text>` (Gradle) or
`TWIN_BRIDGE_MARKER` (npm) compiles a marker into the bundle, which the extension reports over
the bridge; `tools/extension-update-test.sh` uses it to build a changed extension.

Packaging: `:engine:buildTwinBridge` runs `npm run build` in `extension/`;
`:engine:twinBridgeAssets<Variant>` copies `dist/` into the module's generated assets under
`extensions/twin-bridge/`. The app gets the files through the library merge, exactly once
(checked with `unzip -l`).

## 8. Mock server and tests

`mockserver/` is a pure JVM Kotlin module: an HTTP/1.1 server on `127.0.0.1`, random port,
thread per connection, `Connection: close` on every response, `Cache-Control: no-store`. It
runs inside the instrumented test process on the device (`androidTestImplementation`) and in
JVM unit tests. Every request is recorded (`server.requests`), streamed responses record the
time each chunk was flushed (`server.streamTraces`), and pages post results to
`/report?run=<id>` (`server.awaitReport(run)`).

| Endpoint | Behaviour |
|---|---|
| `POST /api/graphql/?scenario=<name>&run=<id>&transport=<t>` | Streams the scenario as chunked NDJSON. |
| `GET /test.html?run=&scenario=&transports=xhr,fetch&integrity=1&timers=<ms>&summary=1` | The test page. |
| `GET /static/test-page.js` | Its script. |
| `GET /document?run=<id>` | HTML with two JSON islands (the first contains ad edges); its inline script reports the islands' text. |
| `GET /session/set-cookies?prefix=<p>&value=<v>[&maxAge=<s>]` | Sets `<p>_plain`, `_httponly`, `_lax`, `_strict`, `_none` (SameSite=None; Secure). |
| `GET\|POST /session/echo[?pad=<n>]` | JSON with method, path, query, headers, cookies, body, and `payload` = `MockServer.payload(n)`. |
| `GET /anchor` | The replay anchor: no scripts. |
| `GET /blank.html` | Empty page for same-origin iframes. |
| `POST /report?run=<id>` | Stores the body for `awaitReport`. |
| `POST /log?run=<id>&field=<f>` | Stores the body; `server.logs(run)`, `server.lastLog(run, field)`. |
| `GET /secrets/page?run=<id>` | Plants `MockSecrets(run)`: cookies with attributes, the dtsg token and user id unkeyed in the HTML; its script posts the token as `fb_dtsg` (with `lsd`, `jazoest`), fetches `/secrets/json` (cookie values echoed in JSON), posts to the Bloks-shaped endpoint, reports. |
| `GET\|POST /async/wbloks/fetch/` | One JSON document behind `for (;;);`, `application/x-javascript`. |
| `GET /login.html`, `GET /nav.html` | Login-like form; custom scheme, intent, new-window, permission and dialog triggers. Both log element positions (`layout`) and results to `/log`. |
| `GET /bulk?i=<n>&size=<bytes>`, `GET /bulk.html?n=&size=&big=&par=` | Deterministic bodies of exact size; a page that fetches `n` of them. |

Every recorded request now also carries `responseStatus`, `responseSha256` (of the content as
served, before gzip) and `responseLength`.

Scenarios (`Scenarios.kt`): `ads-first`, `ads-middle`, `ads-last`, `ads-all`, `no-ads`,
`no-guard`, `split-line` (cuts every 37 bytes), `split-utf8` (cuts 1 to 3 bytes into
multi-byte characters), `gzip` (sync-flushed gzip), `malformed`, `slow` (400 ms between
lines), `large` (5.9 MB, 16 KB chunks). Each carries `expectedText`, the exact body the page
must receive after filtering. The mock's `Json` writer produces the same bytes as
`JSON.stringify`, so even the re-serialized first document is predicted exactly.

### Adding a scenario and a test

1. In `Scenarios.kt`, add a `feed(...)` call to the `byName` list (choose ad positions, cut
   strategy, delay, gzip, guard). `expectedText` is computed for you. For a different shape,
   build a `Scenario` directly and compute `expectedText` by hand.
2. Add a JVM test in `mockserver/src/test` if the scenario has new mechanics.
3. In `engine/src/androidTest/.../StreamFilterTest.kt`, add a test calling
   `runScenario("<name>")`; it loads the test page in a headless session, waits for the
   report, and asserts that XHR and fetch both received `expectedText` exactly.
4. For filter logic, add Vitest cases in `extension/test/` first; they run in `tools/check.sh`.

### Test classes in `engine/src/androidTest`

| Class | Covers |
|---|---|
| `BridgeTest` | G3: requests and events both ways, errors, 1 MB, messages sent before the other side is ready |
| `StreamFilterTest` | G4 to G9, G11 to G13 |
| `DocumentFilterTest` | G10 |
| `SessionsTest` | G14 |
| `ReplayProbeTest` | G17, G18 (observations, logged with tag `twinbook-evidence`) |
| `ObserveModeTest` | C3: every scenario in observe mode gives the page the unfiltered input with the same decisions; site enforce and replay refused |
| `CaptureRecorderTest` | C4 to C6, C8: recorder completeness against the server's own log, layer 1 at the source, layer 2 at finalize, 200 responses with a 5.5 MB one; capture overhead |
| `PersistenceProbe` | G15, G16 phases; skipped unless `-e persistPhase` is given |
| `MeasurementProbe` | memory; skipped unless `-e measure 1` is given |

App tests (`app/src/androidTest`): `EngineLabScreenTest` (M1), `CaptureBrowserScreenTest`
(C10: text input through an input method and through key events, custom schemes and intents,
new windows, permissions, dialogs, back, reload, site switch), `CaptureProbe` (`@ManualProbe`,
run by `tools/capture-kill-test.sh` and for `tools/capture-pull.sh` checks).

`TestEngine` (in `TestSupport.kt`) holds the shared engine and server, and a `CaptureRecorder`
writing to the test app's `files/captures-test/`. Right after starting the
engine it sends an event and a request over the bridge, before the extension exists, and
records every bridge event from the first one.

## 9. GeckoView quirks met in M1 and M2a

1. **Background scripts of an installed extension start late.** GeckoView starts them on
   `extensions-late-startup`, which `geckoview.js` sends when the first session window opens
   (through `DelayedInit`, at most 15 s). A fresh install starts the background at once; a
   normal start does not until a session exists. `Engine` therefore opens a bootstrap session
   at start and closes it when the bridge connects. Until the background runs, webRequest
   listeners are not registered: load site pages only after `awaitReady()`.
2. **Add-on startup state can be lost.** Gecko writes `addonStartup.json.lz4` a moment after an
   install. If the process dies first (seen: killed about 1 s after install), the next start
   finds the add-on in `extensions.json` with the same version, so `ensureBuiltIn` does
   nothing, yet never starts it. `Engine` detects a bridge that does not connect within 10 s
   (`backgroundStartTimeoutMs`) and calls `installBuiltIn`; the background then starts within
   100 to 200 ms. storage.local survived the reinstall in the tests.
3. **Messages before delegates.** `WebExtensionController` queues `connectNative` until the app
   calls `setMessageDelegate` (`releasePendingMessages`). Port messages are dispatched to a
   per-port `EventDispatcher` (`port:<id>`); the app sets the port delegate inside
   `onConnect`. The extension re-sends hello until welcome in case a message is posted before
   that.
4. **JSON key order** is not preserved across the native port (GeckoBundle).
5. **`GeckoSession.getUserAgent()` must be called on the main thread** (it asserts a Handler
   thread). GeckoResult callbacks are delivered on the registering thread's Looper.
6. **Initial about:blank.** Every new session reports a page start and stop for about:blank,
   which can arrive after a load issued right after `open()`. `loadAndWait` waits for it.
7. **Default content blocking** in GeckoView 157 (no settings passed): ETP level 0 (none),
   anti-tracking categories 46 (`DEFAULT`), cookie behaviour 5
   (`ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS`), strict social tracking protection off, cookie
   purging off. `GeckoSessionSettings.useTrackingProtection` defaults to false.
8. **Process model.** With the runtime started and no session, five processes run: main,
   crash helper, GPU, and two content (`:tab`) processes. A visible session adds a third
   content process. A headless session on the same origin reuses an existing content process
   (no measurable PSS increase). Closing a session does not end its content process at once.
9. **Content scripts:** `content.fetch` must be called as `content.fetch(...)`; a detached
   reference throws. Match patterns ignore ports.
10. **Closing a session right after its first load** logs a harmless JavaScript error from
    `GeckoViewSessionStore.sys.mjs` (`win is null`).
11. **Gecko accepts session cookies set over plain `http://127.0.0.1`** including
    `SameSite=None; Secure` (loopback counts as a secure context). Session cookies (no expiry)
    do not survive a process restart; persistent ones do.
12. **Lint:** `web-ext lint` flags `geckoViewAddons` as an unknown permission (allowlisted in
    `extension/lint-allowlist.json`). `nativeMessaging` is not flagged.
13. **Denied loads still start.** For a link to a custom scheme, GeckoView reports
    `onPageStart` with that URL before `onLoadRequest` is answered, then `onPageStop`; no
    location change follows. `PageState.url` therefore follows `onLocationChange` only.
14. **Set-Cookie** headers arrive in webRequest as one header whose value joins the cookies
    with newlines.
15. **Prompts:** `dismiss()` and `confirm()` mark a prompt complete themselves and throw if
    called twice; complete the `GeckoResult` exactly once.
16. **Real site, logged out (M2a):** responses come over HTTP/3 with `zstd` encoding and reach
    the stream filter decoded. No custom-scheme navigation was attempted in the M2a loads.
