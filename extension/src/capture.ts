// The capture recorder: while the app runs a capture, every request made by pages of the
// captured profiles is recorded, redacted at the source (layer 1), and sent to the app over
// the lossless transport (lib/captureTransport.ts). The app writes it to app-private storage.
// docs/CAPTURE.md describes the format.
//
// Bridge requests (from the app):
//   capture.start   {sessionId, profiles: ["site"] | ["mock"]}  -> {startedAt, extensionStartedAt, formatVersion, limits}
//   capture.stop    {sessionId} -> {ok, counters, redactions, transport, secrets: [{value, label}]}
//   capture.discard {sessionId} -> {}
//   capture.status  {} -> {active, sessionId, counters, transport}
// The secrets in capture.stop's answer are what layer 2 (the app's finalize pass) scrubs from
// the session. They exist only in memory, here and in the app, and are dropped afterwards.
//
// Requests to the app: capture.write {sessionId, seq, items, counters}, answered once written.

import type { BridgeClient } from "./lib/bridge";
import { BridgeRequestError } from "./lib/bridge";
import { bytesToBase64, bytesToLatin1, latin1ToBytes, sha256Hex } from "./lib/bytes";
import { CaptureTransport, type TransportStats } from "./lib/captureTransport";
import { membershipOf, profileByName, type Membership, type ProfileName, type SiteProfile } from "./lib/profiles";
import {
  BODY_CAP_BYTES,
  BODY_PIECE_BYTES,
  BodyRecorder,
  bodyFileName,
  FORMAT_VERSION,
  headerValue,
  MAX_CHUNK_LOG,
  pickDetails,
  recordRequestBody,
  REQUEST_BODY_CAP_BYTES,
  shouldRecordResponseBody,
} from "./lib/record";
import { Redactor } from "./lib/redact";

interface Counters {
  lines: number;
  requests: number;
  bodies: number;
  bodyBytes: number;
  truncated: number;
  abandoned: number;
  errors: number;
}

interface Session {
  readonly id: string;
  readonly profiles: ReadonlySet<ProfileName>;
  readonly startedAt: number;
  readonly redactor: Redactor;
  readonly transport: CaptureTransport;
  readonly counters: Counters;
  readonly openBodies: Set<OpenBody>;
  readonly pending: Set<Promise<void>>;
  readonly errorSamples: string[];
  stopping: boolean;
  nextBody: number;
}

let current: Session | null = null;

/** The session's redactor while a capture runs (used to redact URLs in events too). */
export function captureRedactor(): Redactor | undefined {
  return current?.redactor;
}

function sessionFor(profile: SiteProfile): Session | null {
  return current !== null && !current.stopping && current.profiles.has(profile.name) ? current : null;
}

function push(session: Session, ev: string, rid: string, data: Record<string, unknown>): void {
  session.counters.lines++;
  session.transport.push({ l: JSON.stringify({ v: FORMAT_VERSION, ev, rid, ...data }) });
}

/** Adds a line to the running capture of `profile`, if any. */
export function emitLine(profile: SiteProfile, ev: string, rid: string, data: Record<string, unknown>): void {
  const session = sessionFor(profile);
  if (session !== null) push(session, ev, rid, { profile: profile.name, ...data });
}

function noteError(session: Session, message: string): void {
  session.counters.errors++;
  if (session.errorSamples.length < 20) session.errorSamples.push(message.slice(0, 200));
}

/** A response body being copied into the capture. */
export class OpenBody {
  /** Set by filters.ts once the stream filter is attached; used for backpressure. */
  tap: { filter: { suspend(): void; resume(): void } } | undefined;
  private closed = false;
  readonly recorder: BodyRecorder;

  constructor(
    private readonly session: Session,
    readonly requestId: string,
    readonly profile: SiteProfile,
    readonly meta: Record<string, unknown>,
  ) {
    this.recorder = new BodyRecorder(Date.now(), BODY_CAP_BYTES);
    session.openBodies.add(this);
  }

  push(bytes: Uint8Array, now: number): void {
    if (!this.closed) this.recorder.push(bytes, now);
  }

  /** Finishes the record: hash, layer 1 redaction, body pieces, then the body line. */
  end(status: "complete" | "error" | "abandoned" | "not-attached", error?: string): void {
    if (this.closed) return;
    this.closed = true;
    const session = this.session;
    session.openBodies.delete(this);
    const work = this.finish(status, error).catch((e: unknown) => noteError(session, `body ${this.requestId}: ${String(e)}`));
    session.pending.add(work);
    void work.finally(() => session.pending.delete(work));
  }

  private async finish(status: string, error: string | undefined): Promise<void> {
    const rec = this.recorder;
    const data = rec.data();
    const sha256 = await sha256Hex(data);
    const file = bodyFileName(this.requestId, this.session.nextBody++);
    const redacted = latin1ToBytes(this.session.redactor.text(bytesToLatin1(data)));
    for (let off = 0; off < redacted.length || off === 0; off += BODY_PIECE_BYTES) {
      const piece = redacted.subarray(off, off + BODY_PIECE_BYTES);
      this.session.transport.push({ f: file, b: bytesToBase64(piece), last: off + BODY_PIECE_BYTES >= redacted.length });
      if (redacted.length === 0) break;
    }
    const c = this.session.counters;
    c.bodies++;
    c.bodyBytes += redacted.length;
    if (rec.truncated) c.truncated++;
    if (status === "abandoned") c.abandoned++;
    push(this.session, "body", this.requestId, {
      profile: this.profile.name,
      ...this.meta,
      status,
      error,
      file,
      size: rec.totalBytes,
      recorded: rec.recordedBytes,
      truncated: rec.truncated,
      cap: rec.cap,
      sha256,
      chunkCount: rec.chunkCount,
      chunks: rec.chunks,
      chunkLogTruncated: rec.chunkCount > MAX_CHUNK_LOG,
      durationMs: Date.now() - rec.startedAt,
    });
  }
}

/**
 * The body recorder for a response, if the running capture wants its body: the request
 * belongs to a captured profile's own host, and the type and content type are recorded.
 */
export function bodyFor(
  details: { requestId: string; url: string; type: string; responseHeaders?: { name: string; value?: string | undefined }[] | undefined },
  profile: SiteProfile,
  contentType: string | undefined,
): OpenBody | undefined {
  const session = sessionFor(profile);
  if (session === null || !shouldRecordResponseBody(details.type, contentType)) return undefined;
  return new OpenBody(session, details.requestId, profile, {
    type: details.type,
    contentType: contentType ?? null,
    contentEncoding: headerValue(details.responseHeaders, "content-encoding") ?? null,
  });
}

// ---- metadata listeners ---------------------------------------------------------------

type AnyDetails = { requestId: string; url: string; timeStamp: number; originUrl?: string; documentUrl?: string; frameAncestors?: { url: string }[] };

function membership(details: AnyDetails): { session: Session; m: Membership } | null {
  if (current === null || current.stopping) return null;
  if (details.originUrl?.startsWith(browser.runtime.getURL("")) === true) return null;
  const m = membershipOf(details);
  if (m === undefined || !current.profiles.has(m.profile.name)) return null;
  return { session: current, m };
}

function metaLine(ev: string, details: AnyDetails, extra: (session: Session, m: Membership) => Record<string, unknown> = () => ({})): void {
  const hit = membership(details);
  if (hit === null) return;
  const { session, m } = hit;
  try {
    push(session, ev, details.requestId, {
      profile: m.profile.name,
      own: m.own,
      t: details.timeStamp,
      d: pickDetails(details, session.redactor),
      ...extra(session, m),
    });
  } catch (e) {
    noteError(session, `${ev}: ${String(e)}`);
  }
}

const ALL_URLS = ["*://*/*"];

type Listener<T> = { add: () => void; remove: () => void; fn?: (d: T) => void };

function listeners(): Listener<unknown>[] {
  const onBeforeRequest = (d: browser.webRequest._OnBeforeRequestDetails): void =>
    metaLine("request", d as AnyDetails, (session, m) => {
      if (m.own) session.counters.requests++;
      return m.own ? { body: recordRequestBody(d.requestBody as Parameters<typeof recordRequestBody>[0], session.redactor) } : {};
    });
  const onSendHeaders = (d: browser.webRequest._OnSendHeadersDetails): void =>
    metaLine("sendHeaders", d as AnyDetails, (session) => ({ headers: session.redactor.headers(d.requestHeaders) }));
  const onHeadersReceived = (d: browser.webRequest._OnHeadersReceivedDetails): void =>
    metaLine("headers", d as AnyDetails, (session) => ({ headers: session.redactor.headers(d.responseHeaders) }));
  const onBeforeRedirect = (d: browser.webRequest._OnBeforeRedirectDetails): void =>
    metaLine("redirect", d as AnyDetails, (session) => ({ headers: session.redactor.headers(d.responseHeaders) }));
  const onResponseStarted = (d: browser.webRequest._OnResponseStartedDetails): void => metaLine("responseStarted", d as AnyDetails);
  const onCompleted = (d: browser.webRequest._OnCompletedDetails): void => metaLine("completed", d as AnyDetails);
  const onErrorOccurred = (d: browser.webRequest._OnErrorOccurredDetails): void => metaLine("error", d as AnyDetails);
  const filter = { urls: ALL_URLS };
  const wr = browser.webRequest;
  return [
    { add: () => wr.onBeforeRequest.addListener(onBeforeRequest, filter, ["requestBody"]), remove: () => wr.onBeforeRequest.removeListener(onBeforeRequest) },
    { add: () => wr.onSendHeaders.addListener(onSendHeaders, filter, ["requestHeaders"]), remove: () => wr.onSendHeaders.removeListener(onSendHeaders) },
    { add: () => wr.onHeadersReceived.addListener(onHeadersReceived, filter, ["responseHeaders"]), remove: () => wr.onHeadersReceived.removeListener(onHeadersReceived) },
    { add: () => wr.onBeforeRedirect.addListener(onBeforeRedirect, filter, ["responseHeaders"]), remove: () => wr.onBeforeRedirect.removeListener(onBeforeRedirect) },
    { add: () => wr.onResponseStarted.addListener(onResponseStarted, filter), remove: () => wr.onResponseStarted.removeListener(onResponseStarted) },
    { add: () => wr.onCompleted.addListener(onCompleted, filter), remove: () => wr.onCompleted.removeListener(onCompleted) },
    { add: () => wr.onErrorOccurred.addListener(onErrorOccurred, filter), remove: () => wr.onErrorOccurred.removeListener(onErrorOccurred) },
  ];
}

// ---- bridge handlers -----------------------------------------------------------------

export interface CaptureHooks {
  /** Called when a capture that includes the site starts (true) and ends (false). */
  siteListening(on: boolean): void;
  extensionStartedAt: number;
}

function countersOf(session: Session): Record<string, number> {
  return { ...session.counters, redactions: session.redactor.total, secrets: session.redactor.secrets.size };
}

export function installCapture(bridge: BridgeClient, hooks: CaptureHooks): void {
  let active: Listener<unknown>[] = [];

  const teardown = (session: Session): void => {
    session.stopping = true;
    for (const l of active) l.remove();
    active = [];
    if (session.profiles.has("site")) hooks.siteListening(false);
  };

  bridge.handle("capture.start", (params) => {
    if (current !== null) throw new BridgeRequestError("already_capturing", `capture ${current.id} is running`);
    const id = String(params["sessionId"] ?? "");
    if (!/^[A-Za-z0-9_-]{1,64}$/.test(id)) throw new BridgeRequestError("bad_request", "sessionId must match [A-Za-z0-9_-]{1,64}");
    const names = Array.isArray(params["profiles"]) ? (params["profiles"] as unknown[]).map(String) : [];
    const profiles = new Set<ProfileName>();
    for (const n of names) {
      const p = profileByName(n);
      if (p === undefined) throw new BridgeRequestError("bad_request", `unknown profile ${n}`);
      profiles.add(p.name);
    }
    if (profiles.size === 0) throw new BridgeRequestError("bad_request", "no profile");
    const redactor = new Redactor();
    const counters: Counters = { lines: 0, requests: 0, bodies: 0, bodyBytes: 0, truncated: 0, abandoned: 0, errors: 0 };
    let session: Session | null = null;
    const transport = new CaptureTransport({
      send: (batch) => bridge.request("capture.write", { sessionId: id, ...batch }, 60_000),
      counters: () => (session === null ? {} : countersOf(session)),
      onPressure: (paused) => {
        for (const body of session?.openBodies ?? []) {
          try {
            if (paused) body.tap?.filter.suspend();
            else body.tap?.filter.resume();
          } catch {
            // The stream may have ended already.
          }
        }
      },
    });
    session = { id, profiles, startedAt: Date.now(), redactor, transport, counters, openBodies: new Set(), pending: new Set(), errorSamples: [], stopping: false, nextBody: 1 };
    current = session;
    active = listeners();
    for (const l of active) l.add();
    if (profiles.has("site")) hooks.siteListening(true);
    push(session, "start", "", { profiles: [...profiles], startedAt: session.startedAt, extensionStartedAt: hooks.extensionStartedAt });
    return {
      sessionId: id,
      startedAt: session.startedAt,
      extensionStartedAt: hooks.extensionStartedAt,
      formatVersion: FORMAT_VERSION,
      limits: { bodyCapBytes: BODY_CAP_BYTES, requestBodyCapBytes: REQUEST_BODY_CAP_BYTES, maxChunkLog: MAX_CHUNK_LOG },
    };
  });

  bridge.handle("capture.stop", async (params) => {
    const session = current;
    if (session === null || session.id !== params["sessionId"]) throw new BridgeRequestError("no_capture", "no such capture is running");
    teardown(session);
    for (const body of [...session.openBodies]) body.end("abandoned");
    while (session.pending.size > 0) await Promise.all([...session.pending]);
    push(session, "end", "", { stoppedAt: Date.now(), counters: countersOf(session), redactions: { ...session.redactor.counts }, errorSamples: session.errorSamples });
    const ok = await session.transport.flush();
    const secrets = session.redactor.secrets.entries();
    session.redactor.secrets.clear();
    current = null;
    return {
      ok,
      counters: countersOf(session),
      redactions: { ...session.redactor.counts },
      transport: { ...session.transport.stats } satisfies TransportStats,
      errorSamples: session.errorSamples,
      secrets,
    };
  });

  bridge.handle("capture.discard", (params) => {
    const session = current;
    if (session === null || session.id !== params["sessionId"]) return { discarded: false };
    teardown(session);
    session.openBodies.clear();
    session.transport.cancel();
    session.redactor.secrets.clear();
    current = null;
    return { discarded: true };
  });

  bridge.handle("capture.status", () => ({
    active: current !== null,
    sessionId: current?.id ?? null,
    counters: current === null ? null : countersOf(current),
    transport: current === null ? null : { ...current.transport.stats },
  }));
}
