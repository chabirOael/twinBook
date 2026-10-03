// Strict mode: cancels requests to the site's own logging endpoints (data/strict-endpoints.json).
//
// This is the only place in twin-bridge that cancels a request. It is off by default, the app
// turns it on with `strict.set` when the owner asks for it, and it is never active while a
// capture runs: capture records what the site does, so nothing may be changed then. The blocking
// listener exists only while strict mode is on and no capture runs, and it covers only the
// listed paths on the own hosts of the site and mock profiles.

import endpointsJson from "../data/strict-endpoints.json";
import type { BridgeClient } from "./lib/bridge";
import { MOCK_PROFILE, SITE_PROFILE, type SiteProfile } from "./lib/profiles";

export interface StrictEndpoint {
  path: string;
  why: string;
}

export const STRICT_ENDPOINTS: readonly StrictEndpoint[] = Object.freeze((endpointsJson as { endpoints: StrictEndpoint[] }).endpoints);

const PROFILES: readonly SiteProfile[] = [SITE_PROFILE, MOCK_PROFILE];

/** Match patterns of the listed paths on every own host of the site and mock profiles. */
export function strictUrlPatterns(endpoints: readonly StrictEndpoint[] = STRICT_ENDPOINTS): string[] {
  const out: string[] = [];
  for (const p of PROFILES) {
    for (const pattern of p.urlPatterns) {
      const base = pattern.slice(0, pattern.indexOf("/", pattern.indexOf("://") + 3));
      for (const e of endpoints) out.push(`${base}${e.path.replace(/\/$/, "")}*`);
    }
  }
  return out;
}

/** True if `url` is one of the listed endpoints on an own host of the site or the mock. */
export function isStrictTarget(url: string, endpoints: readonly StrictEndpoint[] = STRICT_ENDPOINTS): boolean {
  let u: URL;
  try {
    u = new URL(url);
  } catch {
    return false;
  }
  if (!PROFILES.some((p) => p.isOwnHost(u.hostname))) return false;
  const path = u.pathname.replace(/\/$/, "");
  return endpoints.some((e) => e.path.replace(/\/$/, "") === path);
}

type BeforeRequest = browser.webRequest._OnBeforeRequestDetails;

export class StrictMode {
  private enabled = false;
  private capturing = false;
  private listener: ((d: BeforeRequest) => browser.webRequest.BlockingResponse) | undefined;
  private cancelled = 0;
  private readonly byPath = new Map<string, number>();

  constructor(private readonly bridge: BridgeClient) {}

  install(): void {
    this.bridge.handle("strict.set", (params) => {
      this.enabled = params["enabled"] === true;
      this.sync();
      return this.describe();
    });
    this.bridge.handle("strict.describe", () => this.describe());
  }

  /** Called by the capture recorder when any capture starts (true) and ends (false). */
  setCaptureActive(on: boolean): void {
    this.capturing = on;
    this.sync();
  }

  get active(): boolean {
    return this.listener !== undefined;
  }

  describe(): Record<string, unknown> {
    return {
      enabled: this.enabled,
      active: this.active,
      capturing: this.capturing,
      endpoints: STRICT_ENDPOINTS.map((e) => e.path),
      cancelled: this.cancelled,
      cancelledByPath: Object.fromEntries(this.byPath),
    };
  }

  private sync(): void {
    const want = this.enabled && !this.capturing;
    if (want && this.listener === undefined) {
      this.listener = (details) => {
        if (!isStrictTarget(details.url)) return {};
        const path = new URL(details.url).pathname;
        this.cancelled++;
        this.byPath.set(path, (this.byPath.get(path) ?? 0) + 1);
        return { cancel: true };
      };
      browser.webRequest.onBeforeRequest.addListener(this.listener, { urls: strictUrlPatterns() }, ["blocking"]);
    } else if (!want && this.listener !== undefined) {
      browser.webRequest.onBeforeRequest.removeListener(this.listener);
      this.listener = undefined;
    }
  }
}
