// C20: the HAR importer converts a synthetic HAR (built here from the mock's pages, the way
// desktop Firefox exports them) with planted secrets, and none survive in what it writes.

import { describe, expect, it } from "vitest";
import { mkdtempSync, readdirSync, readFileSync, statSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createHash } from "node:crypto";
import { importHar, type Har, type HarEntry } from "../tools/harImport";
import { variants } from "../src/lib/taint";

const DTSG = "NAcHarToken_9f8e7d6c5b4a:17:1696000000";
const LSD = "AVqHarLsdValue77";
const XS = "12%3AHarXsValue%3A2%3A1696000000";
const XS_DECODED = "12:HarXsValue:2:1696000000";
const C_USER = "100098765432109";
const DATR = "HarDatrValue_abc123";
const PASSWORD = "#PWD_BROWSER:5:1696000000:HarSecretPassword";
const ORIGIN = "https://m.facebook.com";

function entry(over: Pick<HarEntry, "request" | "response">): HarEntry {
  return { startedDateTime: "2026-10-02T09:00:00.000Z", time: 12, cache: {}, timings: { send: 0, wait: 10, receive: 2 }, serverIPAddress: "157.240.0.35", ...over };
}

function syntheticHar(): Har {
  const cookieHeader = `c_user=${C_USER}; xs=${XS}; datr=${DATR}`;
  const reqCookies = [
    { name: "c_user", value: C_USER },
    { name: "xs", value: XS },
    { name: "datr", value: DATR },
  ];
  const html = `<!DOCTYPE html><html><body><script>window.__boot=["${DTSG}"];</script><form><input type="hidden" name="lsd" value="${LSD}"></form><div id="u">${C_USER}</div></body></html>`;
  return {
    log: {
      creator: { name: "Firefox", version: "143.0" },
      entries: [
        entry({
          request: { method: "GET", url: `${ORIGIN}/`, httpVersion: "HTTP/2", headers: [{ name: "Accept", value: "text/html" }, { name: "Cookie", value: cookieHeader }], cookies: reqCookies },
          response: {
            status: 200,
            statusText: "OK",
            httpVersion: "HTTP/2",
            headers: [
              { name: "Content-Type", value: "text/html; charset=utf-8" },
              { name: "Set-Cookie", value: `fr=HarFrCookie_0123; expires=Sat, 01 Jan 2028 00:00:00 GMT; Max-Age=7776000; path=/; domain=.facebook.com; secure; httponly; SameSite=None` },
            ],
            cookies: [{ name: "fr", value: "HarFrCookie_0123", path: "/", domain: ".facebook.com", httpOnly: true, secure: true }],
            content: { size: html.length, mimeType: "text/html; charset=utf-8", text: html },
          },
        }),
        entry({
          request: {
            method: "POST",
            url: `${ORIGIN}/api/graphql/`,
            headers: [{ name: "Content-Type", value: "application/x-www-form-urlencoded" }, { name: "X-FB-LSD", value: LSD }, { name: "Cookie", value: cookieHeader }],
            cookies: reqCookies,
            postData: {
              mimeType: "application/x-www-form-urlencoded",
              params: [
                { name: "av", value: C_USER },
                { name: "__user", value: C_USER },
                { name: "fb_dtsg", value: DTSG },
                { name: "jazoest", value: "25512" },
                { name: "lsd", value: LSD },
                { name: "__rev", value: "1007000000" },
                { name: "fb_api_req_friendly_name", value: "MockFeedQuery" },
              ],
            },
          },
          response: {
            status: 200,
            headers: [{ name: "Content-Type", value: "application/json" }],
            content: { mimeType: "application/json", text: btoa(`{"data":{"viewer":{"id":"${C_USER}","echo":"${XS_DECODED}"}}}\n{"extensions":{"is_final":true}}`), encoding: "base64" },
          },
        }),
        entry({
          request: {
            method: "POST",
            url: `${ORIGIN}/login/device-based/regular/login/?refsrc=x&lsd=${LSD}`,
            headers: [{ name: "Content-Type", value: "application/x-www-form-urlencoded" }],
            postData: { mimeType: "application/x-www-form-urlencoded", text: `email=owner%40example.test&encpass=${encodeURIComponent(PASSWORD)}&lsd=${LSD}` },
          },
          response: { status: 302, headers: [{ name: "Location", value: `${ORIGIN}/home.php?fb_dtsg_ag=${encodeURIComponent(DTSG)}` }], redirectURL: `${ORIGIN}/home.php?fb_dtsg_ag=${encodeURIComponent(DTSG)}`, content: { mimeType: "text/html", text: "" } },
        }),
        entry({
          request: { method: "GET", url: "https://scontent.xx.fbcdn.net/v/t1/photo.jpg", headers: [{ name: "Referer", value: `${ORIGIN}/` }] },
          response: { status: 200, headers: [{ name: "Content-Type", value: "image/jpeg" }], content: { mimeType: "image/jpeg", size: 1000, text: "/9j/AAAA", encoding: "base64" } },
        }),
      ],
    },
  };
}

function allFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((f) => (statSync(join(dir, f)).isDirectory() ? allFiles(join(dir, f)) : [join(dir, f)]));
}

describe("HAR importer", () => {
  it("writes a finalized session in which no planted secret survives", () => {
    const out = mkdtempSync(join(tmpdir(), "har-"));
    const r = importHar(syntheticHar(), out, "har-test");
    expect(r.entries).toBe(4);
    expect(r.bodies).toBe(2);
    expect(r.verifyHits).toBe(0);

    const files = allFiles(r.dir);
    const names = files.map((f) => f.slice(r.dir.length + 1)).sort();
    expect(names).toEqual(["FINALIZED", "bodies/har1-1.res", "bodies/har2-2.res", "checksums.sha256", "events.ndjson", "session.json"]);
    const everything = files.map((f) => readFileSync(f, "latin1")).join("\n");
    const planted = { DTSG, LSD, XS, XS_DECODED, C_USER, DATR, PASSWORD, fr: "HarFrCookie_0123", email: "owner@example.test" };
    let hits = 0;
    for (const [name, value] of Object.entries(planted)) {
      for (const v of variants(value)) {
        if (everything.includes(v)) {
          hits++;
          console.error(`found ${name} as ${v}`);
        }
      }
    }
    expect(hits).toBe(0);

    // Names, attributes and shape fields stay; placeholders name what was there.
    const events = readFileSync(join(r.dir, "events.ndjson"), "utf8");
    expect(events).toContain("Max-Age=7776000; path=/; domain=.facebook.com; secure; httponly; SameSite=None");
    expect(events).toContain('["__rev","1007000000"]');
    expect(events).toContain('["fb_api_req_friendly_name","MockFeedQuery"]');
    expect(events).toContain("!T:cookie:c_user!");
    const html = readFileSync(join(r.dir, "bodies/har1-1.res"), "utf8");
    expect(html).toContain('window.__boot=["!T:field:fb_dtsg!"]');
    expect(html).toContain('<div id="u">!T:cookie:c_user!</div>');
    const json = readFileSync(join(r.dir, "bodies/har2-2.res"), "utf8");
    expect(json).toBe('{"data":{"viewer":{"id":"!T:cookie:c_user!","echo":"!T:cookie:xs!"}}}\n{"extensions":{"is_final":true}}');

    // Checksums and FINALIZED, as the app writes them.
    const sums = readFileSync(join(r.dir, "checksums.sha256"), "utf8");
    for (const line of sums.trim().split("\n")) {
      const [hash, name] = line.split("  ");
      expect(createHash("sha256").update(readFileSync(join(r.dir, name!))).digest("hex")).toBe(hash);
    }
    expect(readFileSync(join(r.dir, "FINALIZED"), "utf8")).toBe(`twinbook-capture 1\nchecksums ${createHash("sha256").update(sums).digest("hex")}\n`);
    const session = JSON.parse(readFileSync(join(r.dir, "session.json"), "utf8"));
    expect(session.taint.verifyHits).toBe(0);
    expect(session.taint.labels).toEqual(expect.arrayContaining(["cookie:c_user", "cookie:xs", "field:fb_dtsg", "field:lsd", "field:encpass", "field:email"]));
    expect(JSON.stringify(session)).not.toContain(C_USER);
  });

  it("refuses to overwrite a session", () => {
    const out = mkdtempSync(join(tmpdir(), "har-"));
    importHar(syntheticHar(), out, "same");
    expect(() => importHar(syntheticHar(), out, "same")).toThrow(/exists/);
  });
});
