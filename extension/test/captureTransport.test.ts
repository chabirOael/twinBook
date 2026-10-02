import { describe, expect, it } from "vitest";
import { CaptureTransport, type CaptureBatch } from "../src/lib/captureTransport";

function deferred(): { promise: Promise<void>; resolve: () => void; reject: () => void } {
  let resolve!: () => void;
  let reject!: () => void;
  const promise = new Promise<void>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

const tick = () => new Promise((r) => setTimeout(r, 0));

describe("CaptureTransport", () => {
  it("delivers every item once, in order, one batch at a time", async () => {
    const received: string[] = [];
    let inFlight = 0;
    let maxInFlight = 0;
    const t = new CaptureTransport({
      maxBatchBytes: 100,
      send: async (b: CaptureBatch) => {
        inFlight++;
        maxInFlight = Math.max(maxInFlight, inFlight);
        await tick();
        for (const i of b.items) if ("l" in i) received.push(i.l);
        inFlight--;
      },
    });
    const sent = Array.from({ length: 500 }, (_, i) => `line-${i}`);
    for (const l of sent) t.push({ l });
    expect(await t.flush()).toBe(true);
    expect(received).toEqual(sent);
    expect(maxInFlight).toBe(1);
    expect(t.stats.items).toBe(500);
    expect(t.stats.batches).toBeGreaterThan(10);
  });

  it("retries a failed batch with the same sequence number instead of dropping it", async () => {
    const seqs: number[] = [];
    const delivered: string[] = [];
    let failures = 3;
    const t = new CaptureTransport({
      retryDelaysMs: [1],
      setTimer: (fn) => setTimeout(fn, 0),
      send: async (b) => {
        seqs.push(b.seq);
        if (failures-- > 0) throw new Error("timeout");
        for (const i of b.items) if ("l" in i) delivered.push(i.l);
      },
    });
    t.push({ l: "a" });
    t.push({ l: "b" });
    expect(await t.flush()).toBe(true);
    expect(delivered).toEqual(["a", "b"]);
    expect(seqs).toEqual([1, 1, 1, 1, 2]);
    expect(t.stats.retries).toBe(3);
  });

  it("applies backpressure above the high-water mark and releases it below the low-water mark", async () => {
    const gate = deferred();
    const pressure: boolean[] = [];
    const t = new CaptureTransport({
      maxBatchBytes: 1000,
      highWaterBytes: 5000,
      lowWaterBytes: 1000,
      onPressure: (p) => pressure.push(p),
      send: async () => {
        await gate.promise;
      },
    });
    for (let i = 0; i < 20; i++) t.push({ f: "bodies/1.res", b: "x".repeat(500), last: false });
    expect(t.isPaused).toBe(true);
    expect(pressure).toEqual([true]);
    gate.resolve();
    expect(await t.flush()).toBe(true);
    expect(pressure).toEqual([true, false]);
    expect(t.stats.items).toBe(20);
  });

  it("reports failure after maxAttempts and on cancel", async () => {
    const t = new CaptureTransport({ maxAttempts: 2, retryDelaysMs: [1], send: () => Promise.reject(new Error("down")) });
    t.push({ l: "x" });
    expect(await t.flush()).toBe(false);
    expect(t.failed).toBe(true);
    const c = new CaptureTransport({ send: () => new Promise(() => undefined) });
    c.push({ l: "y" });
    const f = c.flush();
    c.cancel();
    expect(await f).toBe(false);
  });
});
