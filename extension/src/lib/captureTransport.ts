// Lossless transport of capture records to the app.
//
// Records do not travel as bridge events (the app replays only the last 64 events and a slow
// collector can miss some). They travel as bridge requests, `capture.write`, that the app
// answers only after the items are on disk:
//
// - One batch in flight at a time, so batches arrive and are written in order. Every batch has
//   a sequence number; the app ignores a sequence number it has already written, so a batch
//   whose answer was lost can be re-sent safely.
// - A failed batch is retried with backoff, never dropped. After maxAttempts the transport is
//   marked failed; the app then discards the whole session.
// - Backpressure: when more than highWaterBytes are queued, onPressure(true) is called (the
//   recorder suspends the response streams it copies from); below lowWaterBytes,
//   onPressure(false) resumes them.

export type CaptureItem =
  /** One line of events.ndjson (a JSON object without the newline). */
  | { l: string }
  /** A piece of a body file: base64 data appended to file `f`; `last` closes it. */
  | { f: string; b: string; last: boolean };

export interface CaptureBatch {
  seq: number;
  items: CaptureItem[];
  counters: Record<string, number>;
}

export interface TransportOptions {
  send: (batch: CaptureBatch) => Promise<unknown>;
  counters?: () => Record<string, number>;
  maxBatchBytes?: number;
  highWaterBytes?: number;
  lowWaterBytes?: number;
  onPressure?: (paused: boolean) => void;
  retryDelaysMs?: readonly number[];
  maxAttempts?: number;
  setTimer?: (fn: () => void, ms: number) => unknown;
}

export interface TransportStats {
  items: number;
  batches: number;
  bytes: number;
  retries: number;
  queuedBytes: number;
  maxQueuedBytes: number;
  pressureEvents: number;
  failed: boolean;
}

function itemSize(item: CaptureItem): number {
  return "l" in item ? item.l.length + 1 : item.b.length + item.f.length + 16;
}

export class CaptureTransport {
  private readonly options: Required<Omit<TransportOptions, "onPressure" | "counters">> & Pick<TransportOptions, "onPressure" | "counters">;
  private readonly queue: CaptureItem[] = [];
  private queuedBytes = 0;
  private inFlight = false;
  private seq = 1;
  private paused = false;
  private waiters: ((ok: boolean) => void)[] = [];
  readonly stats: TransportStats = { items: 0, batches: 0, bytes: 0, retries: 0, queuedBytes: 0, maxQueuedBytes: 0, pressureEvents: 0, failed: false };

  constructor(options: TransportOptions) {
    this.options = {
      maxBatchBytes: 512 * 1024,
      highWaterBytes: 16 * 1024 * 1024,
      lowWaterBytes: 4 * 1024 * 1024,
      retryDelaysMs: [100, 250, 500, 1000, 2000],
      maxAttempts: 40,
      setTimer: (fn, ms) => setTimeout(fn, ms),
      ...options,
    };
  }

  get failed(): boolean {
    return this.stats.failed;
  }

  get isPaused(): boolean {
    return this.paused;
  }

  push(item: CaptureItem): void {
    this.queue.push(item);
    this.queuedBytes += itemSize(item);
    this.stats.queuedBytes = this.queuedBytes;
    this.stats.maxQueuedBytes = Math.max(this.stats.maxQueuedBytes, this.queuedBytes);
    if (!this.paused && this.queuedBytes > this.options.highWaterBytes) {
      this.paused = true;
      this.stats.pressureEvents++;
      this.options.onPressure?.(true);
    }
    this.pump();
  }

  /** Drops everything queued and stops sending (the session was discarded). */
  cancel(): void {
    this.queue.length = 0;
    this.queuedBytes = 0;
    this.stats.queuedBytes = 0;
    this.stats.failed = true;
    this.settle(false);
  }

  /** Resolves true once everything pushed so far is acknowledged, false if the transport failed. */
  flush(): Promise<boolean> {
    if (this.stats.failed) return Promise.resolve(false);
    if (this.queue.length === 0 && !this.inFlight) return Promise.resolve(true);
    return new Promise((resolve) => this.waiters.push(resolve));
  }

  private settle(ok: boolean): void {
    const w = this.waiters;
    this.waiters = [];
    for (const resolve of w) resolve(ok);
  }

  private pump(): void {
    if (this.inFlight || this.stats.failed || this.queue.length === 0) return;
    const items: CaptureItem[] = [];
    let bytes = 0;
    while (this.queue.length > 0 && (items.length === 0 || bytes + itemSize(this.queue[0]!) <= this.options.maxBatchBytes)) {
      const item = this.queue.shift()!;
      bytes += itemSize(item);
      items.push(item);
    }
    this.inFlight = true;
    this.attempt({ seq: this.seq++, items, counters: {} }, bytes, 1);
  }

  private attempt(batch: CaptureBatch, bytes: number, attempt: number): void {
    if (this.stats.failed) return;
    batch.counters = this.options.counters?.() ?? {};
    this.options.send(batch).then(
      () => {
        this.inFlight = false;
        this.queuedBytes -= bytes;
        this.stats.queuedBytes = this.queuedBytes;
        this.stats.items += batch.items.length;
        this.stats.batches++;
        this.stats.bytes += bytes;
        if (this.paused && this.queuedBytes < this.options.lowWaterBytes) {
          this.paused = false;
          this.options.onPressure?.(false);
        }
        if (this.queue.length === 0) this.settle(true);
        else this.pump();
      },
      () => {
        if (attempt >= this.options.maxAttempts) {
          this.inFlight = false;
          this.stats.failed = true;
          if (this.paused) {
            this.paused = false;
            this.options.onPressure?.(false);
          }
          this.settle(false);
          return;
        }
        this.stats.retries++;
        const delays = this.options.retryDelaysMs;
        const delay = delays[Math.min(attempt - 1, delays.length - 1)] ?? 1000;
        this.options.setTimer(() => this.attempt(batch, bytes, attempt + 1), delay);
      },
    );
  }
}
