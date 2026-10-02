// Sections of the findings numbers (docs/findings/payloads.md). Each prints names, counts,
// sizes, type names and enum-like constants only.

import { preloaderName, prefetchedResults } from "./feed";
import type { Out } from "./report";
import { count, getPath, header, isObj, isPrintableStructural, median, pattern, safeKey, walk, type Json, type Obj, type Rec, type Session } from "./session";

const enc = new TextEncoder();
const bytes = (s: string): number => enc.encode(s).length;
const kb = (n: number): string => (n >= 1_000_000 ? `${(n / 1_000_000).toFixed(2)} MB` : `${(n / 1000).toFixed(1)} KB`);
const range = (xs: number[]): string => (xs.length === 0 ? "-" : `${Math.min(...xs)}..${median(xs)}..${Math.max(...xs)}`);

/** Form fields and headers whose values may be printed: constants of the client, no identity. */
const PRINTABLE_FIELDS = new Set(["__a", "__aaid", "__comet_req", "__ccg", "dpr", "server_timestamps", "fb_api_caller_class", "__crn", "__spin_b", "routing_namespace", "fb_api_req_friendly_name", "doc_id", "trace_policy"]);
const PRINTABLE_HEADERS = new Set(["accept", "accept-language", "accept-encoding", "content-type", "sec-fetch-dest", "sec-fetch-mode", "sec-fetch-site", "x-asbd-id", "origin", "te", "priority", "alt-used", "connection", "user-agent", "pragma", "cache-control", "upgrade", "sec-websocket-version", "sec-websocket-protocol", "sec-websocket-extensions"]);

function sessionKind(s: Session): string {
  const uas = new Set<string>();
  for (const r of s.recs.values()) {
    const ua = header(r.sendHeaders?.["headers"], "user-agent");
    if (ua !== undefined && r.sendHeaders?.own === true && (r.sendHeaders?.d?.["type"] === "main_frame" || r.sendHeaders?.d?.["type"] === "xmlhttprequest")) uas.add(/Mobile/.test(ua) ? "mobile UA" : "desktop UA");
  }
  return [...uas].sort().join("+") || "?";
}

export function overview(out: Out, sessions: Session[]): void {
  out.h("0. Sessions");
  const rows: (string | number)[][] = [["session", "site (UA of own documents and XHR)", "requests", "own", "3rd", "bodies", "main_frame", "websocket", "seconds"]];
  for (const s of sessions) {
    const reqs = s.select(() => true);
    const own = reqs.filter((r) => s.info(r)!.own);
    const started = Number(s.session["startedAt"] ?? 0);
    const stopped = Number(s.session["stoppedAt"] ?? 0);
    rows.push([s.sourceId, sessionKind(s), reqs.length, own.length, reqs.length - own.length, s.lines.filter((l) => l.ev === "body").length, s.select((i) => i.own && i.type === "main_frame").length, s.select((i) => i.type === "websocket").length, Math.round((stopped - started) / 1000)]);
  }
  out.table(rows);
}

export function transport(out: Out, sessions: Session[]): void {
  out.h("1. Transport");
  const ct = new Map<string, number>();
  const enc2 = new Map<string, number>();
  const proto = new Map<string, number>();
  const labels = new Map<string, number>();
  const extKeys = new Map<string, number>();
  const lineCounts: number[] = [];
  const lineSizes: number[] = [];
  const chunkCounts: number[] = [];
  const firstMs: number[] = [];
  const lastMs: number[] = [];
  let guard = 0;
  let responses = 0;
  let finalLast = 0;
  let multiDoc = 0;
  let lastNotFinal = 0;
  for (const s of sessions) {
    for (const r of s.graphql()) {
      const b = s.bodyMeta(r);
      if (b === undefined) continue;
      responses++;
      count(ct, String(b["contentType"]));
      count(enc2, String(b["contentEncoding"]));
      count(proto, String(r.headers?.d?.["statusLine"] ?? "?").split(" ")[0]!);
      const text = s.bodyText(r) ?? "";
      if (/^\s*for ?\(;;\);/.test(text)) guard++;
      const { docs, lines } = s.ndjson(r);
      lineCounts.push(docs.length);
      for (const l of lines) lineSizes.push(bytes(l));
      const chunks = b["chunks"] as [number, number, number][] | undefined;
      chunkCounts.push(Number(b["chunkCount"] ?? 0));
      if (chunks !== undefined && chunks.length > 0) {
        firstMs.push(chunks[0]![2]);
        lastMs.push(chunks[chunks.length - 1]![2]);
      }
      if (docs.length > 1) multiDoc++;
      docs.forEach((d, i) => {
        if (!isObj(d)) return;
        if (typeof d["label"] === "string") count(labels, d["label"]);
        const ext = d["extensions"];
        if (isObj(ext)) for (const k of Object.keys(ext)) count(extKeys, safeKey(k));
        if (i === docs.length - 1) {
          if (isObj(ext) && ext["is_final"] === true) finalLast++;
          else if (docs.length > 1) lastNotFinal++;
        }
      });
    }
  }
  out.p(`GraphQL responses with a body: ${responses}; with a for(;;); guard: ${guard}; with more than one document: ${multiDoc}`);
  out.p("content types:");
  out.map(ct);
  out.p("content encodings (header; the stream filter receives decoded bytes):");
  out.map(enc2);
  out.p("protocols (status line):");
  out.map(proto);
  out.p(`documents per response min..median..max: ${range(lineCounts)}`);
  out.p(`document (line) sizes in bytes min..median..max: ${range(lineSizes)}`);
  out.p(`network chunks per response: ${range(chunkCounts)}; first chunk after (ms): ${range(firstMs)}; last chunk after (ms): ${range(lastMs)}`);
  out.p(`last document has extensions.is_final=true: ${finalLast}; multi-document responses whose last document is not final: ${lastNotFinal}`);
  out.p("labels of incremental documents:");
  out.map(labels);
  out.p("keys of extensions objects (documents carrying them):");
  out.map(extKeys);

  out.p("own-host XHR/fetch responses: body recorded vs stream filter attached (observe line):");
  const filt = new Map<string, number>();
  for (const s of sessions) {
    for (const r of s.select((i) => i.own && i.type === "xmlhttprequest")) {
      const i = s.info(r)!;
      const st = r.observe?.["stats"] as Obj | undefined;
      const state = r.observe === undefined ? (r.body === undefined ? "no body tapped, no filter" : "body, no filter") : `filtered (bytes out ${st?.["bytesIn"] === st?.["bytesOut"] ? "=" : "!="} in, failedOpen ${Number(st?.["failedOpen"] ?? 0) > 0 ? ">0" : "0"})`;
      count(filt, `${s.sourceId} ${i.path.startsWith("/ajax/") || i.path.startsWith("/api/") ? i.path : "other"} tab ${i.tabId === -1 ? "-1" : "page"}: ${state}`);
    }
  }
  out.map(filt, 80);
  out.p("documents that failed open in the observing filter, by endpoint (failedOpen / documents):");
  const fo = new Map<string, [number, number]>();
  for (const s of sessions) {
    for (const r of s.select((i, rec) => i.own && r_has(rec))) {
      const st = r.observe!["stats"] as Obj;
      const p = s.info(r)!.path;
      const c = fo.get(p) ?? [0, 0];
      c[0] += Number(st["failedOpen"] ?? 0);
      c[1] += Number(st["documents"] ?? 0);
      fo.set(p, c);
    }
  }
  out.table([...fo].sort().map(([p, [f, d]]) => [p, `${f}/${d}`]));

  out.p("own-host requests without a tab (tabId -1), by type and path:");
  const tabless = new Map<string, number>();
  for (const s of sessions) for (const r of s.select((i) => i.own && i.tabId === -1)) count(tabless, `${s.sourceId} ${s.info(r)!.type} ${s.info(r)!.path} ${r.observe !== undefined ? "filtered" : "not filtered"}${r.body !== undefined ? ", body recorded" : ""} originUrl ${originKind(r)}`);
  out.map(tabless, 80);
}

function r_has(r: Rec): boolean {
  return r.observe !== undefined;
}

function originKind(r: Rec): string {
  const o = String(r.request?.d?.["originUrl"] ?? r.sendHeaders?.d?.["originUrl"] ?? "");
  try {
    const u = new URL(o);
    return `${u.protocol}//${u.hostname}${u.pathname.replace(/\d{6,}/g, "<n>").slice(0, 60)}`;
  } catch {
    return o === "" ? "(none)" : "(other)";
  }
}

export function requestAnatomy(out: Out, sessions: Session[]): void {
  out.h("2. Request anatomy");
  for (const s of sessions) {
    const reqs = s.graphql().filter((r) => r.request !== undefined);
    if (reqs.length === 0) continue;
    out.p(`${s.sourceId}: GraphQL requests ${reqs.length} (from the page ${reqs.filter((r) => s.info(r)!.tabId !== -1).length}, without a tab ${reqs.filter((r) => s.info(r)!.tabId === -1).length})`);
    const fields = new Map<string, Map<string, number>>();
    const orders = new Set<string>();
    for (const r of reqs) {
      const fl = s.fields(r);
      orders.add(fl.map(([k]) => k).join(","));
      for (const [k, v] of fl) {
        const m = fields.get(k) ?? new Map<string, number>();
        count(m, String(v));
        fields.set(k, m);
      }
    }
    out.p(`  form fields (distinct field orders: ${orders.size}):`);
    out.table(
      [...fields].map(([k, m]) => {
        const lens = [...new Set([...m.keys()].map((v) => v.length))].sort((a, b) => a - b);
        const values = [...m.keys()];
        const placeholder = values.every((v) => /^(!R\**!|\*{1,2}|!T:[A-Za-z0-9_.:-]+!)$/.test(v)) ? (values.some((v) => v.startsWith("!T:")) ? `layer 2 ${[...new Set(values)].join(",")}` : "layer 1 redacted") : "";
        const show = PRINTABLE_FIELDS.has(k) && values.length <= 3 && k !== "fb_api_req_friendly_name" && k !== "doc_id" ? values.join(" | ") : "";
        return [k, `in ${[...m.values()].reduce((a, b) => a + b, 0)}`, m.size === 1 ? "constant" : `varies (${m.size} values)`, `len ${lens.slice(0, 5).join(",")}${lens.length > 5 ? ",…" : ""}`, placeholder || show];
      }),
      "    ",
    );
    const hdrs = new Map<string, Map<string, number>>();
    for (const r of reqs.filter((x) => s.info(x)!.tabId !== -1)) {
      for (const h of (r.sendHeaders?.["headers"] as { name: string; value?: string }[] | undefined) ?? []) {
        const k = h.name.toLowerCase();
        const v = k === "cookie" ? (h.value ?? "").split(";").map((p) => p.split("=")[0]!.trim()).sort().join(",") : (h.value ?? "");
        const m = hdrs.get(k) ?? new Map<string, number>();
        count(m, v);
        hdrs.set(k, m);
      }
    }
    out.p("  request headers of GraphQL requests from the page:");
    out.table(
      [...hdrs].map(([k, m]) => [k, m.size === 1 ? "constant" : `varies (${m.size} values)`, PRINTABLE_HEADERS.has(k) && m.size <= 2 ? [...m.keys()].join(" | ") : k === "cookie" ? `names: ${[...m.keys()].join(" | ")}` : ""]),
      "    ",
    );
  }
  out.p("other own-host endpoints: form field names (union) and request count:");
  const other = new Map<string, Set<string>>();
  const n = new Map<string, number>();
  for (const s of sessions) {
    for (const r of s.select((i) => i.own && i.type === "xmlhttprequest" && i.path !== "/api/graphql/")) {
      const p = s.info(r)!.path;
      count(n, p);
      const set = other.get(p) ?? new Set<string>();
      for (const [k] of s.fields(r)) set.add(k.replace(/\[\d+\]/g, "[n]"));
      other.set(p, set);
    }
  }
  out.table([...other].sort().map(([p, set]) => [p, n.get(p)!, [...set].join(",")]));
}

function jsonType(v: Json): string {
  if (v === null) return "null";
  if (Array.isArray(v)) return `array(${v.length > 0 ? jsonType(v[0]) : "empty"})`;
  if (isObj(v)) return `object{${Object.keys(v).length}}`;
  return typeof v;
}

function topShape(docs: Json[], depth: number): Map<string, number> {
  const m = new Map<string, number>();
  for (const d of docs) {
    const data = isObj(d) ? d["data"] : undefined;
    const seen = new Set<string>();
    walk(data, (v, path) => {
      if (path.length === 0 || path.length > depth) return;
      const p = pattern(path);
      const tn = isObj(v) && typeof v["__typename"] === "string" ? `:${v["__typename"] as string}` : "";
      const key = `${p}${tn} ${isObj(v) ? "{}" : Array.isArray(v) ? "[]" : v === null ? "null" : typeof v}`;
      if (!seen.has(key)) {
        seen.add(key);
        count(m, key);
      }
    });
  }
  return m;
}

export function queryCatalogue(out: Out, sessions: Session[]): void {
  out.h("3. Query catalogue");
  const byName = new Map<string, { s: Session; r: Rec }[]>();
  for (const s of sessions) for (const r of s.graphql()) {
    const name = String(s.field(r, "fb_api_req_friendly_name") ?? "(no name)");
    const list = byName.get(name) ?? [];
    list.push({ s, r });
    byName.set(name, list);
  }
  for (const [name, list] of [...byName].sort()) {
    const docIds = [...new Set(list.map(({ s, r }) => String(s.field(r, "doc_id"))))];
    const sizes = list.map(({ r }) => Number(r.body?.["size"] ?? 0));
    out.p(`### ${name}: ${list.length} request(s), doc_id ${docIds.join(",")}, response bytes ${range(sizes)}, sessions ${[...new Set(list.map(({ s }) => s.sourceId.slice(9, 15)))].join(",")}`);
    const vars = new Map<string, Set<string>>();
    const varValues = new Map<string, Set<string>>();
    for (const { s, r } of list) {
      const raw = s.field(r, "variables");
      let v: Json;
      try {
        v = raw === null || raw === undefined ? undefined : JSON.parse(raw);
      } catch {
        v = undefined;
      }
      if (!isObj(v)) continue;
      walk(v, (x, path) => {
        if (path.length === 0 || path.length > 3) return;
        const p = pattern(path);
        const set = vars.get(p) ?? new Set<string>();
        set.add(jsonType(x));
        vars.set(p, set);
        const vs = varValues.get(p) ?? new Set<string>();
        vs.add(JSON.stringify(x));
        varValues.set(p, vs);
      });
    }
    out.p(`  variables: ${[...vars].map(([p, t]) => `${p}:${[...t].join("|")}${(varValues.get(p)?.size ?? 0) > 1 ? "*" : ""}`).join(", ") || "(none)"}   (* = varies between requests)`);
    const docs = list.flatMap(({ s, r }) => s.ndjson(r).docs);
    const lbls = new Map<string, number>();
    for (const d of docs) if (isObj(d) && typeof d["label"] === "string") count(lbls, d["label"]);
    if (lbls.size > 0) {
      out.p("  incremental labels:");
      out.map(lbls, 20, "    ");
    }
    const first = docs.filter((d) => isObj(d) && !Array.isArray(d["path"]));
    out.p("  response data (first documents), key paths to depth 4 with type names:");
    out.map(topShape(first, 4), 40, "    ");
    const pag = new Map<string, number>();
    for (const d of docs) walk(d, (v, path, k) => {
      if (k === "page_info" || k === "end_cursor" || k === "has_next_page" || k === "cursor") count(pag, pattern(path));
    });
    if (pag.size > 0) {
      out.p("  pagination fields:");
      out.map(pag, 12, "    ");
    }
  }
}

export function video(out: Out, sessions: Session[]): void {
  out.h("6. Video");
  const paths = new Map<string, number>();
  const prog = new Map<string, number>();
  const mime = new Map<string, number>();
  let results = 0;
  let withProgressive = 0;
  let withManifestXml = 0;
  let withManifestUrl = 0;
  let withHls = 0;
  let withReps = 0;
  let withCaptions = 0;
  let withDuration = 0;
  let videos = 0;
  const reps: number[] = [];
  for (const s of sessions) {
    for (const r of s.graphql()) {
      const name = String(s.field(r, "fb_api_req_friendly_name"));
      for (const d of s.ndjson(r).docs) {
        walk(d, (v, path, k) => {
          if (isObj(v) && v["__typename"] === "Video") videos++;
          if (k !== "videoDeliveryResponseResult" || !isObj(v)) return;
          results++;
          count(paths, `${name}: ${pattern(path).replace(/^data\./, "")}`);
          const pu = v["progressive_urls"];
          if (Array.isArray(pu) && pu.some((x) => isObj(x) && typeof x["progressive_url"] === "string")) withProgressive++;
          if (Array.isArray(pu)) for (const x of pu) if (isObj(x)) count(prog, `quality ${String(getPath(x, ["metadata", "quality"]) ?? "?")}`);
          const dm = v["dash_manifests"];
          if (Array.isArray(dm) && dm.some((x) => isObj(x) && typeof x["manifest_xml"] === "string")) withManifestXml++;
          const du = v["dash_manifest_urls"];
          if (Array.isArray(du) && du.some((x) => isObj(x) && typeof x["manifest_url"] === "string")) withManifestUrl++;
          let hls = false;
          walk(v["hls_playlist_urls"], (x) => {
            if (typeof x === "string" && /^https?:/.test(x)) hls = true;
          });
          if (hls) withHls++;
          let nreps = 0;
          walk(v, (x, _p, kk) => {
            if (kk === "representations" && Array.isArray(x)) {
              nreps += x.length;
              for (const rep of x) if (isObj(rep)) count(mime, `${String(rep["mime_type"])} ${typeof rep["codecs"] === "string" ? (rep["codecs"] as string).split(".")[0] : "?"}`);
            }
          });
          if (nreps > 0) withReps++;
          reps.push(nreps);
          if (Array.isArray(v["captions_url"]) || typeof v["captions_url"] === "string") withCaptions++;
          if (typeof getPath(v, ["playable_duration_in_ms"]) === "number") withDuration++;
        });
      }
    }
  }
  out.p(`objects with __typename Video in GraphQL responses: ${videos}; videoDeliveryResponseResult objects: ${results}`);
  out.p(`  with a progressive URL ${withProgressive}, inline DASH manifest XML ${withManifestXml}, DASH manifest URL ${withManifestUrl}, HLS playlist ${withHls}, DASH representations ${withReps} (per result ${range(reps)})`);
  out.p("  where (query: path pattern):");
  out.map(paths, 30, "    ");
  out.p("  progressive URL entries by quality:");
  out.map(prog, 10, "    ");
  let prefetchDocs = 0;
  for (const s of sessions) for (const r of s.graphql()) for (const d of s.ndjson(r).docs) {
    const reps = getPath(d, ["extensions", "all_video_dash_prefetch_representations"]);
    if (!Array.isArray(reps) || reps.length === 0) continue;
    prefetchDocs++;
    walk(reps, (x, _p, kk) => {
      if (kk === "representations" && Array.isArray(x)) {
        for (const rep of x) if (isObj(rep)) count(mime, `${String(rep["mime_type"])} ${typeof rep["codecs"] === "string" ? (rep["codecs"] as string).split(".")[0] : "?"}`);
      }
    });
  }
  out.p(`  documents with extensions.all_video_dash_prefetch_representations (DASH representations with segment URLs, outside the data tree): ${prefetchDocs}`);
  out.p("  representations by MIME type and codec family:");
  out.map(mime, 20, "    ");
  const keys = new Map<string, number>();
  for (const s of sessions) for (const r of s.graphql()) for (const d of s.ndjson(r).docs) walk(d, (v, path, k) => {
    if (typeof k === "string" && /^(captions_url|playable_duration_in_ms|length_in_second|preferred_thumbnail|first_frame_thumbnail|video_available_captions_locales|playable_url|playable_url_quality_hd|browser_native_hd_url|browser_native_sd_url)$/.test(k)) count(keys, `${k} ${v === null ? "null" : Array.isArray(v) ? "array" : typeof v}`);
  });
  out.p("  other video keys (occurrences by value type):");
  out.map(keys, 30, "    ");
  out.p("media fetched by the clients (metadata of video/* and audio/* responses):");
  const media = new Map<string, number>();
  for (const s of sessions) {
    for (const r of s.select((_i, rec) => /^(video|audio)\//.test(header(rec.headers?.["headers"], "content-type") ?? ""))) {
      const i = s.info(r)!;
      let params: string[] = [];
      try {
        params = [...new URL(i.url).searchParams.keys()];
      } catch {
        params = [];
      }
      const ranged = header(r.sendHeaders?.["headers"], "range") !== undefined;
      count(media, `${s.sourceId} ${i.type} status ${String(r.headers?.d?.["statusCode"])} ${header(r.headers?.["headers"], "content-type")} host ${i.host.replace(/\d+/g, "N")} ${ranged ? "Range header" : "no Range header"} ${params.includes("bytestart") ? "bytestart/byteend in URL" : ""}`);
    }
  }
  out.map(media, 20);
}

function describeQueries(out: Out, sessions: Session[], names: string[], depth: number): void {
  for (const name of names) {
    const docs: Json[] = [];
    for (const s of sessions) for (const r of s.graphql()) if (s.field(r, "fb_api_req_friendly_name") === name) docs.push(...s.ndjson(r).docs);
    if (docs.length === 0) {
      out.p(`### ${name}: not recorded`);
      continue;
    }
    out.p(`### ${name}: ${docs.length} document(s); key paths to depth ${depth} (count of documents), type names:`);
    out.map(topShape(docs.filter((d) => isObj(d) && !Array.isArray(d["path"])), depth), 70, "    ");
  }
}

/** Preloaded query results streamed by /ajax/route-definition/: {__type: "preloader", id, result}. */
export function routePreloads(sessions: Session[]): { session: string; name: string; result: Obj }[] {
  const out: { session: string; name: string; result: Obj }[] = [];
  for (const s of sessions) for (const r of s.select((i) => i.own && i.path === "/ajax/route-definition/")) {
    for (const d of s.guarded(r).docs) {
      if (!isObj(d) || d["__type"] !== "preloader" || !isObj(d["result"])) continue;
      const inner = (d["result"] as Obj)["result"];
      if (isObj(inner)) out.push({ session: s.sourceId, name: preloaderName(String(d["id"]).replace(/_[0-9a-f]{16,}$/, "")), result: inner });
    }
  }
  return out;
}

export function otherScreens(out: Out, sessions: Session[]): void {
  out.h("7. Comments, notifications, profile");
  const pre = routePreloads(sessions);
  const preNames = new Map<string, number>();
  for (const p of pre) count(preNames, `${p.session} ${p.name}${typeof p.result["label"] === "string" ? " (incremental)" : " (first result)"}`);
  out.p("query results preloaded through /ajax/route-definition/ (not /api/graphql/):");
  out.map(preNames, 30, "    ");
  for (const name of ["ProfileCometHeaderQuery", "ProfileCometTimelineFeedQuery", "ProfileCometTopAppSectionQuery", "SearchCometResultsInitialResultsQuery"]) {
    const firsts = pre.filter((p) => p.name === name && typeof p.result["label"] !== "string");
    if (firsts.length === 0) continue;
    out.p(`### ${name} (route-definition preload): ${firsts.length} first result(s); key paths to depth 5:`);
    out.map(topShape(firsts.map((p) => p.result), 5), 50, "    ");
  }
  describeQueries(out, sessions, ["CommentsListComponentsPaginationQuery", "CometSinglePostDialogContentQuery", "CometUFIConversationGuideContainerQuery"], 6);
  describeQueries(out, sessions, ["CometNotificationsDropdownQuery", "CometNotificationsListPaginationQuery"], 7);
  describeQueries(out, sessions, ["ProfileCometTimelineFeedRefetchQuery", "ProfileCometTilesFeedPaginationQuery", "CometHovercardQueryRendererQuery"], 5);
  // Comment nodes: keys present.
  const commentKeys = new Map<string, number>();
  let comments = 0;
  for (const s of sessions) for (const r of s.graphql()) {
    const name = s.field(r, "fb_api_req_friendly_name");
    if (name !== "CommentsListComponentsPaginationQuery" && name !== "CometSinglePostDialogContentQuery") continue;
    for (const d of s.ndjson(r).docs) walk(d, (v) => {
      if (!isObj(v) || v["__typename"] !== "Comment") return;
      comments++;
      for (const k of Object.keys(v)) count(commentKeys, safeKey(k));
    });
  }
  out.p(`Comment objects: ${comments}; their keys (count):`);
  out.map(commentKeys, 80, "    ");
  const notifKeys = new Map<string, number>();
  let notifs = 0;
  for (const s of sessions) for (const r of s.graphql()) {
    if (!/^CometNotifications(Dropdown|ListPagination)Query$/.test(String(s.field(r, "fb_api_req_friendly_name")))) continue;
    for (const d of s.ndjson(r).docs) walk(d, (v) => {
      if (!isObj(v) || v["__typename"] !== "NotifPageNotificationRow" || !isObj(v["notif"])) return;
      notifs++;
      for (const k of Object.keys(v["notif"] as Obj)) count(notifKeys, safeKey(k));
    });
  }
  out.p(`notification rows (NotifPageNotificationRow): ${notifs}; keys of their notif objects (count):`);
  out.map(notifKeys, 80, "    ");
  const enums = new Map<string, number>();
  for (const s of sessions) for (const r of s.graphql()) {
    if (!/^CometNotifications/.test(String(s.field(r, "fb_api_req_friendly_name")))) continue;
    for (const d of s.ndjson(r).docs) walk(d, (v, path, k) => {
      if (typeof v === "string" && typeof k === "string" && isPrintableStructural(k, v) && /notif|type|state|seen|category/i.test(k)) count(enums, `${k}=${v}`);
    });
  }
  out.p("notification enum values:");
  out.map(enums, 40, "    ");
}

export function navigation(out: Out, sessions: Session[]): void {
  out.h("8. Navigation endpoints and document ids");
  const eps = ["/ajax/bulk-route-definitions/", "/ajax/route-definition/", "/ajax/navigation/", "/ajax/bootloader-endpoint/", "/ajax/relay-ef/", "/ajax/dtsg/", "/static_resources/webworker/rsrc/"];
  for (const p of eps) {
    const list = sessions.flatMap((s) => s.select((i) => i.own && i.path === p).map((r) => ({ s, r })));
    if (list.length === 0) continue;
    const top = new Map<string, number>();
    const payload = new Map<string, number>();
    let docs = 0;
    let failed = 0;
    let guards = 0;
    const sizes: number[] = [];
    for (const { s, r } of list) {
      sizes.push(Number(r.body?.["size"] ?? 0));
      const g = s.guarded(r);
      docs += g.docs.length;
      failed += g.failed;
      guards += g.guards;
      for (const d of g.docs) {
        if (!isObj(d)) continue;
        for (const k of Object.keys(d)) count(top, safeKey(k));
        const pl = d["payload"];
        if (isObj(pl)) for (const k of Object.keys(pl)) count(payload, safeKey(k));
      }
    }
    out.p(`### ${p}: ${list.length} response(s), bytes ${range(sizes)}, documents ${docs} behind ${guards} guards, unparsed ${failed}`);
    out.p(`  top keys: ${[...top].map(([k, v]) => `${k}(${v})`).join(", ")}`);
    if (payload.size > 0) out.p(`  payload keys: ${[...payload].map(([k, v]) => `${k}(${v})`).join(", ")}`);
  }
  // Route payloads: what one route definition holds.
  const routeKeys = new Map<string, number>();
  let routes = 0;
  for (const s of sessions) for (const r of s.select((i) => i.own && (i.path === "/ajax/bulk-route-definitions/" || i.path === "/ajax/navigation/"))) {
    for (const d of s.guarded(r).docs) {
      const pls = getPath(d, ["payload", "payloads"]);
      const items = isObj(pls) ? Object.values(pls) : [getPath(d, ["payload", "payload"])];
      for (const it of items) {
        if (!isObj(it)) continue;
        routes++;
        walk(it, (v, path) => {
          if (path.length >= 1 && path.length <= 3 && !/^\d/.test(String(path[path.length - 1]))) count(routeKeys, pattern(path));
        });
      }
    }
  }
  out.p(`route definitions (bulk + navigation): ${routes}; key paths to depth 3:`);
  out.map(routeKeys, 40, "    ");
  // Document ids.
  out.p("document ids (queryID) found in responses other than GraphQL, by source and query:");
  const ids = new Map<string, number>();
  const usedIds = new Map<string, string>();
  for (const s of sessions) for (const r of s.graphql()) usedIds.set(String(s.field(r, "doc_id")), String(s.field(r, "fb_api_req_friendly_name")));
  const foundUsed = new Set<string>();
  for (const s of sessions) {
    for (const r of s.select((i, rec) => i.own && rec.body !== undefined && i.path !== "/api/graphql/")) {
      const i = s.info(r)!;
      const src = i.type === "main_frame" ? "document" : i.path;
      const docs = i.type === "main_frame" ? s.islands(r).map((x) => x.json) : s.guarded(r).docs;
      for (const d of docs) walk(d, (v) => {
        if (!isObj(v) || v["queryID"] === undefined) return;
        const qn = String(v["queryName"] ?? v["preloaderID"] ?? "?").replace(/_[0-9a-f]{16,}$/, "");
        count(ids, `${src}: ${safeKey(preloaderName(qn))} (keys ${Object.keys(v).sort().map(safeKey).join(",")})`);
      });
      const text = s.bodyText(r) ?? "";
      for (const id of usedIds.keys()) if (text.includes(id)) foundUsed.add(id);
    }
  }
  out.map(ids, 60, "    ");
  out.p(`doc_ids used by recorded GraphQL requests: ${usedIds.size}; of these found anywhere in a recorded non-GraphQL response: ${foundUsed.size} (${[...foundUsed].map((x) => usedIds.get(x)).join(", ")})`);
  const scripts = new Map<string, number>();
  for (const s of sessions) for (const r of s.select((i) => i.type === "script")) count(scripts, `${s.sourceId} ${s.info(r)!.host} ${s.info(r)!.path.split("/").slice(0, 3).join("/")} body recorded: ${r.body !== undefined ? "yes" : "no"}`);
  out.p("script requests (their bodies are not recorded):");
  out.map(scripts, 20, "    ");
}

export function tokensAndCookies(out: Out, sessions: Session[]): void {
  out.h("9. Tokens and cookies");
  const sc = new Map<string, number>();
  for (const s of sessions) {
    for (const l of s.lines) {
      if (l.ev !== "headers" && l.ev !== "redirect") continue;
      for (const line of (header(l["headers"], "set-cookie") ?? "").split("\n").filter(Boolean)) {
        const [pair, ...attrs] = line.split(";");
        const name = pair!.slice(0, pair!.indexOf("="));
        const a = attrs.map((x) => x.trim()).filter(Boolean);
        const maxAge = a.find((x) => /^max-age=/i.test(x));
        const life = maxAge !== undefined ? `${Math.round(Number(maxAge.split("=")[1]) / 86400)} days` : a.some((x) => /^expires=/i.test(x)) ? "Expires only" : "session";
        const flags = a.filter((x) => !/^(expires|max-age)=/i.test(x)).map((x) => x.replace(/^domain=/i, "Domain=").replace(/^path=/i, "Path="));
        let host = "?";
        try {
          host = new URL(String(l.d?.["url"])).hostname;
        } catch {
          host = "?";
        }
        count(sc, `${s.sourceId} ${host} ${name}: lifetime ${life}; ${flags.join("; ")}`);
      }
    }
  }
  out.p("Set-Cookie seen (name, lifetime from Max-Age, attributes; values redacted):");
  out.map(sc, 60);
  const names = new Map<string, number>();
  for (const s of sessions) for (const r of s.recs.values()) {
    if (r.sendHeaders?.own !== true) continue;
    for (const part of (header(r.sendHeaders["headers"], "cookie") ?? "").split(";")) if (part.includes("=")) count(names, `${s.sourceId} ${part.split("=")[0]!.trim()}`);
  }
  out.p("cookie names sent to own hosts (requests carrying them):");
  out.map(names, 80);
  out.p("token-bearing fields and where they occur (occurrences; values redacted):");
  const where = new Map<string, number>();
  for (const s of sessions) {
    for (const r of s.recs.values()) {
      const i = s.info(r);
      if (i === undefined || !i.own) continue;
      for (const [k, v] of s.fields(r)) if (/^(fb_dtsg|lsd|jazoest|__user|av|fb_dtsg_ag|__a)$/.test(k)) count(where, `${s.sourceId} form ${k} (len ${String(v?.length)}) on ${i.path.startsWith("/api/graphql/") ? "graphql" : i.path.startsWith("/ajax/") ? "/ajax/*" : i.path}`);
      for (const h of ["x-fb-lsd", "x-fb-dtsg"]) if (header(r.sendHeaders?.["headers"], h) !== undefined) count(where, `${s.sourceId} header ${h}`);
      try {
        for (const k of new URL(i.url).searchParams.keys()) if (/^(fb_dtsg|fb_dtsg_ag|lsd|jazoest|__a|__user)$/.test(k)) count(where, `${s.sourceId} URL parameter ${k} on ${i.path.replace(/\d{6,}/g, "<n>")}`);
      } catch {
        // no URL
      }
      const text = s.bodyText(r);
      if (text === undefined) continue;
      for (const name of ["DTSGInitialData", "DTSGInitData", "LSD", "CurrentUserInitialData", "SiteData", "async_get_token", "dtsgToken", "dtsgAsyncGetToken", "\"token\"", "valid_for", "\"expire\""]) {
        const n = text.split(name).length - 1;
        if (n > 0) count(where, `${s.sourceId} ${name} named in ${i.type === "main_frame" ? "document" : i.path}`, n);
      }
    }
  }
  out.map(where, 80);
  for (const s of sessions) for (const r of s.select((i) => i.own && i.path === "/ajax/dtsg/")) {
    for (const d of s.guarded(r).docs) {
      const p = getPath(d, ["payload"]);
      if (isObj(p)) out.p(`  /ajax/dtsg/ payload: token (string, redacted), valid_for = ${String(p["valid_for"])} s, expire - capture time = ${Math.round((Number(p["expire"]) * 1000 - Number(r.request?.t ?? 0)) / 1000)} s`);
    }
  }
  out.p("layer 2 labels per session (distinct remembered values are not kept, so rotation cannot be measured from values):");
  for (const s of sessions) {
    const orig = s.session["taint"] as Obj | undefined;
    const re = (s.session["rescrub"] as Obj | undefined)?.["taint"] as Obj | undefined;
    out.p(`  ${s.sourceId}: finalize labels ${JSON.stringify(orig?.["labels"] ?? [])}; re-scrub labels ${JSON.stringify(re?.["labels"] ?? [])}`);
  }
}

export function pageDocuments(out: Out, sessions: Session[]): void {
  out.h("10. Page documents");
  for (const s of sessions) for (const r of s.select((i) => i.own && i.type === "main_frame")) {
    const b = s.bodyMeta(r)!;
    const text = s.bodyText(r) ?? "";
    const scripts = [...text.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/g)];
    const kinds = new Map<string, number>();
    for (const m of scripts) count(kinds, `${/type="([^"]+)"/.exec(m[1]!)?.[1] ?? "(no type)"} ${/\bsrc=/.test(m[1]!) ? "src" : "inline"}${/data-sjs/.test(m[1]!) ? " data-sjs" : ""}`);
    const islands = s.islands(r);
    out.p(`### ${s.sourceId} ${s.info(r)!.host}${s.info(r)!.path}: ${kb(Number(b["size"]))} in ${String(b["chunkCount"])} chunks over ${String(b["durationMs"])} ms; scripts ${scripts.length}; JSON islands ${islands.length} (${kb(islands.reduce((a, x) => a + x.size, 0))}, largest ${kb(Math.max(0, ...islands.map((x) => x.size)))}), unparsed islands ${islands.filter((x) => x.json === undefined).length}`);
    out.map(kinds, 10, "    ");
    if (islands.length === 0) continue;
    const pre = prefetchedResults(s, r);
    const names = new Map<string, number>();
    for (const p of pre) count(names, `${preloaderName(p.preloader)}${p.result["label"] !== undefined ? ` label ${String(p.result["label"])}` : ""}${p.complete ? " (complete)" : ""}`);
    out.p(`  prefetched Relay results (RelayPrefetchedStreamCache): ${pre.length}`);
    out.map(names, 60, "    ");
    // Position of the first feed story in the document.
    const firstFeed = text.indexOf("CometModernHomeFeedQuery");
    const firstStory = text.indexOf('"news_feed"');
    out.p(`  first feed preloader at byte ${bytes(text.slice(0, Math.max(0, firstFeed)))} of ${bytes(text)}; first "news_feed" key at byte ${bytes(text.slice(0, Math.max(0, firstStory)))}`);
    const defines = new Map<string, number>();
    for (const isl of islands) walk(isl.json, (v, path, k) => {
      if (k !== "define" || !Array.isArray(v)) return;
      for (const d of v) if (Array.isArray(d) && typeof d[0] === "string") {
        const obj = d[2];
        const tokenKeys = isObj(obj) ? Object.keys(obj).filter((x) => /token|dtsg|lsd/i.test(x)) : [];
        count(defines, `${/^[A-Za-z0-9_.$-]{1,80}$/.test(d[0]) && !/\d{5,}/.test(d[0]) ? d[0] : "<module>"}${tokenKeys.length > 0 ? ` [keys ${tokenKeys.map(safeKey).join(",")}]` : ""}`);
      }
    });
    const tokenDefines = [...defines].filter(([k]) => /\[keys|DTSG|LSD|SiteData|CurrentUser|Cookie|Token/i.test(k));
    out.p(`  modules defined in the document (ServerJS define): ${defines.size}; those with token keys or token names:`);
    out.table(tokenDefines.map(([k, v]) => [v, k]), "    ");
  }
}

export function mobile(out: Out, sessions: Session[]): void {
  out.h("11. The mobile site");
  for (const s of sessions) {
    const own = s.select((i) => i.own && i.host === "m.facebook.com" || i.own && i.host.startsWith("kaios"));
    if (own.length === 0) continue;
    const t = new Map<string, number>();
    for (const r of s.select((i) => i.own)) count(t, `${s.info(r)!.type} ${s.info(r)!.host} ${s.info(r)!.path.replace(/\d{6,}/g, "<n>")}`);
    out.p(`### ${s.sourceId}: own-host requests by type, host and path`);
    out.map(t, 40, "    ");
    for (const r of s.select((i) => i.type === "websocket")) {
      const names = ((r.sendHeaders?.["headers"] as { name: string }[] | undefined) ?? []).map((h) => h.name.toLowerCase());
      const shown = ((r.sendHeaders?.["headers"] as { name: string; value?: string }[] | undefined) ?? []).filter((h) => PRINTABLE_HEADERS.has(h.name.toLowerCase()) && /^(upgrade|sec-websocket-version|sec-websocket-protocol|sec-websocket-extensions|origin)$/i.test(h.name)).map((h) => `${h.name.toLowerCase()}=${h.value ?? ""}`);
      let params: string[] = [];
      try {
        params = [...new URL(s.info(r)!.url).searchParams.keys()];
      } catch {
        params = [];
      }
      out.p(`  websocket ${s.info(r)!.host}${s.info(r)!.path.replace(/\d{6,}/g, "<n>")}: status ${String(r.headers?.d?.["statusCode"])} ${String(r.headers?.d?.["statusLine"] ?? "").split(" ")[0]}, tab ${String(s.info(r)!.tabId)}, URL params [${params.join(",")}], request headers [${names.join(",")}] ${shown.join(" ")}`);
    }
    for (const r of s.select((i) => i.own && i.type === "main_frame")) {
      const text = s.bodyText(r) ?? "";
      const markers = ["WebSocket", "wss://", "kaios", "serviceWorker", "/sw", "weblite", "Weblite", "MWLite", "bloks", "Bloks", "graphql", "/ajax/", "bz", "ServerJS", "__bbox", "RelayPrefetchedStreamCache", "dtsg", "lsd", "application/json"];
      out.p(`  document ${kb(Number(r.body?.["size"]))}: inline scripts ${[...text.matchAll(/<script\b(?![^>]*\bsrc=)[^>]*>/g)].length}, external scripts ${[...text.matchAll(/<script\b[^>]*\bsrc=/g)].length}; marker counts: ${markers.map((m) => `${m}:${text.split(m).length - 1}`).join(" ")}`);
    }
  }
}

export function telemetry(out: Out, sessions: Session[]): void {
  out.h("12. Telemetry and third parties");
  const own = new Map<string, number>();
  const ownBytes = new Map<string, number>();
  for (const s of sessions) for (const r of s.select((i) => i.own && i.path !== "/api/graphql/" && i.type !== "main_frame" && i.type !== "image" && i.type !== "script" && i.type !== "stylesheet")) {
    const i = s.info(r)!;
    const k = `${s.sourceId} ${i.type} ${i.host} ${i.path.replace(/\d{6,}/g, "<n>")}`;
    count(own, k);
    count(ownBytes, k, Number(header(r.sendHeaders?.["headers"], "content-length") ?? 0));
  }
  out.p("own-host requests other than GraphQL, documents, images and scripts (count, request body bytes from Content-Length):");
  out.table([...own].sort((a, b) => b[1] - a[1]).map(([k, v]) => [v, ownBytes.get(k)!, k]));
  const muts = new Map<string, number>();
  for (const s of sessions) for (const r of s.graphql()) {
    const n = String(s.field(r, "fb_api_req_friendly_name"));
    if (/Mutation|Logging|Log|Record|Seen|Impression/.test(n)) count(muts, `${s.sourceId} ${n}`);
  }
  out.p("GraphQL mutations and logging-like operations:");
  out.map(muts, 30);
  const third = new Map<string, number>();
  for (const s of sessions) for (const r of s.select((i) => !i.own)) {
    const i = s.info(r)!;
    const parts = i.host.split(".");
    count(third, `${s.sourceId} ${parts.slice(-2).join(".")} ${i.type}`);
  }
  out.p("hosts outside the site's own hosts (registrable domain, type):");
  out.map(third, 60);
}

/** Number literals of a JSON text, outside strings. */
export function numberTokens(text: string): string[] {
  const out: string[] = [];
  let inString = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i]!;
    if (inString) {
      if (c === "\\") i++;
      else if (c === '"') inString = false;
      continue;
    }
    if (c === '"') {
      inString = true;
      continue;
    }
    if (c === "-" || (c >= "0" && c <= "9")) {
      const m = /^-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?/.exec(text.slice(i, i + 40));
      if (m !== null) {
        out.push(m[0]);
        i += m[0].length - 1;
      }
    }
  }
  return out;
}

export function numbers(out: Out, sessions: Session[]): void {
  out.h("13. Numbers");
  let docs = 0;
  let tokens = 0;
  let unsafe = 0;
  let nonCanonical = 0;
  let floats = 0;
  let roundTripDiffers = 0;
  const reasons = new Map<string, number>();
  const unsafeKeys = new Map<string, number>();
  const nonCanonicalSamples = new Map<string, number>();
  const scan = (raw: string): void => {
    docs++;
    for (const tok of numberTokens(raw)) {
      tokens++;
      const isInt = /^-?\d+$/.test(tok);
      if (!isInt) floats++;
      if (isInt && (BigInt(tok) > 9007199254740991n || BigInt(tok) < -9007199254740991n)) unsafe++;
      else if (String(Number(tok)) !== tok) {
        nonCanonical++;
        count(nonCanonicalSamples, tok.replace(/\d/g, "9").replace(/9+/g, "9"));
      }
    }
    for (const m of raw.matchAll(/"([A-Za-z0-9_]+)":(-?\d{16,})/g)) if (BigInt(m[2]!) > 9007199254740991n) count(unsafeKeys, m[1]!);
    let parsed: Json;
    try {
      parsed = JSON.parse(raw);
    } catch {
      count(reasons, "not parseable");
      return;
    }
    const again = JSON.stringify(parsed);
    if (again !== raw) {
      roundTripDiffers++;
      if (raw.includes("\\/")) count(reasons, "escaped slash \\/ in strings");
      if (/\\u[0-9a-fA-F]{4}/.test(raw)) count(reasons, "\\uXXXX escapes in strings");
      if (/[\s]/.test(raw.replace(/"(?:[^"\\]|\\.)*"/g, '""'))) count(reasons, "whitespace outside strings");
    }
  };
  for (const s of sessions) {
    for (const r of s.graphql()) {
      const text = s.bodyText(r);
      if (text === undefined || r.body?.["truncated"] === true) continue;
      for (const line of text.split("\n")) if (line.trim() !== "") scan(line.replace(/\r$/, ""));
    }
  }
  out.p(`GraphQL documents scanned: ${docs}; number literals ${tokens} (non-integers ${floats}); integers beyond 2^53: ${unsafe}; literals whose JavaScript String(Number(x)) differs: ${nonCanonical}`);
  out.p("keys of integers beyond 2^53:");
  out.map(unsafeKeys);
  out.p("non-canonical literal shapes (digits as 9):");
  out.map(nonCanonicalSamples);
  out.p(`documents whose JSON.stringify(JSON.parse(line)) differs from the line: ${roundTripDiffers}; reasons (documents):`);
  out.map(reasons);
}
