# twinBook capture format

How twin-bridge records what the site sends, how secrets are removed, and what a capture
session looks like on disk. Written for whoever builds on the recordings (M2b first) and for
anyone changing the recorder. Build and device instructions are in `docs/SETUP.md`, the
owner's steps in `docs/CAPTURE-CHECKLIST.md`, the engine in `docs/ENGINE.md`.

## 1. Overview

```
 page in a GeckoSession
   |  network
 twin-bridge background script (extension/src/capture.ts, filters.ts)
   |  webRequest metadata listeners (every request of a captured site's pages)
   |  one StreamFilter per recorded response: original bytes written to the page first,
   |  a copy kept for the record (observe only on the real site)
   |  layer 1 redaction at the source (src/lib/redact.ts)
   v
 capture.write bridge requests, one batch at a time, answered after the write
   v
 app: CaptureRecorder (engine/.../capture/CaptureRecorder.kt) -> CaptureStore (:capture)
   |  files in app-private storage: files/captures/<id>/
   |  stop: the extension hands over the secrets it saw; layer 2 (taint) scrubs every file,
   |  a verification scan must find nothing, then checksums and FINALIZED are written
   v
 tools/capture-pull.sh: copies finalized sessions only, verifies checksums -> captures/<id>/
```

A capture records one or more site profiles (`site`: the real site; `mock`: the test mock).
The app's capture browser captures `site`; the instrumented tests capture `mock`.
`tools/har-import.sh` produces the same format from a HAR file (section 8).

## 2. What is recorded

For every request made by a page of a captured profile (the request's own host, its document,
its origin or any ancestor frame belongs to the profile; third parties included), one line per
webRequest event:

| `ev` | When | Extra fields |
|---|---|---|
| `request` | onBeforeRequest | `body`: request body, own hosts only (section 2.1) |
| `sendHeaders` | onSendHeaders | `headers`: request headers as sent (layer 1 applied) |
| `headers` | onHeadersReceived | `headers`: response headers (layer 1 applied) |
| `redirect` | onBeforeRedirect | `headers` |
| `responseStarted` | onResponseStarted | |
| `completed` | onCompleted | |
| `error` | onErrorOccurred | |
| `security` | own hosts, main documents and XHR | `security`: `webRequest.getSecurityInfo` without certificates (TLS version, cipher, HSTS ...) |
| `body` | the response body was recorded | section 2.2 |
| `observe` | a filter core ran on the response | mode, kind, filter stats, probe reports (section 6) |
| `start`, `end` | first and last line, `rid` empty | profiles and start time; counters, layer 1 counts, error samples |

Every line has `v` (format version), `ev`, `rid` (the webRequest `requestId`; the same for all
events of one request, redirects included), `profile`, and for request events `own` (true for
the profile's own hosts), `t` (the event's `timeStamp`, ms since the epoch) and `d`: every field
of the webRequest details object except headers and bodies, with URL fields redacted. Keeping
all fields means whatever Gecko reports about caches, service workers, proxies and
classification is there without being named: `fromCache`, `ip`, `statusCode`, `statusLine`
(includes the protocol, e.g. `HTTP/2.0 200`), `tabId` (`-1` for requests without a tab, such
as service-worker fetches), `frameId`, `parentFrameId`, `frameAncestors`, `documentUrl`,
`originUrl`, `thirdParty`, `urlClassification`, `requestSize`, `responseSize`, `proxyInfo`,
`cookieStoreId`, `incognito`.

### 2.1 Request bodies (own hosts only)

`body.kind`:

- `formData`: `fields`, the parsed form fields in order as `[name, value]` pairs, duplicates
  kept, layer 1 applied. This is what form posts and the site's async POSTs produce.
- `raw`: `text` of a non-form body (JSON, text) up to 256 KiB, layer 1 applied; `bytes` total
  size; `truncated`.
- `binary`: `bytes` only. `error`, `none`.

### 2.2 Response bodies (own hosts only)

Recorded for `main_frame`, `sub_frame` and `xmlhttprequest` (XHR and fetch) responses whose
`Content-Type` is textual: `text/*`, JSON (`application/json`, `*+json`, `application/x-ndjson`),
JavaScript types (`application/x-javascript` etc., which the site uses for guarded JSON),
XML and form types. Responses with status 1xx, 3xx and 204 are never tapped (a redirect keeps
its `requestId`; the final response is tapped on its own). Scripts, styles, images, media,
fonts and beacons are metadata only.

The `body` line: `file` (`bodies/<rid>-<n>.res`), `type`, `contentType`, `contentEncoding`
(the header; Gecko hands the stream filter decoded bytes, so the file is always decoded),
`status` (`complete`, `error`, `abandoned` when the capture stopped first, `not-attached`),
`size` (all bytes that arrived), `recorded` (bytes stored), `truncated`, `cap`, `sha256`
(of the recorded bytes as they arrived, before redaction), `chunkCount`, `chunks`
(`[offset, length, ms since the response started]` per chunk, at most 20,000 entries,
`chunkLogTruncated`), `durationMs`.

The file holds the recorded bytes with layer 1 and layer 2 applied. Layer 1 placeholders have
the length of the value they replace; layer 2 placeholders do not (section 5). So `chunks`
offsets and `sha256` always refer to the bytes as they arrived, not to the stored file.

## 3. Directory layout

```
files/captures/<id>/          app-private storage of the app that recorded it
  OPEN                        present while recording; removed by finalize
  events.ndjson               all lines of section 2, in arrival order
  bodies/<rid>-<n>.res        response bodies
  session.json                written by finalize (section 4)
  checksums.sha256            written by finalize: `sha256sum -c` format, one line per file
                              except itself and FINALIZED, sorted
  FINALIZED                   written last (temporary file + rename):
                                twinbook-capture 1
                                checksums <sha256 of checksums.sha256>
```

`<id>` is `yyyyMMdd-HHmmss-<profiles>` (for example `20261002-142501-site`), or a test's own id;
it always matches `[A-Za-z0-9_-]{1,64}`.

A session is finalized if and only if `FINALIZED` exists. An unfinalized session is never
pulled (`capture-pull.sh` refuses it) and is deleted when the app process starts
(`TwinBookApp`, main process only). It is also deleted at once if the extension restarts
during the capture or does not answer `capture.stop`, because its secrets are then gone.

## 4. session.json

| Field | Meaning |
|---|---|
| `format` | 1 |
| `id`, `profiles`, `startedAt`, `stoppedAt` | |
| `complete` | false if the extension reported a transport failure (records may be missing) |
| `app` | application id, version, build type |
| `geckoview`, `extension` | versions |
| `extensionReport` | the extension's `capture.stop` answer without the secrets: `counters` (lines, requests, bodies, bodyBytes, truncated, abandoned, errors, redactions, secrets), `redactions` (layer 1 counts by kind), `transport` (batches, items, bytes, retries, maxQueuedBytes, pressureEvents, failed), `errorSamples` |
| `writer` | lines, bodies, bytes, droppedBodyBytes, errors, batches, duplicateBatches |
| `taint` | `minTaintLength`, `rememberedValues`, `eligibleValues`, `patterns`, `replacements`, `byLabel` (label -> count), `labels` (every label seen, names only), `verifyHits` (always 0 in a finalized session) |
| `files`, `dataBytes` | |

It never contains a secret value. It is scrubbed by layer 2 like every other file.

## 5. Redaction

### Layer 1, at the source (extension, `src/lib/redact.ts`)

Rules in `extension/data/redaction-rules.json`, one `why` per entry.

- `Cookie` request headers: every cookie keeps its name; its value becomes a placeholder.
- `Set-Cookie` response headers (Gecko joins several with newlines): name and attributes
  (Path, Domain, Expires, Max-Age, Secure, HttpOnly, SameSite) stay; the value is replaced.
- Credential headers (`Authorization`, `Proxy-Authorization`, `X-FB-LSD`, `X-CSRFToken` and
  the others in the rules file): the value is replaced.
- URL-valued headers (`Referer`, `Location`, `Origin`, `Content-Location`) and every recorded
  URL: query and fragment values of secret keys are replaced.
- Form fields whose name is a secret key: value replaced. Other values are scanned as text
  (JSON inside a form field, such as `variables`).
- Text bodies (requests and responses): values of secret keys in JSON at any escaping level
  (`"token":"..."`, `\"token\":\"...\"`), in JavaScript literals (`token:'...'`), in
  `key=value` pairs, and in `<input name="key" value="...">` tags.

Secret keys (case-insensitive, exact names): `fb_dtsg`, `fb_dtsg_ag`, `async_get_token`, `lsd`,
`jazoest`, `__a` (see the rules file: usually `1`, but a long opaque value on one logged-out
beacon), `token`, `access_token`, `refresh_token`, `id_token`, `oauth_token`, `auth_token`,
`session_key`, `sessionKey`, `session_token`, `csrf_token`, `csrftoken`, `xsrf_token`, `nonce`,
`machine_id`, `pass`, `password`, `encpass`, `email`, `contact_point`, `contactpoint`,
`approvals_code`, `otp`. Shape-only fields stay: `__rev`, `__req`, `__s`, `__hsi`, `__dyn`,
`__csr`, `__user`, `__spin_*`, `av`, `doc_id`, `variables` (its inner keys are scanned).

Placeholder: a value of n characters becomes `!R` + `*` × (n − 3) + `!` (n ≥ 3), or `*` × n
(n < 3). The length is kept, so sizes and offsets stay meaningful. The characters are left
alone by `encodeURIComponent`, are valid in cookies, headers and JSON strings.

Every value replaced by layer 1 is remembered in the extension's memory with a label
(`cookie:<name>`, `header:<name>`, `field:<key>`), together with its URL-decoded and
JSON-unescaped forms. The memory is per capture and is cleared after `capture.stop`.

### Layer 2, taint, at finalize (app, `capture/.../TaintScrubber.kt`)

When the owner stops a capture, the extension answers `capture.stop` with the remembered
values (only after every record is acknowledged). The app then scrubs every file of the
session, including bodies recorded before a value was first recognized (a token embedded in
the HTML document, later sent as a form field), and runs a verification scan that must find
nothing. The values exist only in memory, in the extension and then in the app during
finalize; they are never written to disk or logged.

- Eligible values: at least **8** UTF-16 characters (`minTaintLength`), not in the stop list
  (`true`, `false`, `null`, `undefined`, `deleted`, case-insensitive), not one repeated
  character, not a layer 1 placeholder. **Short-value rule:** shorter values (for example
  `jazoest`, 4 to 5 digits, or short cookie values like `locale`) would match unrelated data,
  so they are redacted only where layer 1 finds them under their key.
- Each eligible value is searched in seven encodings: as is, `encodeURIComponent`, form
  encoding (space as `+`), JSON-escaped, JSON-escaped with `\/`, JSON-escaped twice,
  HTML-escaped. Variants shorter than 8 bytes are skipped.
- Bytes are scanned left to right; at each position the longest matching variant is replaced
  by `!T:<label>!`, for example `!T:cookie:c_user!`. The first value's label wins when two
  values share a variant. Labels contain only `[A-Za-z0-9_.:-]`.

The same algorithm runs in TypeScript for the HAR importer (`extension/src/lib/taint.ts`).
Both implementations run the shared vectors `extension/test/vectors/taint.json` (Vitest and
the `:capture` JVM test).

Known over-redaction: any non-secret cookie value of 8 characters or more (for example a
window size like `1280x720`) is scrubbed wherever it appears. Placeholders name their label,
so M2b can tell.

## 6. Filter modes and the probe

Each profile has a filter mode (`filter.setMode`): `enforce` (the M1 behaviour: the core's
output reaches the page), `observe` (the original bytes are written to the page first,
unchanged; a copy runs through the core, whose decisions are counted and reported and whose
output is discarded), or `off` (no core). The real-site profile allows only `observe` and `off`
and its default is `observe`; its listeners exist only while a capture of it runs.

The real site's observing rule is a probe (`src/lib/probe.ts`). Per JSON document (an NDJSON
line of an XHR response, a guarded single document, or a JSON island of an HTML document) it
reports `bytes`, `topKeys` (at most 40) and `candidates`: the candidate key names found anywhere
inside, with counts. The names are in `extension/data/probe-keys.json`: `sponsored_data`,
`ad_id`, `is_sponsored`, `client_token`. At most 400 reports per response are kept
(`probe.documents` counts all). The `observe` line also carries the core's `stats`
(`bytesIn`, `bytesOut` equal in observe mode, `documents`, `kept`, `failedOpen` ...).

## 7. Transport and limits

| Limit | Value | Where |
|---|---|---|
| Response body cap | 8 MiB per body; the rest is counted (`size`), not stored | `BODY_CAP_BYTES`, record.ts |
| Request body cap (non-form) | 256 KiB | `REQUEST_BODY_CAP_BYTES` |
| Chunk log | 20,000 entries per body | `MAX_CHUNK_LOG` |
| Batch size | 512 KiB per `capture.write`; body pieces of 192 KiB before base64 | captureTransport.ts, record.ts |
| Backpressure | above 16 MiB queued the recorded streams are suspended, below 4 MiB resumed | captureTransport.ts |
| Retries | a failed batch is re-sent with the same `seq`, up to 40 times; the app ignores a `seq` it has written | captureTransport.ts, CaptureStore.kt |
| Session size | 1 GiB of data; beyond it body bytes are dropped and counted as errors | `DEFAULT_MAX_SESSION_BYTES` |
| Probe reports | 400 per response | probe.ts |

Records never travel as bridge events (the app replays only the last 64 events). The
transport is lossless (acknowledged requests, retries), ordered (one batch in flight), and
applies backpressure instead of dropping.

## 8. HAR import

`tools/har-import.sh <file.har> [--id <id>] [--out <dir>]` converts a HAR file exported from
desktop Firefox (Network panel, "Save All As HAR") into a finalized session under `captures/`.
It runs layer 1 and layer 2 with the extension's own code, in memory, and writes only the
result. HAR differences: `profile` is `har`; `rid` is `har<n>`; `d` has the HAR fields (`url`,
`method`, a guessed `type`, `httpVersion`, `serverIPAddress`, `statusCode`, `statusLine`,
`timings`, `cache`); request and response `cookies` arrays are kept with redacted values; there
are no chunk logs or observe lines. The HAR file itself contains live credentials: keep it
outside the repository and delete it after the import.

## 9. Versioning

`format` in session.json and `v` on every line are 1. A change that renames or removes a field,
or changes placeholder syntax, increments both and is described here. Adding fields does not.
