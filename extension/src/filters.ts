// webRequest wiring of the stream filters and of response-body recording. Runs only in the
// background script: the page's JavaScript realm is never touched, the page simply receives
// the bytes this code writes.
//
// One StreamFilter ("tap") per response at most. A tap can carry:
// - a filter core (NDJSON or HTML island filter) running in the profile's mode:
//     enforce  the core's output is written (M1 behaviour, mock profile only)
//     observe  the original bytes are written first, unchanged; a copy goes to the core, whose
//              decisions are counted and reported and whose output is discarded
// - a body recorder of the active capture, which gets a copy of the original bytes.
//
// Mock profile: listeners are always registered (M1). Site profile: the blocking listener is
// registered only while a capture of the site runs, it never cancels, redirects or changes
// headers (it always returns {}), and the site's mode can only be observe or off.

import type { BridgeClient } from "./lib/bridge";
import { isGraphqlUrl } from "./config";
import { bodyFor, captureRedactor, emitLine, type OpenBody } from "./capture";
import { HtmlJsonIslandFilter } from "./lib/htmlIslandFilter";
import { mockAdIslandTransform, mockAdRule } from "./lib/mockAdRule";
import { NdjsonStreamFilter, errorMessage, type FilterError } from "./lib/ndjsonFilter";
import { Probe } from "./lib/probe";
import { MOCK_PROFILE, SITE_PROFILE, type FilterMode, type FilterModes, type SiteProfile } from "./lib/profiles";
import { hasNoBodyToTap, headerValue, isTextualContentType } from "./lib/record";
import { Redactor } from "./lib/redact";

interface CoreFilter {
  push(chunk: Uint8Array): Uint8Array;
  end(): Uint8Array;
  readonly stats: object;
}

type Kind = "ndjson" | "document";

/** True for requests the extension itself made (replay path A), which are never filtered. */
export function isOwnRequest(details: { originUrl?: string | undefined }): boolean {
  return details.originUrl?.startsWith(browser.runtime.getURL("")) === true;
}

/** URL as it may appear in events to the app: site URLs are redacted (layer 1). */
function reportedUrl(profile: SiteProfile, url: string): string {
  if (profile.name === "mock") return url;
  return (captureRedactor() ?? new Redactor()).url(url);
}

const taps = new Map<string, Tap>();

class Tap {
  core: CoreFilter | undefined;
  kind: Kind | undefined;
  probe: Probe | undefined;
  body: OpenBody | undefined;
  private busyMs = 0;
  private chunks = 0;
  private firstDataAt = 0;
  private readonly startedAt = Date.now();
  private coreFailed = false;

  constructor(
    private readonly bridge: BridgeClient,
    readonly filter: ReturnType<typeof browser.webRequest.filterResponseData>,
    readonly requestId: string,
    readonly url: string,
    readonly profile: SiteProfile,
    readonly mode: FilterMode,
  ) {
    filter.ondata = (event) => this.onData(event.data);
    filter.onstop = () => this.onStop();
    filter.onerror = () => this.onError();
  }

  setCore(kind: Kind, core: CoreFilter, probe?: Probe): void {
    this.kind = kind;
    this.core = core;
    this.probe = probe;
  }

  private timed<T>(fn: () => T): T {
    const t0 = performance.now();
    try {
      return fn();
    } finally {
      this.busyMs += performance.now() - t0;
    }
  }

  private onData(data: ArrayBuffer): void {
    this.chunks++;
    const now = Date.now();
    if (this.firstDataAt === 0) this.firstDataAt = now;
    const bytes = new Uint8Array(data);
    if (this.core !== undefined && this.mode === "enforce" && !this.coreFailed) {
      const copy = this.body === undefined ? bytes : bytes.slice();
      try {
        const out = this.timed(() => this.core!.push(bytes));
        if (out.length > 0) this.filter.write(out);
      } catch (e) {
        // Fail open: hand the chunk on unchanged and stop filtering this response.
        this.filter.write(data);
        this.coreFailed = true;
        this.reportError({ kind: "internal", index: -1, message: errorMessage(e), sample: "" });
      }
      this.body?.push(copy, now);
      return;
    }
    // observe, off, or recording only: the original bytes go out first, untouched.
    const copy = this.core !== undefined || this.body !== undefined ? bytes.slice() : undefined;
    this.filter.write(data);
    if (copy === undefined) return;
    if (this.core !== undefined && !this.coreFailed) {
      try {
        this.timed(() => this.core!.push(copy));
      } catch (e) {
        this.coreFailed = true;
        this.reportError({ kind: "internal", index: -1, message: errorMessage(e), sample: "" });
      }
    }
    this.body?.push(copy, now);
  }

  private onStop(): void {
    try {
      if (this.core !== undefined && !this.coreFailed) {
        const out = this.timed(() => this.core!.end());
        if (this.mode === "enforce" && out.length > 0) this.filter.write(out);
      }
    } catch (e) {
      this.reportError({ kind: "internal", index: -1, message: errorMessage(e), sample: "" });
    } finally {
      this.filter.close();
      taps.delete(this.requestId);
    }
    this.body?.end("complete");
    if (this.core !== undefined) this.reportStats();
  }

  private onError(): void {
    taps.delete(this.requestId);
    this.body?.end("error", this.filter.error);
    this.bridge.emit("filter.error", {
      requestId: this.requestId,
      url: reportedUrl(this.profile, this.url),
      profile: this.profile.name,
      mode: this.mode,
      kind: this.kind ?? "none",
      error: { kind: "stream", index: -1, message: this.filter.error, sample: "" },
    });
  }

  reportError(error: FilterError): void {
    const sample = this.profile.name === "mock" ? error.sample : "";
    this.bridge.emit("filter.error", {
      requestId: this.requestId,
      url: reportedUrl(this.profile, this.url),
      profile: this.profile.name,
      mode: this.mode,
      kind: this.kind ?? "none",
      error: { ...error, sample },
    });
  }

  private reportStats(): void {
    const data = {
      requestId: this.requestId,
      url: reportedUrl(this.profile, this.url),
      profile: this.profile.name,
      mode: this.mode,
      kind: this.kind,
      chunks: this.chunks,
      busyMs: Math.round(this.busyMs * 100) / 100,
      elapsedMs: Date.now() - this.startedAt,
      firstDataMs: this.firstDataAt === 0 ? -1 : this.firstDataAt - this.startedAt,
      coreFailed: this.coreFailed,
      stats: { ...this.core!.stats },
    };
    this.bridge.emit("filter.stats", data);
    if (this.body !== undefined || this.probe !== undefined) {
      emitLine(this.profile, "observe", this.requestId, {
        ...data,
        probe: this.probe === undefined ? undefined : { documents: this.probe.documents, candidateTotals: this.probe.candidateTotals, reports: this.probe.reports },
      });
    }
  }
}

function attach(bridge: BridgeClient, details: { requestId: string; url: string }, profile: SiteProfile, mode: FilterMode): Tap | undefined {
  try {
    const tap = new Tap(bridge, browser.webRequest.filterResponseData(details.requestId), details.requestId, details.url, profile, mode);
    taps.set(details.requestId, tap);
    return tap;
  } catch (e) {
    bridge.emit("filter.error", {
      requestId: details.requestId,
      url: reportedUrl(profile, details.url),
      profile: profile.name,
      mode,
      kind: "none",
      error: { kind: "attach", index: -1, message: errorMessage(e), sample: "" },
    });
    return undefined;
  }
}

function errorReporter(tapRef: { tap?: Tap }): (e: FilterError) => void {
  return (error) => tapRef.tap?.reportError(error);
}

function documentCore(profile: SiteProfile, mode: FilterMode, onError: (e: FilterError) => void): { core: CoreFilter; probe?: Probe } {
  const observe = mode === "observe";
  if (profile.rule === "mock-ad") return { core: new HtmlJsonIslandFilter(mockAdIslandTransform, { onError, observe }) };
  const probe = new Probe();
  return { core: new HtmlJsonIslandFilter(probe.islandTransform, { onError, observe: true }), probe };
}

function streamCore(profile: SiteProfile, mode: FilterMode, onError: (e: FilterError) => void): { core: CoreFilter; probe?: Probe } {
  const observe = mode === "observe";
  if (profile.rule === "mock-ad") return { core: new NdjsonStreamFilter(mockAdRule, { onError, observe }) };
  const probe = new Probe();
  return { core: new NdjsonStreamFilter(probe.rule, { onError, observe: true }), probe };
}

type HeadersDetails = browser.webRequest._OnHeadersReceivedDetails;

export class Filters {
  private siteListener: ((details: HeadersDetails) => browser.webRequest.BlockingResponse) | undefined;

  constructor(
    private readonly bridge: BridgeClient,
    readonly modes: FilterModes,
  ) {}

  install(): void {
    // Mock GraphQL streams: attached before the request is sent, as in M1.
    browser.webRequest.onBeforeRequest.addListener(
      (details) => {
        if (isOwnRequest(details)) return {};
        const mode = this.modes.get(MOCK_PROFILE);
        if (mode !== "off" && details.type === "xmlhttprequest" && isGraphqlUrl(details.url)) {
          const ref: { tap?: Tap } = {};
          const tap = attach(this.bridge, details, MOCK_PROFILE, mode);
          if (tap !== undefined) {
            ref.tap = tap;
            const { core } = streamCore(MOCK_PROFILE, mode, errorReporter(ref));
            tap.setCore("ndjson", core);
          }
        }
        return {};
      },
      { urls: [...MOCK_PROFILE.urlPatterns], types: ["xmlhttprequest"] },
      ["blocking"],
    );
    browser.webRequest.onHeadersReceived.addListener((details) => this.onHeaders(details, MOCK_PROFILE), { urls: [...MOCK_PROFILE.urlPatterns] }, [
      "blocking",
      "responseHeaders",
    ]);
  }

  /** Registers or removes the site's blocking listener. Called when a site capture starts and stops. */
  setSiteListening(on: boolean): void {
    if (on && this.siteListener === undefined) {
      this.siteListener = (details) => this.onHeaders(details, SITE_PROFILE);
      browser.webRequest.onHeadersReceived.addListener(this.siteListener, { urls: [...SITE_PROFILE.urlPatterns] }, ["blocking", "responseHeaders"]);
    } else if (!on && this.siteListener !== undefined) {
      browser.webRequest.onHeadersReceived.removeListener(this.siteListener);
      this.siteListener = undefined;
    }
  }

  get siteListening(): boolean {
    return this.siteListener !== undefined;
  }

  /** Always returns {}: nothing is cancelled, redirected or rewritten. */
  private onHeaders(details: HeadersDetails, profile: SiteProfile): browser.webRequest.BlockingResponse {
    if (isOwnRequest(details) || !profile.isOwnHost(new URL(details.url).hostname)) return {};
    // Redirects and empty responses are never tapped (a redirect keeps its requestId, and the
    // final response is tapped on its own onHeadersReceived).
    if (hasNoBodyToTap(details.statusCode)) return {};
    try {
      const contentType = headerValue(details.responseHeaders, "content-type");
      const body = bodyFor(details, profile, contentType);
      let tap = taps.get(details.requestId);
      if (tap === undefined) {
        const mode = this.modes.get(profile);
        const isDocument = (details.type === "main_frame" || details.type === "sub_frame") && /^\s*text\/html\b/i.test(contentType ?? "");
        const isStream = profile.rule === "probe" && details.type === "xmlhttprequest" && isTextualContentType(contentType);
        if (body !== undefined || (mode !== "off" && (isDocument || isStream))) {
          tap = attach(this.bridge, details, profile, mode);
          if (tap !== undefined && mode !== "off" && (isDocument || isStream)) {
            const ref = { tap };
            const { core, probe } = isDocument ? documentCore(profile, mode, errorReporter(ref)) : streamCore(profile, mode, errorReporter(ref));
            tap.setCore(isDocument ? "document" : "ndjson", core, probe);
          }
        }
      }
      if (tap !== undefined && body !== undefined) {
        tap.body = body;
        body.tap = tap;
      } else {
        body?.end("not-attached");
      }
      if (profile.name === "site" && body !== undefined && (details.type === "main_frame" || details.type === "xmlhttprequest")) {
        recordSecurityInfo(details.requestId, profile);
      }
    } catch (e) {
      this.bridge.emit("filter.error", {
        requestId: details.requestId,
        url: reportedUrl(profile, details.url),
        profile: profile.name,
        kind: "none",
        error: { kind: "internal", index: -1, message: errorMessage(e), sample: "" },
      });
    }
    return {};
  }
}

/** TLS and protocol facts of a site response, as Gecko reports them. Read only. */
function recordSecurityInfo(requestId: string, profile: SiteProfile): void {
  let pending: Promise<browser.webRequest.SecurityInfo>;
  try {
    pending = browser.webRequest.getSecurityInfo(requestId, {});
  } catch {
    return;
  }
  pending.then(
    (info) => {
      const { certificates: _certificates, ...rest } = info as browser.webRequest.SecurityInfo & { certificates?: unknown };
      emitLine(profile, "security", requestId, { security: rest });
    },
    () => undefined,
  );
}
