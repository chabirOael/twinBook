// M2b fixture tests (docs/prompts/M2b.md 5.5 and 5.6), on the committed fixtures only:
// - every GraphQL and preload fixture passes the stream filter in observe mode with the probe:
//   no parse failure, output identical to input, at arbitrary chunk boundaries;
// - ad rules v1 in enforce mode on the feed fixtures remove exactly the expected edges, keep
//   every other line byte for byte, and keep the final-document rule;
// - the leak test runs when the raw sessions are on this machine and is skipped otherwise.

import { describe, expect, it } from "vitest";
import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { createAdRule, type AdRules } from "../src/lib/adRules";
import { NdjsonStreamFilter } from "../src/lib/ndjsonFilter";
import { Probe } from "../src/lib/probe";
import { rawValues, scanFixtures } from "../tools/fixtures/leak";
import { concatBytes } from "../src/lib/bytes";
import { randomCuts } from "./helpers";

const ROOT = new URL("../../", import.meta.url).pathname;
const FIXTURES = join(ROOT, "fixtures");
const manifest = JSON.parse(readFileSync(join(FIXTURES, "manifest.json"), "utf8")) as {
  sources: { session: string }[];
  files: { file: string; kind: string; query: string; documents?: number }[];
  expected: { adRemovals: Record<string, { removed: number[]; droppedDocuments: number; finalMarkers: number }> };
};
const rules = JSON.parse(readFileSync(join(ROOT, "rules", "ads-v1.json"), "utf8")) as AdRules;
const enc = new TextEncoder();
const dec = new TextDecoder();

function run(filter: NdjsonStreamFilter, input: Uint8Array, cuts: number[]): Uint8Array {
  const out: Uint8Array[] = [];
  let prev = 0;
  for (const c of [...cuts, input.length]) {
    out.push(filter.push(input.slice(prev, c)));
    prev = c;
  }
  out.push(filter.end());
  return concatBytes(out);
}

const responses = manifest.files.filter((f) => f.kind === "graphql-response" || f.kind === "preload");

describe("fixtures: manifest", () => {
  it("lists every fixture file, and every listed file exists", () => {
    const onDisk: string[] = [];
    const walk = (d: string): void => {
      for (const f of readdirSync(d)) {
        const p = join(d, f);
        if (statSync(p).isDirectory()) walk(p);
        else onDisk.push(p.slice(FIXTURES.length + 1));
      }
    };
    for (const sub of ["graphql", "preload"]) walk(join(FIXTURES, sub));
    expect(onDisk.sort()).toEqual(manifest.files.map((f) => f.file).sort());
    expect(responses.length).toBeGreaterThan(30);
  });
});

describe("fixtures: observe mode with the probe (5.6)", () => {
  for (const f of responses) {
    it(`${f.file} passes unchanged`, () => {
      const input = readFileSync(join(FIXTURES, f.file));
      const probe = new Probe();
      const errors: unknown[] = [];
      const filter = new NdjsonStreamFilter(probe.rule, { observe: true, onError: (e) => errors.push(e) });
      const out = run(filter, input, randomCuts(input.length, 25, input.length));
      expect(errors).toEqual([]);
      expect(filter.stats.failedOpen).toBe(0);
      expect(filter.stats.documents).toBe(f.documents);
      expect(out.length).toBe(input.length);
      expect(out.every((b, i) => b === input[i])).toBe(true);
    });
  }
});

describe("fixtures: ad rules v1 in enforce mode (5.6)", () => {
  const feeds = Object.keys(manifest.expected.adRemovals);
  it("the expected removals cover every recorded ad", () => {
    const total = feeds.reduce((n, f) => n + manifest.expected.adRemovals[f]!.removed.length, 0);
    expect(total).toBe(5);
  });
  for (const file of feeds) {
    it(`${file}: expected edges removed, everything else byte for byte, final document kept`, () => {
      const expected = manifest.expected.adRemovals[file]!;
      const text = readFileSync(join(FIXTURES, file), "utf8");
      const input = enc.encode(text);
      const rule = createAdRule(rules);
      const filter = new NdjsonStreamFilter(rule);
      const out = dec.decode(run(filter, input, randomCuts(input.length, 40, 7)));
      expect(rule.report.removed.map((r) => r.index)).toEqual(expected.removed);
      expect(filter.stats.failedOpen).toBe(0);
      for (const r of rule.report.removed) expect(r.families.length).toBeGreaterThanOrEqual(rules.minFamilies);

      const inLines = text.split("\n").filter(Boolean);
      const outLines = out.split("\n").filter(Boolean);
      // Kept lines appear unchanged and in order; every other output line is a replacement.
      const kept = inLines.filter((l) => outLines.includes(l));
      expect(outLines.filter((l) => inLines.includes(l))).toEqual(kept);
      expect(inLines.length - kept.length).toBe(filter.stats.dropped + filter.stats.replaced);
      for (const l of outLines.filter((x) => !inLines.includes(x))) {
        const doc = JSON.parse(l) as { extensions?: { is_final?: boolean }; data?: { viewer?: { news_feed?: { edges?: unknown[] } } } };
        const isFinalMarker = JSON.stringify(doc) === '{"extensions":{"is_final":true}}';
        const isTrimmedFirstPage = Array.isArray(doc.data?.viewer?.news_feed?.edges);
        expect(isFinalMarker || isTrimmedFirstPage).toBe(true);
      }
      // No removed edge survives anywhere in the output.
      for (const l of outLines) {
        const doc = JSON.parse(l) as { path?: unknown[]; data?: { node?: { th_dat_spo?: unknown } } };
        if (Array.isArray(doc.path) && doc.path[2] === "edges") expect(expected.removed).not.toContain(doc.path[3]);
        expect(doc.data?.node?.th_dat_spo ?? null).toBeNull();
      }
      // Final-document rule: if the input ended with a final document, so does the output.
      const lastIn = JSON.parse(inLines[inLines.length - 1]!) as { extensions?: { is_final?: boolean } };
      const lastOut = JSON.parse(outLines[outLines.length - 1]!) as { extensions?: { is_final?: boolean } };
      if (lastIn.extensions?.is_final === true) expect(lastOut.extensions?.is_final).toBe(true);
      expect(rule.report.finalMarkers).toBe(expected.finalMarkers);
    });
  }
});

const rawDirs = manifest.sources.flatMap((s) => [join(ROOT, "captures", `${s.session}-rescrub`), join(ROOT, "captures", s.session)]).filter((d) => existsSync(join(d, "FINALIZED")));
const haveRaw = rawDirs.length > 0;

describe("fixtures: pattern scans (always)", () => {
  it("no e-mail address, phone number, URL outside the example domains, or site host name", () => {
    const res = scanFixtures(FIXTURES, undefined);
    console.info(`fixture pattern scan: ${res.files} files, ${res.strings} strings and keys, ${res.numbers} numbers; e-mails ${res.emails}, phones ${res.phones}, foreign URLs ${res.foreignUrls}, site host names ${res.siteHostMentions}`);
    expect([res.emails, res.phones, res.foreignUrls, res.siteHostMentions]).toEqual([0, 0, 0, 0]);
  });
});

describe.skipIf(!haveRaw)("fixtures: leak test against the raw sessions (skipped when they are not on this machine)", () => {
  it("no non-structural raw string, identifier token or remapped number appears in the fixtures", () => {
    const raw = rawValues(rawDirs, FIXTURES);
    const res = scanFixtures(FIXTURES, raw);
    console.info(
      `fixture leak test: raw sessions ${rawDirs.length}; raw non-structural strings ${raw.strings.size}, identifier tokens ${raw.tokens.size}, remapped numbers ${raw.numbers.size}, non-schema keys ${raw.keys.size}; fixture files ${res.files}, strings and keys ${res.strings}, tokens ${res.tokens}, numbers ${res.numbers}; leaks ${res.leaks.length}`,
    );
    const byKind = new Map<string, number>();
    for (const l of res.leaks) byKind.set(`${l.kind} len ${l.length} in ${l.file.split("/")[1]}`, (byKind.get(`${l.kind} len ${l.length} in ${l.file.split("/")[1]}`) ?? 0) + 1);
    if (res.leaks.length > 0) console.info([...byKind].sort((a, b) => b[1] - a[1]).slice(0, 40).map(([k, v]) => `${v} ${k}`).join("\n"));
    expect(res.leaks).toEqual([]);
  }, 120_000);
});
