// The content blocker's start-up probe as twin-bridge sees it: cancelled -> blocked, any other
// end -> passed, nothing -> timeout; outcomes that arrive before the app asks are kept.

import { describe, expect, it } from "vitest";
import { FakeBridge, installFakeBrowser } from "./fakeBrowser";

const fake = installFakeBrowser();
const { StartupProbe, classify, probeIdOf } = await import("../src/startupProbe");

const bridge = new FakeBridge();
new StartupProbe(bridge as never).install();

const url = (id: string) => `http://127.0.0.1:65535/__utm.gif?twinbook_startup_probe=${id}`;

describe("start-up probe", () => {
  it("ids only on loopback probe URLs", () => {
    expect(probeIdOf(url("7"))).toBe("7");
    expect(probeIdOf("http://127.0.0.1:1/x")).toBeNull();
    expect(probeIdOf("https://example.com/?twinbook_startup_probe=1")).toBeNull();
    expect(probeIdOf("not a url")).toBeNull();
  });

  it("classifies Gecko's error for a cancelled request", () => {
    expect(classify("NS_ERROR_ABORT")).toBe("blocked");
    expect(classify("NS_ERROR_CONNECTION_REFUSED")).toBe("passed");
    expect(classify(undefined)).toBe("passed");
  });

  it("an outcome reported before the app asks is kept; one reported later wakes the waiter", async () => {
    fake.webRequest.onErrorOccurred.fire({ url: url("a"), error: "NS_ERROR_CONNECTION_REFUSED", requestId: "1" });
    expect(await bridge.call("probe.result", { id: "a", waitMs: 10 })).toMatchObject({ id: "a", outcome: "passed", error: "NS_ERROR_CONNECTION_REFUSED" });
    const pending = bridge.call("probe.result", { id: "b", waitMs: 5_000 });
    fake.webRequest.onErrorOccurred.fire({ url: url("b"), error: "NS_ERROR_ABORT", requestId: "2" });
    expect(await pending).toMatchObject({ id: "b", outcome: "blocked" });
    fake.webRequest.onCompleted.fire({ url: url("c"), statusCode: 200, requestId: "3" });
    expect(await bridge.call("probe.result", { id: "c", waitMs: 10 })).toMatchObject({ outcome: "passed", status: 200 });
  });

  it("times out when the request never ends", async () => {
    expect(await bridge.call("probe.result", { id: "never", waitMs: 20 })).toMatchObject({ id: "never", outcome: "timeout" });
  });
});
