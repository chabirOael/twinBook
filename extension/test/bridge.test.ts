import { describe, expect, it } from "vitest";
import { BridgeClient, BridgeRequestError, type PortLike } from "../src/lib/bridge";

/** In-memory port; `app` collects what the extension posts, `deliver` plays the app. */
class FakePort implements PortLike {
  readonly sent: Record<string, unknown>[] = [];
  private messageListeners: ((m: unknown) => void)[] = [];
  private disconnectListeners: ((p: unknown) => void)[] = [];
  disconnected = false;
  postMessage(message: unknown): void {
    if (this.disconnected) throw new Error("port closed");
    this.sent.push(JSON.parse(JSON.stringify(message)));
  }
  disconnect(): void {
    this.disconnected = true;
  }
  onMessage = { addListener: (l: (m: unknown) => void) => void this.messageListeners.push(l) };
  onDisconnect = { addListener: (l: (p: unknown) => void) => void this.disconnectListeners.push(l) };
  deliver(m: unknown): void {
    for (const l of this.messageListeners) l(m);
  }
  drop(): void {
    this.disconnected = true;
    for (const l of this.disconnectListeners) l(this);
  }
}

class Timers {
  private now = 0;
  private items: { at: number; fn: () => void; id: number }[] = [];
  private seq = 0;
  set = (fn: () => void, ms: number) => {
    const id = ++this.seq;
    this.items.push({ at: this.now + ms, fn, id });
    return id;
  };
  clear = (h: unknown) => {
    this.items = this.items.filter((i) => i.id !== h);
  };
  advance(ms: number): void {
    this.now += ms;
    for (;;) {
      const due = this.items.filter((i) => i.at <= this.now).sort((a, b) => a.at - b.at)[0];
      if (due === undefined) break;
      this.items = this.items.filter((i) => i !== due);
      due.fn();
    }
  }
}

function setup(timeoutMs = 1000) {
  const ports: FakePort[] = [];
  const timers = new Timers();
  const client = new BridgeClient({
    connect: () => {
      const p = new FakePort();
      ports.push(p);
      return p;
    },
    hello: () => ({ version: "1.2.3" }),
    requestTimeoutMs: timeoutMs,
    reconnectDelaysMs: [10, 20],
    setTimer: timers.set,
    clearTimer: timers.clear,
  });
  return { client, ports, timers };
}

const flush = () => new Promise((r) => setTimeout(r, 0));

describe("BridgeClient", () => {
  it("sends hello first and queues everything until welcome", () => {
    const { client, ports } = setup();
    client.emit("early", { a: 1 });
    void client.request("engine.info").catch(() => {});
    client.start();
    const port = ports[0]!;
    expect(port.sent).toEqual([{ type: "hello", protocol: 1, extension: { version: "1.2.3" } }]);
    expect(client.state).toBe("connecting");
    port.deliver({ type: "welcome", protocol: 1, engine: { geckoview: "157.0" } });
    expect(client.state).toBe("ready");
    expect(client.engine).toEqual({ geckoview: "157.0" });
    expect(port.sent.slice(1)).toEqual([
      { type: "event", name: "early", data: { a: 1 } },
      { type: "request", id: "e1", method: "engine.info", params: {} },
    ]);
  });

  it("re-sends hello until welcome, then stops", () => {
    const { client, ports, timers } = setup();
    client.start();
    timers.advance(500);
    timers.advance(500);
    expect(ports[0]!.sent.filter((m) => m["type"] === "hello")).toHaveLength(3);
    ports[0]!.deliver({ type: "welcome" });
    timers.advance(5000);
    expect(ports[0]!.sent.filter((m) => m["type"] === "hello")).toHaveLength(3);
  });

  it("replaces a port that never answers hello", () => {
    const { client, ports, timers } = setup();
    client.start();
    for (let i = 0; i < 21; i++) timers.advance(500);
    expect(ports[0]!.disconnected).toBe(true);
    timers.advance(20);
    expect(ports).toHaveLength(2);
    expect(client.state).toBe("connecting");
  });

  it("correlates responses to requests, also out of order", async () => {
    const { client, ports } = setup();
    client.start();
    const port = ports[0]!;
    port.deliver({ type: "welcome" });
    const a = client.request("a");
    const b = client.request("b");
    port.deliver({ type: "response", id: "e2", ok: true, result: { v: "b" } });
    port.deliver({ type: "response", id: "e1", ok: false, error: { code: "nope", message: "failed a" } });
    await expect(b).resolves.toEqual({ v: "b" });
    await expect(a).rejects.toMatchObject({ code: "nope", message: "failed a" });
  });

  it("answers requests from the app with handler results and errors", async () => {
    const { client, ports } = setup();
    client.handle("sum", (p) => ({ sum: (p["a"] as number) + (p["b"] as number) }));
    client.handle("later", async () => {
      await flush();
      return { done: true };
    });
    client.handle("fail", () => {
      throw new BridgeRequestError("bad", "no good");
    });
    client.start();
    const port = ports[0]!;
    port.deliver({ type: "welcome" });
    port.deliver({ type: "request", id: "k1", method: "sum", params: { a: 2, b: 3 } });
    port.deliver({ type: "request", id: "k2", method: "later", params: {} });
    port.deliver({ type: "request", id: "k3", method: "fail", params: {} });
    port.deliver({ type: "request", id: "k4", method: "missing", params: {} });
    await flush();
    await flush();
    const responses = port.sent.filter((m) => m["type"] === "response");
    expect(responses).toContainEqual({ type: "response", id: "k1", ok: true, result: { sum: 5 } });
    expect(responses).toContainEqual({ type: "response", id: "k2", ok: true, result: { done: true } });
    expect(responses).toContainEqual({ type: "response", id: "k3", ok: false, error: { code: "bad", message: "no good" } });
    expect(responses).toContainEqual({ type: "response", id: "k4", ok: false, error: { code: "no_handler", message: "no handler for missing" } });
  });

  it("delivers app events to listeners", () => {
    const { client, ports } = setup();
    const got: unknown[] = [];
    client.on("note", (d) => got.push(d));
    client.start();
    ports[0]!.deliver({ type: "welcome" });
    ports[0]!.deliver({ type: "event", name: "note", data: { x: 1 } });
    ports[0]!.deliver({ type: "event", name: "other", data: {} });
    expect(got).toEqual([{ x: 1 }]);
  });

  it("times out a request that gets no response, also while queued", async () => {
    const { client, timers, ports } = setup(100);
    const queued = client.request("never");
    client.start();
    timers.advance(150);
    await expect(queued).rejects.toMatchObject({ code: "timeout" });
    ports[0]!.deliver({ type: "welcome" });
    expect(ports[0]!.sent.filter((m) => m["type"] === "request")).toEqual([]);
  });

  it("reconnects after a disconnect, fails in-flight requests and keeps queued messages", async () => {
    const { client, ports, timers } = setup(10_000);
    client.start();
    ports[0]!.deliver({ type: "welcome" });
    const inflight = client.request("slow");
    ports[0]!.drop();
    await expect(inflight).rejects.toMatchObject({ code: "disconnected" });
    expect(client.state).toBe("disconnected");
    client.emit("while-down", {});
    timers.advance(10);
    expect(ports).toHaveLength(2);
    expect(ports[1]!.sent[0]).toEqual({ type: "hello", protocol: 1, extension: { version: "1.2.3" } });
    ports[1]!.deliver({ type: "welcome" });
    expect(ports[1]!.sent.find((m) => m["type"] === "event")).toEqual({ type: "event", name: "while-down", data: {} });
  });

  it("retries when connecting throws", () => {
    const timers = new Timers();
    let calls = 0;
    const ports: FakePort[] = [];
    const client = new BridgeClient({
      connect: () => {
        calls++;
        if (calls < 3) throw new Error("not yet");
        const p = new FakePort();
        ports.push(p);
        return p;
      },
      hello: () => ({}),
      reconnectDelaysMs: [5],
      setTimer: timers.set,
      clearTimer: timers.clear,
    });
    client.start();
    timers.advance(5);
    timers.advance(5);
    expect(calls).toBe(3);
    expect(ports).toHaveLength(1);
  });

  it("carries a 1 MB payload unchanged", async () => {
    const { client, ports } = setup();
    client.handle("echo", (p) => p);
    client.start();
    ports[0]!.deliver({ type: "welcome" });
    const big = "é".repeat(512 * 1024);
    ports[0]!.deliver({ type: "request", id: "k1", method: "echo", params: { big } });
    await flush();
    const res = ports[0]!.sent.find((m) => m["type"] === "response") as { result: { big: string } };
    expect(res.result.big).toBe(big);
  });
});
