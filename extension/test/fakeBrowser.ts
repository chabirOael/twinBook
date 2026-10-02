// A minimal fake of the WebExtension APIs the background script uses, so the wiring in
// filters.ts and capture.ts can be tested in Node: listeners are recorded with their filters
// and options, stream filters record every byte written to the page.

type Fn = (details: never) => unknown;

export interface RegisteredListener {
  fn: Fn;
  urls: string[];
  types?: string[] | undefined;
  extra: string[];
}

export class FakeEvent {
  readonly listeners: RegisteredListener[] = [];

  addListener(fn: Fn, filter: { urls: string[]; types?: string[] }, extra: string[] = []): void {
    this.listeners.push({ fn, urls: [...filter.urls], types: filter.types, extra });
  }

  removeListener(fn: Fn): void {
    const i = this.listeners.findIndex((l) => l.fn === fn);
    if (i >= 0) this.listeners.splice(i, 1);
  }

  /** Calls every listener whose URL patterns match and returns their results. */
  fire(details: Record<string, unknown>): unknown[] {
    return this.listeners.filter((l) => l.urls.some((p) => matches(p, String(details["url"])))).map((l) => l.fn(details as never));
  }
}

/** WebExtension match pattern test, enough for the patterns twin-bridge uses. */
export function matches(pattern: string, url: string): boolean {
  const m = /^(\*|https?|wss?):\/\/(\*|\*\.[^/]+|[^/*]+)\/(.*)$/.exec(pattern);
  if (m === null) return false;
  let u: URL;
  try {
    u = new URL(url);
  } catch {
    return false;
  }
  const scheme = u.protocol.slice(0, -1);
  if (m[1] === "*" ? !["http", "https", "ws", "wss"].includes(scheme) : m[1] !== scheme) return false;
  const host = m[2]!;
  if (host !== "*") {
    if (host.startsWith("*.")) {
      const base = host.slice(2);
      if (u.hostname !== base && !u.hostname.endsWith(`.${base}`)) return false;
    } else if (u.hostname !== host) return false;
  }
  return true;
}

export class FakeStreamFilter {
  ondata: ((event: { data: ArrayBuffer }) => void) | null = null;
  onstop: (() => void) | null = null;
  onerror: (() => void) | null = null;
  readonly written: Uint8Array[] = [];
  closed = false;
  suspended = 0;
  error = "";

  write(data: ArrayBuffer | Uint8Array): void {
    this.written.push(data instanceof Uint8Array ? data.slice() : new Uint8Array(data.slice(0)));
  }

  close(): void {
    this.closed = true;
  }

  disconnect(): void {
    this.closed = true;
  }

  suspend(): void {
    this.suspended++;
  }

  resume(): void {}

  /** Delivers `bytes` cut at `cuts`, then stops. */
  deliver(bytes: Uint8Array, cuts: number[] = []): void {
    let prev = 0;
    for (const cut of [...cuts, bytes.length]) {
      const piece = bytes.slice(prev, cut);
      this.ondata?.({ data: piece.buffer });
      prev = cut;
    }
    this.onstop?.();
  }

  output(): Uint8Array {
    const total = this.written.reduce((n, w) => n + w.length, 0);
    const out = new Uint8Array(total);
    let o = 0;
    for (const w of this.written) {
      out.set(w, o);
      o += w.length;
    }
    return out;
  }
}

type EventName = "onBeforeRequest" | "onBeforeSendHeaders" | "onSendHeaders" | "onHeadersReceived" | "onBeforeRedirect" | "onResponseStarted" | "onCompleted" | "onErrorOccurred";

export interface FakeBrowser {
  webRequest: Record<EventName, FakeEvent> & {
    filterResponseData(requestId: string): FakeStreamFilter;
    getSecurityInfo(requestId: string, options: object): Promise<object>;
  };
  runtime: { getURL(path: string): string; id: string; getManifest(): object; onConnect: { addListener(): void } };
  filters: Map<string, FakeStreamFilter>;
}

export function installFakeBrowser(): FakeBrowser {
  const filters = new Map<string, FakeStreamFilter>();
  const events: EventName[] = ["onBeforeRequest", "onBeforeSendHeaders", "onSendHeaders", "onHeadersReceived", "onBeforeRedirect", "onResponseStarted", "onCompleted", "onErrorOccurred"];
  const webRequest = Object.fromEntries(events.map((e) => [e, new FakeEvent()])) as unknown as FakeBrowser["webRequest"];
  webRequest.filterResponseData = (requestId: string) => {
    const f = new FakeStreamFilter();
    filters.set(requestId, f);
    return f;
  };
  webRequest.getSecurityInfo = () => Promise.resolve({ state: "secure", protocolVersion: "TLSv1.3", certificates: [{ subject: "x" }] });
  const fake: FakeBrowser = {
    webRequest,
    runtime: { getURL: (p: string) => `moz-extension://fake-uuid/${p}`, id: "twin-bridge@twinbook", getManifest: () => ({ version: "0" }), onConnect: { addListener: () => undefined } },
    filters,
  };
  (globalThis as unknown as { browser: unknown }).browser = fake;
  return fake;
}

/** Records what the extension sends to the app. */
export class FakeBridge {
  readonly events: { name: string; data: Record<string, unknown> }[] = [];
  readonly requests: { method: string; params: Record<string, unknown> }[] = [];
  readonly handlers = new Map<string, (params: Record<string, unknown>) => unknown>();

  emit(name: string, data: Record<string, unknown> = {}): void {
    this.events.push({ name, data });
  }

  request(method: string, params: Record<string, unknown> = {}): Promise<unknown> {
    this.requests.push({ method, params: JSON.parse(JSON.stringify(params)) as Record<string, unknown> });
    return Promise.resolve({});
  }

  handle(method: string, handler: (params: Record<string, unknown>) => unknown): void {
    this.handlers.set(method, handler);
  }

  on(): void {}

  async call(method: string, params: Record<string, unknown> = {}): Promise<Record<string, unknown>> {
    const h = this.handlers.get(method);
    if (h === undefined) throw new Error(`no handler ${method}`);
    return (await h(params)) as Record<string, unknown>;
  }

  /** Every capture line sent so far, parsed. */
  lines(): Record<string, unknown>[] {
    return this.requests
      .filter((r) => r.method === "capture.write")
      .flatMap((r) => (r.params["items"] as { l?: string }[]).filter((i) => i.l !== undefined).map((i) => JSON.parse(i.l!) as Record<string, unknown>));
  }

  /** Body files reassembled from the capture.write pieces. */
  bodies(): Map<string, Uint8Array> {
    const parts = new Map<string, number[]>();
    for (const r of this.requests.filter((x) => x.method === "capture.write")) {
      for (const i of r.params["items"] as { f?: string; b?: string }[]) {
        if (i.f === undefined) continue;
        const list = parts.get(i.f) ?? [];
        for (const c of atob(i.b!)) list.push(c.charCodeAt(0));
        parts.set(i.f, list);
      }
    }
    return new Map([...parts].map(([k, v]) => [k, Uint8Array.from(v)]));
  }
}
