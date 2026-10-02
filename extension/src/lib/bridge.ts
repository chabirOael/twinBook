// Extension side of the twin-bridge protocol: one long-lived native messaging port to the
// app, carrying JSON messages. See docs/ENGINE.md for the protocol.
//
//   ext -> app  {type:"hello", protocol, extension:{...}}        first message on every port
//   app -> ext  {type:"welcome", protocol, engine:{...}}         reply to hello
//   both        {type:"event", name, data}
//   both        {type:"request", id, method, params}
//   both        {type:"response", id, ok:true, result}
//               {type:"response", id, ok:false, error:{code, message}}
//
// Outgoing events and requests are queued until the app has answered hello with welcome, so
// nothing is lost when the extension starts before the app listens. If the port disconnects,
// requests already sent on it fail with code "disconnected", unsent messages stay queued and
// the client reconnects with backoff. hello is re-sent on the same port until welcome arrives,
// because a message posted before the app has attached its port listener can be lost.

export const PROTOCOL_VERSION = 1;

export interface PortLike {
  postMessage(message: unknown): void;
  disconnect(): void;
  onMessage: { addListener(listener: (message: unknown) => void): void };
  onDisconnect: { addListener(listener: (port: unknown) => void): void };
}

export type BridgeState = "idle" | "connecting" | "ready" | "disconnected";

export interface BridgeError {
  code: string;
  message: string;
}

export class BridgeRequestError extends Error {
  readonly code: string;
  constructor(code: string, message: string) {
    super(message);
    this.code = code;
    this.name = "BridgeRequestError";
  }
}

export type RequestHandler = (params: Record<string, unknown>) => unknown;
export type EventListener = (data: Record<string, unknown>) => void;

export interface BridgeClientOptions {
  /** Opens a new port to the app, e.g. () => browser.runtime.connectNative("twinbook"). */
  connect: () => PortLike;
  /** Fields of the `extension` object sent in hello. */
  hello: () => Record<string, unknown>;
  requestTimeoutMs?: number;
  reconnectDelaysMs?: readonly number[];
  /** Interval for re-sending hello until welcome; after helloAttempts the port is replaced. */
  helloRetryMs?: number;
  helloAttempts?: number;
  setTimer?: (fn: () => void, ms: number) => unknown;
  clearTimer?: (handle: unknown) => void;
  log?: (message: string) => void;
}

interface Pending {
  resolve: (value: unknown) => void;
  reject: (error: Error) => void;
  timer: unknown;
  /** Port the request went out on; undefined while queued. */
  port: PortLike | undefined;
}

type Outgoing = { type: "event"; name: string; data: unknown } | { type: "request"; id: string; method: string; params: unknown };

export class BridgeClient {
  private readonly options: Required<Omit<BridgeClientOptions, "log">> & Pick<BridgeClientOptions, "log">;
  private port: PortLike | undefined;
  private stateValue: BridgeState = "idle";
  private readonly queue: Outgoing[] = [];
  private readonly pending = new Map<string, Pending>();
  private readonly handlers = new Map<string, RequestHandler>();
  private readonly listeners = new Map<string, EventListener[]>();
  private readonly stateListeners: ((state: BridgeState) => void)[] = [];
  private nextId = 1;
  private attempt = 0;
  private welcome: Record<string, unknown> | undefined;

  constructor(options: BridgeClientOptions) {
    this.options = {
      requestTimeoutMs: 30_000,
      reconnectDelaysMs: [100, 250, 500, 1000, 2000, 5000],
      helloRetryMs: 500,
      helloAttempts: 20,
      setTimer: (fn, ms) => setTimeout(fn, ms),
      clearTimer: (h) => clearTimeout(h as ReturnType<typeof setTimeout>),
      ...options,
    };
  }

  get state(): BridgeState {
    return this.stateValue;
  }

  /** What the app sent in welcome, once ready. */
  get engine(): Record<string, unknown> | undefined {
    return this.welcome;
  }

  start(): void {
    if (this.stateValue === "idle") this.open();
  }

  /** Sends an event. Queued until the bridge is ready. */
  emit(name: string, data: Record<string, unknown> = {}): void {
    this.enqueue({ type: "event", name, data });
  }

  /** Sends a request and resolves with the app's result. Queued until the bridge is ready. */
  request(method: string, params: Record<string, unknown> = {}, timeoutMs = this.options.requestTimeoutMs): Promise<unknown> {
    const id = `e${this.nextId++}`;
    return new Promise((resolve, reject) => {
      const timer = this.options.setTimer(() => {
        if (this.pending.delete(id)) {
          const queued = this.queue.findIndex((m) => m.type === "request" && m.id === id);
          if (queued >= 0) this.queue.splice(queued, 1);
          reject(new BridgeRequestError("timeout", `request ${method} timed out after ${timeoutMs} ms`));
        }
      }, timeoutMs);
      this.pending.set(id, { resolve, reject, timer, port: undefined });
      this.enqueue({ type: "request", id, method, params });
    });
  }

  /** Registers the handler for requests from the app. Its return value (or promise) is the result. */
  handle(method: string, handler: RequestHandler): void {
    this.handlers.set(method, handler);
  }

  /** Listens to events from the app. */
  on(name: string, listener: EventListener): void {
    const list = this.listeners.get(name) ?? [];
    list.push(listener);
    this.listeners.set(name, list);
  }

  onStateChange(listener: (state: BridgeState) => void): void {
    this.stateListeners.push(listener);
  }

  private setState(state: BridgeState): void {
    if (state === this.stateValue) return;
    this.stateValue = state;
    for (const l of this.stateListeners) {
      try {
        l(state);
      } catch {
        // ignore
      }
    }
  }

  private open(): void {
    this.setState("connecting");
    let port: PortLike;
    try {
      port = this.options.connect();
    } catch (e) {
      this.log(`connect failed: ${String(e)}`);
      this.scheduleReconnect();
      return;
    }
    this.port = port;
    port.onMessage.addListener((m) => {
      if (this.port === port) this.receive(port, m);
    });
    port.onDisconnect.addListener(() => {
      if (this.port === port) this.lost(port);
    });
    this.sendHello(port, 1);
  }

  private sendHello(port: PortLike, attempt: number): void {
    if (this.port !== port || this.stateValue === "ready") return;
    if (attempt > this.options.helloAttempts) {
      this.log("no welcome; replacing the port");
      try {
        port.disconnect();
      } catch {
        // already gone
      }
      this.lost(port);
      return;
    }
    try {
      port.postMessage({ type: "hello", protocol: PROTOCOL_VERSION, extension: this.options.hello() });
    } catch (e) {
      this.log(`hello failed: ${String(e)}`);
      this.lost(port);
      return;
    }
    this.options.setTimer(() => this.sendHello(port, attempt + 1), this.options.helloRetryMs);
  }

  private lost(port: PortLike): void {
    this.port = undefined;
    this.welcome = undefined;
    this.setState("disconnected");
    for (const [id, p] of this.pending) {
      if (p.port === port) {
        this.pending.delete(id);
        this.options.clearTimer(p.timer);
        p.reject(new BridgeRequestError("disconnected", "bridge disconnected before the response arrived"));
      }
    }
    this.scheduleReconnect();
  }

  private scheduleReconnect(): void {
    const delays = this.options.reconnectDelaysMs;
    const delay = delays[Math.min(this.attempt, delays.length - 1)] ?? 1000;
    this.attempt++;
    this.options.setTimer(() => {
      if (this.port === undefined) this.open();
    }, delay);
  }

  private enqueue(message: Outgoing): void {
    if (this.stateValue === "ready" && this.port !== undefined) {
      this.post(this.port, message);
    } else {
      this.queue.push(message);
    }
  }

  private post(port: PortLike, message: Outgoing): void {
    if (message.type === "request") {
      const p = this.pending.get(message.id);
      if (p === undefined) return; // timed out while queued
      p.port = port;
    }
    port.postMessage(message);
  }

  private flush(port: PortLike): void {
    while (this.queue.length > 0 && this.port === port) {
      this.post(port, this.queue.shift()!);
    }
  }

  private receive(port: PortLike, raw: unknown): void {
    if (typeof raw !== "object" || raw === null) return;
    const m = raw as Record<string, unknown>;
    switch (m["type"]) {
      case "welcome":
        this.welcome = asRecord(m["engine"]);
        this.attempt = 0;
        this.setState("ready");
        this.flush(port);
        break;
      case "event": {
        const name = String(m["name"]);
        for (const l of this.listeners.get(name) ?? []) {
          try {
            l(asRecord(m["data"]));
          } catch (e) {
            this.log(`event listener ${name} failed: ${String(e)}`);
          }
        }
        break;
      }
      case "request":
        void this.answer(port, String(m["id"]), String(m["method"]), asRecord(m["params"]));
        break;
      case "response": {
        const id = String(m["id"]);
        const p = this.pending.get(id);
        if (p === undefined) return;
        this.pending.delete(id);
        this.options.clearTimer(p.timer);
        if (m["ok"] === true) {
          p.resolve(m["result"]);
        } else {
          const err = asRecord(m["error"]);
          p.reject(new BridgeRequestError(String(err["code"] ?? "error"), String(err["message"] ?? "request failed")));
        }
        break;
      }
      default:
        this.log(`unknown message type ${String(m["type"])}`);
    }
  }

  private async answer(port: PortLike, id: string, method: string, params: Record<string, unknown>): Promise<void> {
    const handler = this.handlers.get(method);
    let response: Record<string, unknown>;
    if (handler === undefined) {
      response = { type: "response", id, ok: false, error: { code: "no_handler", message: `no handler for ${method}` } };
    } else {
      try {
        const result = await handler(params);
        response = { type: "response", id, ok: true, result: result ?? {} };
      } catch (e) {
        const code = e instanceof BridgeRequestError ? e.code : "handler_error";
        response = { type: "response", id, ok: false, error: { code, message: e instanceof Error ? e.message : String(e) } };
      }
    }
    // Answer on the port the request came in on, if it is still the current one.
    if (this.port === port) {
      try {
        port.postMessage(response);
      } catch (e) {
        this.log(`response to ${method} failed: ${String(e)}`);
      }
    }
  }

  private log(message: string): void {
    this.options.log?.(`twin-bridge: ${message}`);
  }
}

function asRecord(value: unknown): Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value) ? (value as Record<string, unknown>) : {};
}
