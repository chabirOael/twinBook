// Start-up probe for the content blocker (uBlock Origin), which has no bridge of its own.
//
// The engine loads a tiny page in its headless bootstrap session whose only subresource is a
// probe URL on the loopback interface that a default uBlock Origin list blocks. This file
// watches those requests (metadata only, never blocking) and tells the app what happened to
// each: "blocked" when another extension cancelled it (Gecko reports NS_ERROR_ABORT), "passed"
// when it went out (nothing listens on the probe port, so it fails with a connection error, or
// it completed). The engine calls the blocker ready after the first "blocked". Probe requests
// carry `twinbook_startup_probe=<id>` and never leave the device.

import type { BridgeClient } from "./lib/bridge";

export const PROBE_PARAM = "twinbook_startup_probe";
const PROBE_PATTERNS = ["http://127.0.0.1/*"];
const KEEP = 32;

export interface ProbeOutcome {
  id: string;
  outcome: "blocked" | "passed" | "timeout";
  error?: string;
  status?: number;
  at: number;
}

export function probeIdOf(url: string): string | null {
  try {
    const u = new URL(url);
    if (u.hostname !== "127.0.0.1") return null;
    return u.searchParams.get(PROBE_PARAM);
  } catch {
    return null;
  }
}

/** Classifies a finished probe request. NS_ERROR_ABORT is what Gecko reports for a request an extension cancelled. */
export function classify(error: string | undefined): "blocked" | "passed" {
  return error === "NS_ERROR_ABORT" ? "blocked" : "passed";
}

export class StartupProbe {
  private readonly outcomes = new Map<string, ProbeOutcome>();
  private readonly waiters = new Map<string, ((o: ProbeOutcome) => void)[]>();

  constructor(private readonly bridge: BridgeClient) {}

  install(): void {
    browser.webRequest.onErrorOccurred.addListener((d) => this.finish(d.url, { error: d.error }), { urls: PROBE_PATTERNS });
    browser.webRequest.onCompleted.addListener((d) => this.finish(d.url, { status: d.statusCode }), { urls: PROBE_PATTERNS });
    this.bridge.handle("probe.result", (params) => this.result(String(params["id"] ?? ""), Number(params["waitMs"] ?? 10_000)));
  }

  private finish(url: string, what: { error?: string; status?: number }): void {
    const id = probeIdOf(url);
    if (id === null || this.outcomes.has(id)) return;
    const o: ProbeOutcome = { id, outcome: what.error !== undefined ? classify(what.error) : "passed", ...what, at: Date.now() };
    this.outcomes.set(id, o);
    while (this.outcomes.size > KEEP) this.outcomes.delete(this.outcomes.keys().next().value!);
    for (const w of this.waiters.get(id) ?? []) w(o);
    this.waiters.delete(id);
  }

  /** The outcome of probe `id`, waiting up to `waitMs` for it. */
  result(id: string, waitMs: number): Promise<ProbeOutcome> {
    const known = this.outcomes.get(id);
    if (known !== undefined) return Promise.resolve(known);
    return new Promise((resolve) => {
      const timer = setTimeout(() => {
        const list = this.waiters.get(id) ?? [];
        this.waiters.set(id, list.filter((w) => w !== done));
        resolve({ id, outcome: "timeout", at: Date.now() });
      }, Math.max(0, Math.min(waitMs, 60_000)));
      const done = (o: ProbeOutcome): void => {
        clearTimeout(timer);
        resolve(o);
      };
      this.waiters.set(id, [...(this.waiters.get(id) ?? []), done]);
    });
  }
}
