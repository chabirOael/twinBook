# Fixtures

Sanitized recordings of the site's logged-in traffic, for tests in M4 (filtering), M5
(requests), M6 (models and normalizer) and the native screens. Structure only: every key, the
shape of every array and object, type names, booleans, nulls and counts are real; every name,
text, URL and identifier is synthetic. The findings they come from are in
`docs/findings/payloads.md`.

## Layout

```
fixtures/
  manifest.json            provenance (no personal data), every file, what tests expect
  enum-allowlist.json      reviewed ALL_CAPS constants that stay verbatim
  graphql/<Query>/<session>-<rid>.request.json     one GraphQL request
  graphql/<Query>/<session>-<rid>.response.ndjson  its response, one document per line, as received
  preload/<Query>/<session>-document.ndjson          results the page document carried (RelayPrefetchedStreamCache)
  preload/<Query>/<session>-route-definition.ndjson  results /ajax/route-definition/ carried
```

`<session>` is the time part of the recording (`172927` desktop on the emulator, `183314`
desktop on a phone, both 2026-10-02); `<rid>` is the request's id in that recording.

| Query | Files | Use |
|---|---|---|
| `CometNewsFeedPaginationQuery` | 8 pages | feed pages 2+; 4 contain an ad |
| `CometModernHomeFeedQuery` (preload, document) | 1 | feed page 1 from the page document; contains an ad |
| `CometSinglePostDialogContentQuery` | 2 | a post with its first comments |
| `CommentsListComponentsPaginationQuery` | 4 | more comments |
| `CometNotificationsDropdownQuery`, `CometNotificationsListPaginationQuery` | 2, 3 | notifications |
| `FBUnifiedVideoRootWithEntrypointQuery`, `FBUnifiedVideoContainerQuery`, `FBUnifiedVideoSeenStateMutation` | 1, 2, 2 | video tab |
| `ProfileCometHeaderQuery`, `ProfileCometTimelineFeedQuery`, `ProfileCometTopAppSectionQuery` (preload, route definition) | 1 each | profile header and first timeline page |
| `ProfileCometTimelineFeedRefetchQuery`, `ProfileCometTilesFeedPaginationQuery` | 3, 1 | more timeline, profile tiles |
| `CometHovercardQueryRendererQuery` | 1 | person hover card |
| `StoriesTrayRectangularQuery` | 2 | stories tray |
| `CometSearchKeywordDataSourceQuery`, `SearchCometResultsInitialResultsQuery` (preload) | 7, 1 | search typeahead and first results |
| `CometHomeRightSideEgoRefetchQuery` | 1 | right column with an ad unit |

Not included: `SearchCometResultsPaginatedResultsQuery` (its only response was cut at the
recorder's 8 MiB cap, so it is not valid JSON), chat and messaging queries, composer mention
sources, settings.

A request file holds `query`, `docId`, `method`, `path`, `headerNames` (names only), and
`fields`: every form field in order as `[name, value]`, with `variables` as a JSON string.
Token fields show the recorder's redaction placeholders (`!R***!`, `!T:cookie:c_user!`).

## Regenerating

The raw recordings stay outside git (`captures/`). The fixtures, the leak test and the
findings need only the re-scrubbed copies `captures/20261002-172927-site-rescrub` and
`captures/20261002-183314-site-rescrub` (the findings also use the `-rescrub` copies of
`172602` and `181317`). The originals without `-rescrub` may be deleted once their copies
exist; docs/CAPTURE.md, "Which pulled sessions may be deleted", lists every directory. On the
machine that has the copies:

```bash
# only while the originals exist and the rules changed:
tools/capture-tools.sh rescrub captures/20261002-172927-site captures/20261002-183314-site --replace
tools/capture-tools.sh fixtures captures/20261002-172927-site-rescrub captures/20261002-183314-site-rescrub
cd extension && npx vitest run test/fixtures.test.ts     # includes the leak test
```

Regenerating from the same sessions gives byte-identical files. A new recording needs a
review of new enum values first: `tools/capture-tools.sh fixtures <sessions> --census` lists
every ALL_CAPS `key=VALUE` pair found; add to `enum-allowlist.json` only constants of the
schema, never a value that describes a person.

## How values are sanitized

Code: `extension/tools/fixtures/` (`structural.ts` decides, `sanitize.ts` replaces, `leak.ts`
checks).

Kept verbatim (structural), decided by an allowlist, and when in doubt a string is replaced:

- type names (`__typename`, `__is*`), incremental labels (`…$stream$…`, `…$defer$…`), elements
  of `path`, module references under `__dr` and `__jsr`;
- renderer, strategy, plugin and module names by their shape (`….react`,
  `…$normalization.graphql`, `…Strategy`, `…Renderer`, `…Plugin`);
- words of the schema: any string equal to a field name or type name seen in the GraphQL data;
- the request protocol: form field names, header names, `/api/graphql/`, and the values of the
  client constants `__a`, `__aaid`, `__comet_req`, `__ccg`, `dpr`, `server_timestamps`,
  `fb_api_caller_class`, `__crn`, `__spin_b`, `fb_api_req_friendly_name`, `doc_id`;
- ALL_CAPS values in `enum-allowlist.json`, MIME types and codec strings of video
  representations, a few lowercase schema constants (`type`, `entity_type`, `style_list`,
  `glyph_name`, request render locations);
- redaction placeholders.

A string is never kept if the same string occurs anywhere in the recordings as plain data (for
example a constant that is also someone's text), and never if it sits under a text key such as
`name`, `text`, `title`, `message`, `body`, `url`, `uri` or `id`, unless it is a schema word.

Replaced, deterministically within one fixture set (the same input gives the same output in
every file). Replacements come from a counter in first-seen order, never from a hash of the
value, so a guessed value cannot be confirmed by recomputing it. A replacement of 4 or more
characters never equals any string of the input.

| Input | Output |
|---|---|
| URL | `https://<kind>.example.com/<n>[.<ext>]`, kind `media`, `video`, `captions`, `static`, `www`, `link` or `external`; nothing else of the original |
| path starting with `/` | `/x/<n>` |
| digits | same number of digits |
| identifier (ASCII, no spaces) | same length, same class (upper, lower, digit) at every position, punctuation kept |
| text | synthetic lowercase words, same length in UTF-16 units, line breaks kept, so `ranges[].offset` and `length` still point at the same positions |
| object key that is not a schema field name (ids, URLs, hashes used as keys) | `k<n>` |
| number under an id key (`id`, `*_id`, `*Id`, `fbid` …) or any integer of 12+ digits | remapped, same digit count (at most 15) |
| epoch time under a time key (`*time*`, `*_at`, `expire*`, `date` …) | shifted by one offset so the first recording starts on 2001-01-01T00:00:00Z; durations stay |
| other numbers (counts, sizes, scores, widths) | kept |

The viewer's id, which the recorder could only redact as an unquoted `!T:cookie:c_user!` where
the site sent it as a number, is `0` in the fixtures.

## Leak test and scans

`extension/test/fixtures.test.ts` runs in `tools/check.sh`:

- Pattern scans, always: no e-mail address, phone number, URL outside `example.com`,
  `example.net` or `example.org`, and no `facebook.com`, `fbcdn.net` or `fbsbx.com` anywhere in
  the fixtures.
- Leak test, when the raw sessions of `manifest.json` are on the machine; reported as skipped
  otherwise. It uses the re-scrubbed copies, and the originals too while they exist. The copies
  are enough: the fixtures were made from them, and an original differs from its copy only
  where a secret was replaced. The test prints how many of each it used. It walks every value of the raw sessions:
  every response body (GraphQL, `/ajax/*`, page document islands, preloads), every form field and
  every URL parameter. It collects every non-structural string of 4 or more characters, the
  identifier tokens inside them (runs of 8+ letters and digits with a digit, or 6+ digits),
  every non-schema object key, and every number the sanitizer remaps. It then checks that none
  of them occurs as a string, key, token or number anywhere in the fixtures.
- Observe mode: every response and preload file passes the NDJSON filter with the probe rule at
  random chunk boundaries, unchanged, with no parse failure.
- Rules v1 in enforce mode on the feed files: exactly the edges in
  `manifest.json` `expected.adRemovals` are removed, every other line is unchanged, and the
  final-document rule holds.

## Limits: how personal data could still reach a fixture

- **Lengths and shapes.** Text keeps its length in UTF-16 units and identifiers keep length and
  character classes, so a three-letter name stays a capitalized three-letter word. Counts
  (reactions, comments, shares, views) and media sizes are real.
- **Relative times.** Times keep their differences: the gap between two posts is real.
- **Schema words.** A post or name consisting only of a schema word (for example `Video`) is
  kept, because the same word is a type or field name.
- **Allowlisted constants.** An ALL_CAPS value in `enum-allowlist.json` stays wherever it
  occurs, unless it also occurs as plain data. The list was reviewed for values that describe a
  person (gender, relationship and verification states were excluded).
- **Substrings.** The leak test matches whole values, identifier tokens and numbers. A personal
  word inside a kept structural string would only be found if it were a whole value or token
  in the raw data. Kept strings are type, module, field and constant names.
- **Keys.** Object keys that look like schema field names are kept. A map keyed by plain words
  (for example usernames without digits or punctuation) would keep those keys; none was seen.
- **Third-party request records** are never written to fixtures; only own-host GraphQL
  requests, their responses and preloaded results are.
