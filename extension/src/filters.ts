// webRequest wiring for the stream filters. Runs only in the background script: the page's
// JavaScript realm is never touched, the page simply receives already-filtered bytes.

import type { BridgeClient } from "./lib/bridge";
import { TARGET_URL_PATTERNS, isGraphqlUrl } from "./config";
import { HtmlJsonIslandFilter } from "./lib/htmlIslandFilter";
import { mockAdIslandTransform, mockAdRule } from "./lib/mockAdRule";
import { NdjsonStreamFilter, errorMessage, type FilterError } from "./lib/ndjsonFilter";

interface CoreFilter {
  push(chunk: Uint8Array): Uint8Array;
  end(): Uint8Array;
  readonly stats: object;
}

type Kind = "ndjson" | "document";

export interface FilterSwitch {
  enabled: boolean;
}

/** True for requests the extension itself made (replay path A), which are never filtered. */
export function isOwnRequest(details: { originUrl?: string | undefined }): boolean {
  return details.originUrl?.startsWith(browser.runtime.getURL("")) === true;
}

export function installFilters(bridge: BridgeClient, filterSwitch: FilterSwitch): void {
  browser.webRequest.onBeforeRequest.addListener(
    (details) => {
      if (!filterSwitch.enabled || isOwnRequest(details)) return {};
      if (details.type === "xmlhttprequest" && isGraphqlUrl(details.url)) {
        attach(bridge, details.requestId, details.url, "ndjson", new NdjsonStreamFilter(mockAdRule, { onError: reporter(bridge, details, "ndjson") }));
      }
      return {};
    },
    { urls: TARGET_URL_PATTERNS, types: ["xmlhttprequest"] },
    ["blocking"],
  );

  browser.webRequest.onHeadersReceived.addListener(
    (details) => {
      if (!filterSwitch.enabled || isOwnRequest(details)) return {};
      const contentType = details.responseHeaders?.find((h) => h.name.toLowerCase() === "content-type")?.value ?? "";
      if (/^\s*text\/html\b/i.test(contentType)) {
        attach(bridge, details.requestId, details.url, "document", new HtmlJsonIslandFilter(mockAdIslandTransform, { onError: reporter(bridge, details, "document") }));
      }
      return {};
    },
    { urls: TARGET_URL_PATTERNS, types: ["main_frame", "sub_frame"] },
    ["blocking", "responseHeaders"],
  );
}

function reporter(bridge: BridgeClient, details: { requestId: string; url: string }, kind: Kind): (e: FilterError) => void {
  return (error) => bridge.emit("filter.error", { requestId: details.requestId, url: details.url, kind, error: { ...error } });
}

function attach(bridge: BridgeClient, requestId: string, url: string, kind: Kind, core: CoreFilter): void {
  let filter: ReturnType<typeof browser.webRequest.filterResponseData>;
  try {
    filter = browser.webRequest.filterResponseData(requestId);
  } catch (e) {
    bridge.emit("filter.error", { requestId, url, kind, error: { kind: "attach", index: -1, message: errorMessage(e), sample: "" } });
    return;
  }
  let busyMs = 0;
  let chunks = 0;
  let firstDataAt = 0;
  const startedAt = Date.now();
  const run = (fn: () => Uint8Array): void => {
    const t0 = performance.now();
    const out = fn();
    busyMs += performance.now() - t0;
    if (out.length > 0) filter.write(out);
  };
  filter.ondata = (event) => {
    chunks++;
    if (firstDataAt === 0) firstDataAt = Date.now();
    try {
      run(() => core.push(new Uint8Array(event.data)));
    } catch (e) {
      // Fail open: hand the chunk on unchanged and stop filtering this response.
      filter.write(event.data);
      filter.disconnect();
      bridge.emit("filter.error", { requestId, url, kind, error: { kind: "internal", index: -1, message: errorMessage(e), sample: "" } });
    }
  };
  filter.onstop = () => {
    try {
      run(() => core.end());
    } finally {
      filter.close();
    }
    bridge.emit("filter.stats", {
      requestId,
      url,
      kind,
      chunks,
      busyMs: Math.round(busyMs * 100) / 100,
      elapsedMs: Date.now() - startedAt,
      firstDataMs: firstDataAt === 0 ? -1 : firstDataAt - startedAt,
      stats: { ...core.stats },
    });
  };
  filter.onerror = () => {
    bridge.emit("filter.error", { requestId, url, kind, error: { kind: "stream", index: -1, message: filter.error, sample: "" } });
  };
}
