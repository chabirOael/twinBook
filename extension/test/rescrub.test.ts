// Offline re-scrub and opaque-value scan (M2b 5.1) on a synthetic finalized session.

import { describe, expect, it } from "vitest";
import { mkdtempSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { bytesToLatin1 } from "../src/lib/bytes";
import { l1Placeholder } from "../src/lib/redact";
import { rescrubSession } from "../tools/rescrub";
import { formatScan, isOpaque, scanSession } from "../tools/opaqueScan";
import { writeFinalizedSession } from "../tools/sessionIo";
import { Session } from "../tools/findings/session";
import { TaintScrubber } from "../src/lib/taint";

const enc = new TextEncoder();
const CAMEL = "CamelTokenValue123456";
const NONCE = "SrvNonceValue98765abc";
const SHARED = "RecurringOpaqueValue0123456789";

function makeSession(): string {
  const root = mkdtempSync(join(tmpdir(), "rescrub-"));
  const dir = join(root, "20261002-000000-site");
  const lines = [
    { v: 1, ev: "start", rid: "", profiles: ["site"] },
    {
      v: 1,
      ev: "request",
      rid: "1",
      profile: "site",
      own: true,
      t: 1,
      d: { url: `https://www.facebook.com/api/graphql/?x=1`, method: "POST", type: "xmlhttprequest" },
      body: { kind: "formData", fields: [["variables", JSON.stringify({ accessToken: CAMEL, trace: SHARED })], ["empty", null]] },
    },
    { v: 1, ev: "request", rid: "2", profile: "site", own: true, t: 2, d: { url: `https://www.facebook.com/ajax/x?trace=${SHARED}`, method: "GET", type: "xmlhttprequest" }, body: { kind: "none" } },
    { v: 1, ev: "body", rid: "1", profile: "site", file: "bodies/1-1.res", status: "complete" },
  ];
  const files = new Map<string, string>([
    ["events.ndjson", bytesToLatin1(enc.encode(lines.map((l) => JSON.stringify(l)).join("\n") + "\n"))],
    ["bodies/1-1.res", `for (;;);{"ServerNonce":"${NONCE}","copy":"${CAMEL}","trace":"${SHARED}"}`],
    ["session.json", JSON.stringify({ format: 1, id: "20261002-000000-site", profiles: ["site"] })],
  ]);
  writeFinalizedSession(dir, files);
  return dir;
}

describe("offline re-scrub", () => {
  it("applies the current rules, scrubs other occurrences, verifies, leaves the original alone", () => {
    const dir = makeSession();
    const before = readFileSync(join(dir, "events.ndjson"), "latin1");
    const r = rescrubSession(dir);
    expect(r.dir).toBe(`${dir}-rescrub`);
    expect(r.verifyHits).toBe(0);
    expect(readFileSync(join(dir, "events.ndjson"), "latin1")).toBe(before);
    const events = readFileSync(join(r.dir, "events.ndjson"), "latin1");
    const body = readFileSync(join(r.dir, "bodies/1-1.res"), "latin1");
    for (const s of [CAMEL, NONCE]) {
      expect(events.includes(s)).toBe(false);
      expect(body.includes(s)).toBe(false);
    }
    // Keyed occurrences keep the length; the unkeyed copy is a layer 2 placeholder.
    expect(body).toContain(`"ServerNonce":"${l1Placeholder(NONCE.length)}"`);
    expect(body).toContain('"copy":"!T:field:accessToken!"');
    // Not a secret key: kept for the scan.
    expect(body).toContain(SHARED);
    const session = JSON.parse(readFileSync(join(r.dir, "session.json"), "utf8")) as { id: string; rescrub: { source: string; taint: { labels: string[] } } };
    expect(session.id).toBe("20261002-000000-site-rescrub");
    expect(session.rescrub.source).toBe("20261002-000000-site");
    expect(session.rescrub.taint.labels).toEqual(["field:ServerNonce", "field:accessToken"]);
    expect(() => rescrubSession(dir)).toThrow(/exists/);
    expect(rescrubSession(dir, { replace: true }).verifyHits).toBe(0);
    expect(() => rescrubSession(r.dir)).toThrow(/already/);
  });
});

describe("opaque-value scan", () => {
  it("lists recurring opaque values by key with lengths and counts, never the value", () => {
    const dir = makeSession();
    const res = scanSession(dir);
    const trace = res.rows.find((x) => x.key === "trace")!;
    expect(trace).toMatchObject({ where: ["body-json", "form-json", "url"], occurrences: 3, distinctValues: 1, recurringValues: 1, maxRecords: 3, minLength: SHARED.length });
    expect(res.suspicious.map((x) => x.key).sort()).toEqual(["ServerNonce", "accessToken"]);
    const text = formatScan(dir, res);
    for (const s of [CAMEL, NONCE, SHARED]) expect(text.includes(s)).toBe(false);
  });

  it("opaque: long token-shaped values only", () => {
    expect(isOpaque(SHARED)).toBe(true);
    expect(isOpaque("short")).toBe(false);
    expect(isOpaque("has spaces in the value here")).toBe(false);
    expect(isOpaque("https://www.facebook.com/a/b/c")).toBe(false);
    expect(isOpaque(l1Placeholder(20))).toBe(false);
    expect(isOpaque("scontent.fdoh1-1.fna.fbcdn.net")).toBe(false);
  });
});

describe("tainted numbers: sessions before and after the quoted placeholder", () => {
  const USER = "100012345678";
  function sessionWith(bodies: Record<string, string>): string {
    const root = mkdtempSync(join(tmpdir(), "bare-"));
    const dir = join(root, "20261002-000001-site");
    const rids = Object.keys(bodies);
    const lines = [
      { v: 1, ev: "start", rid: "", profiles: ["site"] },
      ...rids.flatMap((rid) => [
        { v: 1, ev: "request", rid, profile: "site", own: true, t: 1, d: { url: "https://www.facebook.com/api/graphql/", method: "POST", type: "xmlhttprequest" }, body: { kind: "none" } },
        { v: 1, ev: "body", rid, profile: "site", file: `bodies/${rid}-1.res`, status: "complete" },
      ]),
    ];
    const files = new Map<string, string>([
      ["events.ndjson", lines.map((l) => JSON.stringify(l)).join("\n") + "\n"],
      ["session.json", JSON.stringify({ format: 1, id: "20261002-000001-site", profiles: ["site"] })],
      ...rids.map((rid) => [`bodies/${rid}-1.res`, bodies[rid]!] as [string, string]),
    ]);
    writeFinalizedSession(dir, files);
    return dir;
  }

  it("a session finalized before the change (bare placeholders) is still read", () => {
    const old = `{"data":{"viewer":{"id":!T:cookie:c_user!,"ids":[!T:cookie:c_user!, 2]}}}\n{"data":{"x":1},"path":["a"]}\n`;
    const s = new Session(sessionWith({ "1": old, "2": `for (;;);{"a":!T:cookie:c_user!}for (;;);{"b":1}` }));
    const nd = s.ndjson(s.recs.get("1")!);
    expect(nd.failed).toBe(0);
    expect(nd.docs).toHaveLength(2);
    expect(nd.docs[0]).toEqual({ data: { viewer: { id: 0, ids: [0, 2] } } });
    const g = s.guarded(s.recs.get("2")!);
    expect([g.docs.length, g.failed]).toEqual([2, 0]);
    expect(s.repairedPlaceholders).toBe(3);
  });

  it("a body scrubbed by the current layer 2 is valid JSON as recorded, and the label is kept", () => {
    const scrubber = new TaintScrubber([{ value: USER, label: "cookie:c_user" }]);
    const raw = `{"data":{"viewer":{"id":${USER},"ids":[${USER}, 2],"s":"{\\"actor\\":${USER}}","name":"${USER}"}}}\n`;
    const scrubbed = scrubber.scrub(bytesToLatin1(enc.encode(raw))).text;
    const s = new Session(sessionWith({ "1": scrubbed }));
    const nd = s.ndjson(s.recs.get("1")!);
    expect(nd.failed).toBe(0);
    expect(s.repairedPlaceholders).toBe(0);
    const doc = nd.docs[0] as { data: { viewer: { id: string; ids: unknown[]; s: string; name: string } } };
    expect(doc.data.viewer.id).toBe("!T:cookie:c_user!");
    expect(doc.data.viewer.ids).toEqual(["!T:cookie:c_user!", 2]);
    expect(JSON.parse(doc.data.viewer.s)).toEqual({ actor: "!T:cookie:c_user!" });
    expect(doc.data.viewer.name).toBe("!T:cookie:c_user!");
    expect(scrubbed.includes(USER)).toBe(false);
  });
});
