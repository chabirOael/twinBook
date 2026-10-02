// Reading and writing capture sessions on the development machine (docs/CAPTURE.md section 3).
// Used by the offline tools (re-scrub, scan, findings, sanitizer). Bodies are handled as
// latin1 strings, one char per byte, like the extension's redaction code.

import { createHash } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, readdirSync, renameSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { bytesToLatin1, latin1ToBytes } from "../src/lib/bytes";
import { FORMAT_VERSION } from "../src/lib/record";

export interface SessionLine {
  v: number;
  ev: string;
  rid: string;
  profile?: string;
  own?: boolean;
  t?: number;
  d?: Record<string, unknown>;
  [field: string]: unknown;
}

export interface LoadedSession {
  dir: string;
  /** session.json, parsed. */
  session: Record<string, unknown>;
  /** events.ndjson as a latin1 string. */
  eventsText: string;
  /** Body files ("bodies/<name>") as latin1 strings. */
  bodies: Map<string, string>;
}

export function sha256(data: Uint8Array | string): string {
  return createHash("sha256").update(data).digest("hex");
}

/** True if `dir` holds a finalized session whose FINALIZED line matches checksums.sha256. */
export function isFinalized(dir: string): boolean {
  if (!existsSync(join(dir, "FINALIZED")) || !existsSync(join(dir, "checksums.sha256"))) return false;
  const fin = readFileSync(join(dir, "FINALIZED"), "latin1");
  const expected = /checksums ([0-9a-f]{64})/.exec(fin)?.[1];
  return expected === sha256(readFileSync(join(dir, "checksums.sha256")));
}

export function loadSession(dir: string): LoadedSession {
  if (!isFinalized(dir)) throw new Error(`${dir} is not a finalized session`);
  const session = JSON.parse(readFileSync(join(dir, "session.json"), "utf8")) as Record<string, unknown>;
  const eventsText = bytesToLatin1(readFileSync(join(dir, "events.ndjson")));
  const bodies = new Map<string, string>();
  const bodyDir = join(dir, "bodies");
  if (existsSync(bodyDir)) for (const f of readdirSync(bodyDir).sort()) bodies.set(`bodies/${f}`, bytesToLatin1(readFileSync(join(bodyDir, f))));
  return { dir, session, eventsText, bodies };
}

/** Parsed lines of events.ndjson (decoded as UTF-8). */
export function parseLines(eventsText: string): SessionLine[] {
  const text = new TextDecoder().decode(latin1ToBytes(eventsText));
  return text
    .split("\n")
    .filter((l) => l.length > 0)
    .map((l) => JSON.parse(l) as SessionLine);
}

/**
 * Writes a finalized session: every file, checksums.sha256, then FINALIZED, into a temporary
 * directory that is renamed into place, so a half-written session never appears.
 */
export function writeFinalizedSession(dir: string, files: Map<string, string>): void {
  if (existsSync(dir)) throw new Error(`${dir} exists`);
  const tmp = join(dirname(dir), `.partial-${dir.split("/").pop()!}`);
  rmSync(tmp, { recursive: true, force: true });
  mkdirSync(join(tmp, "bodies"), { recursive: true });
  const sums: string[] = [];
  for (const [name, data] of files) {
    const bytes = latin1ToBytes(data);
    mkdirSync(dirname(join(tmp, name)), { recursive: true });
    writeFileSync(join(tmp, name), bytes);
    sums.push(`${sha256(bytes)}  ${name}`);
  }
  const checksums = sums.sort().join("\n") + "\n";
  writeFileSync(join(tmp, "checksums.sha256"), checksums);
  writeFileSync(join(tmp, "FINALIZED"), `twinbook-capture ${FORMAT_VERSION}\nchecksums ${sha256(checksums)}\n`);
  renameSync(tmp, dir);
}

/** Sessions in `root` whose id is at least `minId` (string order), finalized, not derived copies. */
export function listSessions(root: string, filter: (id: string) => boolean): string[] {
  if (!existsSync(root)) return [];
  return readdirSync(root)
    .filter((id) => !id.startsWith(".") && filter(id) && isFinalized(join(root, id)))
    .sort();
}
