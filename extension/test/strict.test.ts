// S9 at the extension level: strict mode cancels exactly the listed logging endpoints on the
// site's and the mock's own hosts, is off by default, and is never active while a capture runs.
// Drives the real strict.ts, capture.ts and filters.ts against the fake WebExtension API.

import { beforeAll, describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { FakeBridge, installFakeBrowser, matches, type FakeBrowser } from "./fakeBrowser";

const fake: FakeBrowser = installFakeBrowser();
const { StrictMode, STRICT_ENDPOINTS, isStrictTarget, strictUrlPatterns } = await import("../src/strict");
const { Filters } = await import("../src/filters");
const { installCapture } = await import("../src/capture");
const { FilterModes } = await import("../src/lib/profiles");

const data = JSON.parse(readFileSync(new URL("../data/strict-endpoints.json", import.meta.url), "utf8")) as { endpoints: { path: string; why: string }[] };

let bridge: FakeBridge;
let strict: InstanceType<typeof StrictMode>;

beforeAll(() => {
  bridge = new FakeBridge();
  strict = new StrictMode(bridge as never);
  strict.install();
  const filters = new Filters(bridge as never, new FilterModes());
  filters.install();
  installCapture(bridge as never, { siteListening: (on) => filters.setSiteListening(on), captureActive: (on) => strict.setCaptureActive(on), extensionStartedAt: 1 });
});

const LISTED = [
  "https://m.facebook.com/ajax/weblite_load_logging/",
  "https://m.facebook.com/ajax/weblite_load_logging/?__a=1",
  "https://m.facebook.com/ajax/weblite_resources_timing_logging/",
  "https://www.facebook.com/ajax/weblite_load_logging",
  "https://facebook.com/ajax/weblite_resources_timing_logging/",
  "http://127.0.0.1:43210/ajax/weblite_load_logging/?run=x",
];
const NOT_LISTED = [
  "https://m.facebook.com/ajax/weblite_load_logging_more/",
  "https://m.facebook.com/ajax/bz",
  "https://m.facebook.com/a/bz",
  "https://m.facebook.com/",
  "https://m.facebook.com/ajax/weblite_load_logging/x",
  "https://example.com/ajax/weblite_load_logging/",
  "https://m.facebook.com.evil.example/ajax/weblite_load_logging/",
  "https://static.xx.fbcdn.net/ajax/weblite_load_logging/",
];

/** What the blocking onBeforeRequest listeners answer for `url` (only those whose patterns match). */
function answers(url: string): unknown[] {
  return fake.webRequest.onBeforeRequest.listeners
    .filter((l) => l.extra.includes("blocking") && l.urls.some((p) => matches(p, url)))
    .map((l) => l.fn({ url, requestId: "s", type: "beacon", method: "POST", timeStamp: 1 } as never));
}

function cancels(url: string): boolean {
  return answers(url).some((r) => (r as { cancel?: boolean } | undefined)?.cancel === true);
}

describe("strict mode (S9)", () => {
  it("data file: every entry has a path and a reason", () => {
    expect(data.endpoints.length).toBeGreaterThan(0);
    for (const e of data.endpoints) {
      expect(e.path).toMatch(/^\/[a-z_/]+$/);
      expect(e.why.length).toBeGreaterThan(20);
    }
    expect(STRICT_ENDPOINTS.map((e) => e.path)).toEqual(data.endpoints.map((e) => e.path));
  });

  it("pure matcher: listed paths on own hosts only", () => {
    for (const u of LISTED) expect(isStrictTarget(u), u).toBe(true);
    for (const u of NOT_LISTED) expect(isStrictTarget(u), u).toBe(false);
    // Patterns are narrow: no pattern matches a site page or another path.
    for (const p of strictUrlPatterns()) expect(p).toMatch(/\/ajax\/weblite_/);
  });

  it("off by default: no blocking listener covers any listed endpoint", async () => {
    expect((await bridge.call("strict.describe"))["enabled"]).toBe(false);
    for (const u of LISTED) expect(cancels(u), u).toBe(false);
  });

  it("on: cancels exactly the listed endpoints", async () => {
    const r = await bridge.call("strict.set", { enabled: true });
    expect(r).toMatchObject({ enabled: true, active: true });
    for (const u of LISTED) expect(cancels(u), u).toBe(true);
    for (const u of NOT_LISTED) expect(cancels(u), u).toBe(false);
    const d = await bridge.call("strict.describe");
    expect(d["cancelled"]).toBe(LISTED.length);
  });

  it("a capture switches it off for its whole duration, also when turned on meanwhile", async () => {
    await bridge.call("capture.start", { sessionId: "s1", profiles: ["site"] });
    expect((await bridge.call("strict.describe"))["active"]).toBe(false);
    for (const u of LISTED) expect(cancels(u), u).toBe(false);
    // During a capture every blocking listener of a site URL answers {} (observe only).
    for (const u of LISTED.filter((x) => x.includes("facebook"))) {
      for (const a of answers(u)) expect(a === undefined || JSON.stringify(a) === "{}").toBe(true);
    }
    await bridge.call("strict.set", { enabled: true });
    expect((await bridge.call("strict.describe"))).toMatchObject({ enabled: true, active: false, capturing: true });
    for (const u of LISTED) expect(cancels(u), u).toBe(false);
    await bridge.call("capture.discard", { sessionId: "s1" });
    expect((await bridge.call("strict.describe"))).toMatchObject({ enabled: true, active: true, capturing: false });
    for (const u of LISTED) expect(cancels(u), u).toBe(true);
  });

  it("a mock capture switches it off too", async () => {
    await bridge.call("capture.start", { sessionId: "s2", profiles: ["mock"] });
    expect(cancels(LISTED[5]!)).toBe(false);
    await bridge.call("capture.discard", { sessionId: "s2" });
    expect(cancels(LISTED[5]!)).toBe(true);
  });

  it("off again: the listener is removed", async () => {
    await bridge.call("strict.set", { enabled: false });
    for (const u of LISTED) expect(cancels(u), u).toBe(false);
    expect(fake.webRequest.onBeforeRequest.listeners.filter((l) => l.urls.some((p) => p.includes("weblite")))).toEqual([]);
  });
});
