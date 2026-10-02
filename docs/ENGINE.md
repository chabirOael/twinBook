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
 +-----------------------------+               |   recorder.ts  GraphQL form fields  |
                                               |   replay.ts    fetch for the app    |
                                               | anchor.js (content script, anchor   |
                                               |   page only, no site JS there)      |
                                               +-------------------------------------+
```

## 2. Layout

```
engine/src/main/kotlin/.../engine/
  Engine.kt            runtime, extension install and recovery, sessions, tracking protection
  EngineSession.kt     one tab: profile (UA), load, attach/detach a GeckoView, page state
  GeckoResults.kt      GeckoResult.await()
  bridge/Bridge.kt     app side of the bridge protocol (no GeckoView dependency)
engine/src/androidTest/   instrumented gate tests and probes (see section 8)
engine/build.gradle.kts   also builds extension/ and packages it into the module's assets
extension/
  manifest.json        base manifest; build.mjs stamps the version (section 7)
  src/background.ts    wires everything; diagnostic handlers
  src/anchor.ts        anchor-page content script (replay path B)
  src/config.ts        target URL patterns, GraphQL path, native app name, build marker
  src/filters.ts       webRequest wiring of the stream filters
  src/recorder.ts      request recorder
  src/replay.ts        replay executor (paths A and B), header rewrite
  src/lib/             pure library code, unit-tested with Vitest:
    bridge.ts          BridgeClient (extension side of the protocol)
    ndjsonFilter.ts    streaming NDJSON filter core
    htmlIslandFilter.ts streaming filter for <script type="application/json"> islands
    mockAdRule.ts      the M1 mock ad rule (replaced by the rule engine in M4)
    formFields.ts      form-field extraction for the recorder
    bytes.ts, replayResult.ts, jsonGuard.ts
  test/                Vitest tests
  lint.mjs, lint-allowlist.json   web-ext lint policy
mockserver/            pure JVM module: the hermetic mock of the site's traffic shape
app/                   engine lab screen (EngineLab.kt, EngineLabScreen.kt, assets/lab/)
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
| `close()` | Main thread only. |

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
- `events` replays the last 64 events to new collectors. Match events on content (for example
  a URL with a unique id), not on arrival.
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
| `filter.setEnabled` | `enabled` | `enabled`. Filtering is on by default; off is for measurements. |
| `storage.get` | `keys` (array or null) | `items` from `storage.local` |
| `storage.set` | `items` | `stored` (keys) |
| `replay.configure` | `rewrite`: null or {`origin`, `referer`, `userAgent`} | Header rewrite for path A requests |
| `replay.fetch` | `via`: `background` \| `anchor` \| `anchor-extension-fetch`; `request` {`url`, `method`, `headers`, `body`, `credentials`} | `via`, `status`, `statusText`, `url`, `headers`, `body` (text), `sentHeaders` (as Gecko reported them in onSendHeaders, only for URLs containing `twinbook_probe=`) |
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

### Events from the extension

| Name | Data |
|---|---|
| `bridge.startup` | extension info; emitted at background start, before the port exists |
| `filter.stats` | `requestId`, `url`, `kind` (`ndjson` \| `document`), `chunks`, `busyMs` (time spent in the filter), `elapsedMs`, `firstDataMs`, `stats` (counters, see section 5) |
| `filter.error` | `requestId`, `url`, `kind`, `error` {`kind`: `utf8` \| `parse` \| `rule` \| `internal` \| `attach` \| `stream`, `index`, `message`, `sample`} |
| `recorder.request` | `requestId`, `url`, `method`, `type`, `documentUrl`, `timeStamp`, `source` (`formData` \| `raw` \| `none`), `fieldNames` (all, in order), `fields` {`fb_api_req_friendly_name`, `doc_id`, `variables`, `fb_dtsg`, `lsd`, `jazoest`, `__rev`, `__req`} |
| `anchor.ready`, `anchor.gone` | `url` |

### Events the extension listens to

| Name | Use |
|---|---|
| `diag.note` | recorded for `diag.notes` (tests) |

## 5. Filters

### Which requests

`src/config.ts`: webRequest listeners cover `http://127.0.0.1/*` and `http://localhost/*`
(match patterns ignore the port). M2 adds the real site here and in the manifest's host
permissions. Requests the extension makes itself (origin `moz-extension://`) are never filtered
or recorded.

- NDJSON filter: `xmlhttprequest` requests (XHR and fetch both have this type in Gecko) whose
  path is `/api/graphql/`. Attached in `onBeforeRequest`.
- Document filter: `main_frame` and `sub_frame` responses with `Content-Type: text/html`.
  Attached in `onHeadersReceived`.
- Recorder: POST requests to `/api/graphql/`, from `onBeforeRequest` with `requestBody`.

Gecko hands the stream filter the decoded body: with `Content-Encoding: gzip` the filter sees
plain bytes (verified with the `gzip` scenario: `bytesIn` equals the uncompressed size) and
the page receives the filtered bytes correctly.

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
  stream without losing or reordering bytes. In `filters.ts` an exception around the core
  writes the chunk unchanged and disconnects the filter.
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
| `PersistenceProbe` | G15, G16 phases; skipped unless `-e persistPhase` is given |
| `MeasurementProbe` | memory; skipped unless `-e measure 1` is given |

`TestEngine` (in `TestSupport.kt`) holds the shared engine and server. Right after starting the
engine it sends an event and a request over the bridge, before the extension exists, and
records every bridge event from the first one.

## 9. GeckoView quirks met in M1

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
