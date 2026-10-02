// Replay executor: performs an HTTP request on the app's behalf and returns status, headers
// and body through the bridge. Two interchangeable paths:
//
//   via "background"  fetch() from this background script, credentials included. Optionally
//                     Origin and Referer are rewritten in a blocking onBeforeSendHeaders
//                     listener (only for the extension's own requests).
//   via "anchor"      content.fetch() executed in the anchor page: a same-origin page with no
//                     site JavaScript, loaded in a (headless) session. The anchor content script
//                     is the only content script twin-bridge has.
//   via "anchor-extension-fetch"  the content script's own fetch() (extension principal), for
//                     comparison only.
//
// Replay and header rewriting are allowed only for profiles that permit them (the mock). A
// request to any other host, the real site included, is refused before anything is sent, and
// the rewrite listener is registered only for the mock's hosts.

import type { BridgeClient } from "./lib/bridge";
import { BridgeRequestError } from "./lib/bridge";
import { isOwnRequest } from "./filters";
import { MOCK_PROFILE, ownProfileOf, PROFILES } from "./lib/profiles";
import { requestInit, toResult, type FetchedResponse, type ReplayRequest } from "./lib/replayResult";

export interface ReplayResult extends FetchedResponse {
  /** Request headers as Gecko reported them in onSendHeaders, if seen. */
  sentHeaders: Record<string, string> | null;
}

interface HeaderRewrite {
  origin?: string;
  referer?: string;
  userAgent?: string;
}

let rewrite: HeaderRewrite | null = null;
const sentHeaders = new Map<string, Record<string, string>>();
const anchors: browser.runtime.Port[] = [];
let anchorSeq = 1;
const anchorPending = new Map<number, { resolve: (r: FetchedResponse) => void; reject: (e: Error) => void }>();

export function installReplay(bridge: BridgeClient): void {
  browser.webRequest.onBeforeSendHeaders.addListener(
    (details) => {
      if (rewrite === null || !isOwnRequest(details) || details.requestHeaders === undefined) return {};
      const r = rewrite;
      const replaced: Record<string, string | undefined> = { origin: r.origin, referer: r.referer, "user-agent": r.userAgent };
      const headers = details.requestHeaders.filter((h) => replaced[h.name.toLowerCase()] === undefined);
      if (r.origin !== undefined) headers.push({ name: "Origin", value: r.origin });
      if (r.referer !== undefined) headers.push({ name: "Referer", value: r.referer });
      if (r.userAgent !== undefined) headers.push({ name: "User-Agent", value: r.userAgent });
      return { requestHeaders: headers };
    },
    { urls: PROFILES.filter((p) => p.rewriteHeaders).flatMap((p) => [...p.urlPatterns]) },
    ["blocking", "requestHeaders"],
  );

  browser.webRequest.onSendHeaders.addListener(
    (details) => {
      if (!details.url.includes("twinbook_probe=")) return;
      const headers: Record<string, string> = {};
      for (const h of details.requestHeaders ?? []) headers[h.name] = h.value ?? "";
      sentHeaders.set(details.url, headers);
      if (sentHeaders.size > 50) sentHeaders.delete(sentHeaders.keys().next().value!);
    },
    { urls: [...MOCK_PROFILE.urlPatterns] },
    ["requestHeaders"],
  );

  browser.runtime.onConnect.addListener((port) => {
    if (port.name !== "anchor") return;
    anchors.push(port);
    bridge.emit("anchor.ready", { url: port.sender?.url ?? null });
    port.onMessage.addListener((raw) => {
      const m = raw as { id: number; ok: boolean; result?: FetchedResponse; error?: string };
      const p = anchorPending.get(m.id);
      if (p === undefined) return;
      anchorPending.delete(m.id);
      if (m.ok && m.result !== undefined) p.resolve(m.result);
      else p.reject(new BridgeRequestError("anchor_fetch_failed", m.error ?? "anchor fetch failed"));
    });
    port.onDisconnect.addListener(() => {
      const i = anchors.indexOf(port);
      if (i >= 0) anchors.splice(i, 1);
      bridge.emit("anchor.gone", { url: port.sender?.url ?? null });
    });
  });

  bridge.handle("replay.configure", (params) => {
    const r = params["rewrite"];
    rewrite = typeof r === "object" && r !== null ? (r as HeaderRewrite) : null;
    return { rewrite };
  });

  bridge.handle("replay.fetch", async (params) => {
    const request = params["request"] as ReplayRequest;
    const via = String(params["via"] ?? "background");
    assertReplayAllowed(request.url);
    let result: FetchedResponse;
    if (via === "background") {
      result = await fetchHere(request, via);
    } else if (via === "anchor" || via === "anchor-extension-fetch") {
      result = await fetchInAnchor(request, via);
    } else {
      throw new BridgeRequestError("bad_request", `unknown via ${via}`);
    }
    return { ...result, sentHeaders: sentHeaders.get(request.url) ?? null } satisfies ReplayResult;
  });

  bridge.handle("replay.anchors", () => ({ count: anchors.length, urls: anchors.map((p) => p.sender?.url ?? null) }));
}

/** Throws unless `url` belongs to a profile that allows replay. */
export function assertReplayAllowed(url: string): void {
  if (ownProfileOf(url)?.replay !== true) throw new BridgeRequestError("forbidden_host", "replay is not allowed for this host");
}

async function fetchHere(request: ReplayRequest, via: string): Promise<FetchedResponse> {
  return toResult(await fetch(request.url, requestInit(request)), via);
}

function fetchInAnchor(request: ReplayRequest, via: string): Promise<FetchedResponse> {
  const port = anchors[anchors.length - 1];
  if (port === undefined) return Promise.reject(new BridgeRequestError("no_anchor", "no anchor page is loaded"));
  const id = anchorSeq++;
  return new Promise((resolve, reject) => {
    anchorPending.set(id, { resolve, reject });
    port.postMessage({ id, via, request });
  });
}
