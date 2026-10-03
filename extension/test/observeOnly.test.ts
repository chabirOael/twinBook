// C17 and C5 at the extension level: the real-site profile is observe only, and capture
// records leave the extension with layer 1 applied. Drives the real filters.ts and capture.ts
// against a fake WebExtension API (fakeBrowser.ts).

import { beforeAll, beforeEach, describe, expect, it } from "vitest";
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";
import { FakeBridge, installFakeBrowser, matches, type FakeBrowser } from "./fakeBrowser";

const fake: FakeBrowser = installFakeBrowser();
const { Filters } = await import("../src/filters");
const { installCapture } = await import("../src/capture");
const { FilterModes, MOCK_PROFILE, SITE_PROFILE, ModeError } = await import("../src/lib/profiles");
const { assertReplayAllowed, installReplay } = await import("../src/replay");
const { l1Placeholder } = await import("../src/lib/redact");

const enc = new TextEncoder();
const dec = new TextDecoder();
const manifest = JSON.parse(readFileSync(new URL("../manifest.json", import.meta.url), "utf8")) as {
  permissions: string[];
  content_scripts: { matches: string[] }[];
};

const SITE_URLS = ["https://www.facebook.com/", "https://m.facebook.com/home.php", "https://facebook.com/x", "https://web.facebook.com/api/graphql/"];

let bridge: FakeBridge;
let modes: InstanceType<typeof FilterModes>;
let filters: InstanceType<typeof Filters>;

beforeAll(() => {
  bridge = new FakeBridge();
  modes = new FilterModes();
  filters = new Filters(bridge as never, modes);
  filters.install();
  installCapture(bridge as never, { siteListening: (on) => filters.setSiteListening(on), extensionStartedAt: 1 });
  installReplay(bridge as never);
});

beforeEach(() => {
  bridge.requests.length = 0;
  bridge.events.length = 0;
});

function blockingListenersFor(url: string): { event: string; extra: string[] }[] {
  return Object.entries(fake.webRequest)
    .filter(([, v]) => typeof v === "object" && v !== null && "listeners" in v)
    .flatMap(([name, ev]) =>
      (ev as { listeners: { urls: string[]; extra: string[] }[] }).listeners.filter((l) => l.extra.includes("blocking") && l.urls.some((p) => matches(p, url))).map((l) => ({ event: name, extra: l.extra })),
    );
}

const COOKIE = "c_user=100012345678901; xs=12%3AsEcReTxS%3A2%3A1696000000; datr=DaTrVaLuE123";
const TOKEN = "NAcMSiteToken123:17:1696";
const LSD = "AVqSiteLsd99";

const siteHtml = [
  "<!DOCTYPE html><html><head><title>Facebook</title></head><body>",
  `<script>require("ServerJS").handle({"define":[["DTSGInitialData",[],{"token":"${TOKEN}"},258],["LSD",[],{"token":"${LSD}"},323]]});</script>`,
  `<form><input type="hidden" name="lsd" value="${LSD}"></form>`,
  `<script type="application/json" data-sjs>{"require":[["X",{"is_sponsored":true,"sponsored_data":{"ad_id":"9"}}]]}</script>`,
  `<script>window.__u = "100012345678901";</script>`,
  "</body></html>",
].join("");

describe("real-site profile is observe only (C17)", () => {
  it("manifest: no content script on the site; host permissions present", () => {
    const scriptMatches = manifest.content_scripts.flatMap((c) => c.matches);
    expect(scriptMatches).toEqual(["http://127.0.0.1/anchor", "http://localhost/anchor"]);
    for (const url of SITE_URLS) expect(scriptMatches.some((p) => matches(p, url))).toBe(false);
    expect(manifest.permissions).toEqual(expect.arrayContaining(["*://facebook.com/*", "*://*.facebook.com/*"]));
  });

  it("source: no API that injects into pages exists anywhere in the extension", () => {
    const files: string[] = [];
    const walk = (dir: string): void => {
      for (const f of readdirSync(dir)) {
        const p = join(dir, f);
        if (statSync(p).isDirectory()) walk(p);
        else if (p.endsWith(".ts")) files.push(p);
      }
    };
    walk(new URL("../src", import.meta.url).pathname);
    for (const f of files) {
      const text = readFileSync(f, "utf8");
      for (const api of ["executeScript", "insertCSS", "contentScripts.register", "userScripts", "browser.tabs", "scripting."]) {
        expect(text.includes(api), `${f} uses ${api}`).toBe(false);
      }
      // A blocking response never cancels or redirects; only replay.ts rewrites request headers.
      // The one exception is strict mode (strict.ts, its own tests in strict.test.ts): off by
      // default, never active during a capture, and limited to the listed logging endpoints.
      if (!f.endsWith("strict.ts")) expect(/\bcancel\s*:/.test(text), `${f} cancels`).toBe(false);
      expect(/redirectUrl\s*:/.test(text), `${f} redirects`).toBe(false);
      expect(/return\s*\{\s*responseHeaders/.test(text), `${f} rewrites response headers`).toBe(false);
      if (!f.endsWith("replay.ts")) expect(/return\s*\{\s*requestHeaders/.test(text), `${f} rewrites request headers`).toBe(false);
    }
  });

  it("mode: enforce is refused for the site, observe and off are allowed", () => {
    const m = new FilterModes();
    expect(m.get(SITE_PROFILE)).toBe("observe");
    expect(() => m.set(SITE_PROFILE, "enforce")).toThrow(ModeError);
    m.set(SITE_PROFILE, "off");
    m.set(SITE_PROFILE, "observe");
    expect(SITE_PROFILE.allowedModes).toEqual(["observe", "off"]);
    for (const mode of ["enforce", "observe", "off"] as const) m.set(MOCK_PROFILE, mode);
  });

  it("replay: refused for every site host, allowed for the mock", () => {
    for (const url of SITE_URLS) expect(() => assertReplayAllowed(url)).toThrow(/not allowed/);
    expect(() => assertReplayAllowed("https://evil.example/")).toThrow(/not allowed/);
    expect(() => assertReplayAllowed("http://127.0.0.1:4000/session/echo")).not.toThrow();
  });

  it("listeners: without a capture, no blocking listener covers any site URL", () => {
    for (const url of SITE_URLS) expect(blockingListenersFor(url)).toEqual([]);
    // The replay header rewrite listener covers only the mock.
    expect(blockingListenersFor("http://127.0.0.1:1/x").map((l) => l.event).sort()).toEqual(["onBeforeRequest", "onBeforeSendHeaders", "onHeadersReceived"]);
  });

  it("capture: the site document passes byte for byte, the probe reports, records are redacted", async () => {
    await bridge.call("capture.start", { sessionId: "t1", profiles: ["site"] });
    for (const url of SITE_URLS) expect(blockingListenersFor(url)).toEqual([{ event: "onHeadersReceived", extra: ["blocking", "responseHeaders"] }]);

    const base = { requestId: "r1", url: `https://m.facebook.com/?lsd=${LSD}`, method: "GET", type: "main_frame", timeStamp: 1000, frameId: 0, parentFrameId: -1, tabId: 1 };
    fake.webRequest.onBeforeRequest.fire({ ...base });
    fake.webRequest.onSendHeaders.fire({ ...base, requestHeaders: [{ name: "Cookie", value: COOKIE }, { name: "User-Agent", value: "UA" }] });
    const responseHeaders = [
      { name: "Content-Type", value: "text/html; charset=utf-8" },
      { name: "Set-Cookie", value: "fr=FrCookieValue77; expires=Sat, 01 Jan 2028 00:00:00 GMT; Max-Age=7776000; path=/; domain=.facebook.com; secure; httponly; SameSite=None\nsb=SbCookieValue88; path=/; secure" },
      { name: "Content-Encoding", value: "br" },
    ];
    const results = fake.webRequest.onHeadersReceived.fire({ ...base, statusCode: 200, statusLine: "HTTP/2.0 200 OK", responseHeaders });
    // Every listener (blocking or not) returns {} or nothing: no cancel, redirect or header change.
    for (const r of results) expect(r === undefined || JSON.stringify(r) === "{}").toBe(true);

    const filter = fake.filters.get("r1")!;
    const input = enc.encode(siteHtml);
    filter.deliver(input, [17, 200, 201, 333]);
    expect(dec.decode(filter.output())).toBe(siteHtml);
    expect(filter.output()).toEqual(input);
    fake.webRequest.onCompleted.fire({ ...base, statusCode: 200, fromCache: false, ip: "157.240.0.35" });
    await new Promise((r) => setTimeout(r, 0));

    const stop = await bridge.call("capture.stop", { sessionId: "t1" });
    for (const url of SITE_URLS) expect(blockingListenersFor(url)).toEqual([]);
    expect(stop["ok"]).toBe(true);
    expect((stop["counters"] as { secrets: number }).secrets).toBe((stop["secrets"] as unknown[]).length);

    const lines = bridge.lines();
    const text = JSON.stringify(lines);
    const bodies = bridge.bodies();
    const body = dec.decode(bodies.get([...bodies.keys()].find((k) => k.startsWith("bodies/r1-"))!)!);
    // C5: values gone from everything that left the extension, names and attributes kept.
    for (const secret of ["100012345678901", "12%3AsEcReTxS", "DaTrVaLuE123", "FrCookieValue77", "SbCookieValue88", TOKEN, LSD]) {
      expect(text.includes(secret), `lines contain ${secret}`).toBe(false);
    }
    for (const s of [TOKEN, LSD]) expect(body.includes(s), `body contains ${s}`).toBe(false);
    expect(text).toContain(`c_user=${l1Placeholder(15)}`);
    expect(text).toContain("Max-Age=7776000; path=/; domain=.facebook.com; secure; httponly; SameSite=None");
    expect(body).toHaveLength(siteHtml.length);
    // Not keyed, so layer 1 cannot know it yet: left to layer 2 (the cookie value is remembered).
    expect(body).toContain('window.__u = "100012345678901"');

    const bodyLine = lines.find((l) => l["ev"] === "body")!;
    expect(bodyLine).toMatchObject({ status: "complete", size: input.length, recorded: input.length, truncated: false, contentEncoding: "br", chunkCount: 5 });
    const observe = lines.find((l) => l["ev"] === "observe")!;
    expect(observe).toMatchObject({ mode: "observe", kind: "document", profile: "site" });
    expect((observe["probe"] as { candidateTotals: object }).candidateTotals).toEqual({ is_sponsored: 1, sponsored_data: 1, ad_id: 1 });
    expect((observe["stats"] as { bytesIn: number; bytesOut: number }).bytesOut).toBe(input.length);
    expect(lines.find((l) => l["ev"] === "security")).toMatchObject({ security: { protocolVersion: "TLSv1.3" } });
    expect(JSON.stringify(lines.find((l) => l["ev"] === "security"))).not.toContain("certificates");

    const secrets = Object.fromEntries((stop["secrets"] as { value: string; label: string }[]).map((s) => [s.value, s.label]));
    expect(secrets).toMatchObject({ "100012345678901": "cookie:c_user", FrCookieValue77: "cookie:fr", [TOKEN]: "field:token", [LSD]: "field:lsd" });
    expect(bridge.events.filter((e) => e.name === "filter.stats").every((e) => !String(e.data["url"]).includes(LSD))).toBe(true);
  });

  it("capture: a guarded XHR response of the site passes byte for byte; scripts and media get no stream filter", async () => {
    await bridge.call("capture.start", { sessionId: "t2", profiles: ["site"] });
    const json = 'for (;;);{"__ar":1,"payload":{"ad_id":"1","client_token":"ct"}}';
    const d = { requestId: "x1", url: "https://m.facebook.com/async/wbloks/fetch/?appid=x", method: "POST", type: "xmlhttprequest", timeStamp: 1, documentUrl: "https://m.facebook.com/" };
    fake.webRequest.onHeadersReceived.fire({ ...d, statusCode: 200, responseHeaders: [{ name: "Content-Type", value: "application/x-javascript; charset=utf-8" }] });
    const f = fake.filters.get("x1")!;
    f.deliver(enc.encode(json), [3, 9, 10]);
    expect(dec.decode(f.output())).toBe(json);
    fake.webRequest.onHeadersReceived.fire({ requestId: "s1", url: "https://static.xx.fbcdn.net/rsrc.php/a.js", type: "script", timeStamp: 1, documentUrl: "https://m.facebook.com/", responseHeaders: [{ name: "Content-Type", value: "text/javascript" }] });
    fake.webRequest.onHeadersReceived.fire({ requestId: "s2", url: "https://m.facebook.com/x.mp4", type: "media", timeStamp: 1, responseHeaders: [{ name: "Content-Type", value: "video/mp4" }] });
    expect(fake.filters.has("s1")).toBe(false);
    expect(fake.filters.has("s2")).toBe(false);
    const stop = await bridge.call("capture.stop", { sessionId: "t2" });
    expect(stop["ok"]).toBe(true);
    const observe = bridge.lines().find((l) => l["ev"] === "observe" && l["rid"] === "x1")!;
    expect((observe["probe"] as { candidateTotals: object }).candidateTotals).toEqual({ ad_id: 1, client_token: 1 });
    // Third-party script of a site page: metadata recorded, no body.
    expect(bridge.lines().some((l) => l["ev"] === "headers" && l["rid"] === "s1" && l["own"] === false)).toBe(true);
    expect([...bridge.bodies().keys()]).toEqual(["bodies/x1-1.res"]);
  });

  it("mock observe mode: the page gets the unfiltered input, the decisions are reported", async () => {
    const stream = [
      JSON.stringify({ data: { viewer: { news_feed: { edges: [{ node: { id: "1", mock_sponsored: true } }, { node: { id: "2" } }] } } } }),
      JSON.stringify({ data: { node: { id: "3", mock_sponsored: true } }, path: ["viewer", "news_feed", "edges", 2], extensions: { is_final: true } }),
    ].join("\n") + "\n";
    const run = (mode: "enforce" | "observe", id: string) => {
      modes.set(MOCK_PROFILE, mode);
      fake.webRequest.onBeforeRequest.fire({ requestId: id, url: "http://127.0.0.1:5000/api/graphql/?x", method: "POST", type: "xmlhttprequest", timeStamp: 1 });
      const f = fake.filters.get(id)!;
      f.deliver(enc.encode(stream), [7, 50]);
      const stats = bridge.events.find((e) => e.name === "filter.stats" && e.data["requestId"] === id)!.data;
      return { out: dec.decode(f.output()), stats: stats["stats"] as Record<string, number>, mode: stats["mode"] };
    };
    const enforce = run("enforce", "m1");
    const observe = run("observe", "m2");
    modes.set(MOCK_PROFILE, "enforce");
    expect(enforce.out).not.toBe(stream);
    expect(observe.out).toBe(stream);
    expect(observe.mode).toBe("observe");
    for (const k of ["documents", "kept", "dropped", "replaced"]) expect(observe.stats[k]).toBe(enforce.stats[k]);
    expect(observe.stats["replaced"]).toBe(2);
  });
});
