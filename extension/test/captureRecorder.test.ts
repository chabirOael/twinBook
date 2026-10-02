// M2b recorder fixes, driven through the real capture.ts and filters.ts against the fake
// WebExtension API:
// - F3: a form body part without "=" (Gecko's parser yields undefined for its value) made
//   layer 1 throw `can't access property "replace", s is undefined`, and the whole request
//   line was lost. Seen on two `/ajax/route-definition/` posts of the owner's desktop capture.
// - F4: WebSocket handshakes are recorded as metadata (type, host, path) and never tapped.

import { beforeAll, beforeEach, describe, expect, it } from "vitest";
import { FakeBridge, installFakeBrowser, type FakeBrowser } from "./fakeBrowser";

const fake: FakeBrowser = installFakeBrowser();
const { Filters } = await import("../src/filters");
const { installCapture } = await import("../src/capture");
const { FilterModes } = await import("../src/lib/profiles");
const { Redactor, l1Placeholder } = await import("../src/lib/redact");
const { recordRequestBody } = await import("../src/lib/record");

let bridge: FakeBridge;

beforeAll(() => {
  bridge = new FakeBridge();
  const filters = new Filters(bridge as never, new FilterModes());
  filters.install();
  installCapture(bridge as never, { siteListening: (on) => filters.setSiteListening(on), extensionStartedAt: 1 });
});

beforeEach(() => {
  bridge.requests.length = 0;
  bridge.events.length = 0;
});

describe("form fields without a value (F3)", () => {
  it("layer 1 keeps them as [name, null] instead of throwing", () => {
    const r = new Redactor();
    const fields = r.formFields([
      ["route_urls[0]", "/x"],
      ["fb_dtsg", "NAcTokenValue:1:2"],
      ["__comet_req", undefined],
      ["", undefined],
    ]);
    expect(fields).toEqual([
      ["route_urls[0]", "/x"],
      ["fb_dtsg", l1Placeholder("NAcTokenValue:1:2".length)],
      ["__comet_req", null],
      ["", null],
    ]);
  });

  it("recordRequestBody: Gecko formData with an undefined value", () => {
    const body = recordRequestBody({ formData: { a: ["1"], b: [undefined], lsd: ["AVqLsdValue1"] } }, new Redactor());
    expect(body).toEqual({ kind: "formData", fields: [["a", "1"], ["b", null], ["lsd", l1Placeholder(12)]] });
  });

  it("the request line of such a post is recorded, with no recorder error", async () => {
    await bridge.call("capture.start", { sessionId: "f3", profiles: ["site"] });
    const d = {
      requestId: "rd1",
      url: "https://www.facebook.com/ajax/route-definition/",
      method: "POST",
      type: "xmlhttprequest",
      timeStamp: 5,
      tabId: 7,
      documentUrl: "https://www.facebook.com/",
      requestBody: { formData: { "route_urls[0]": ["/notifications/"], fb_dtsg: ["NAcTokenValue:1:2"], __a: ["1"], trailing: [undefined] } },
    };
    fake.webRequest.onBeforeRequest.fire(d);
    const stop = await bridge.call("capture.stop", { sessionId: "f3" });
    expect((stop["counters"] as { errors: number }).errors).toBe(0);
    const line = bridge.lines().find((l) => l["ev"] === "request" && l["rid"] === "rd1");
    expect(line).toBeDefined();
    expect(line!["body"]).toEqual({
      kind: "formData",
      fields: [
        ["route_urls[0]", "/notifications/"],
        ["fb_dtsg", l1Placeholder(17)],
        ["__a", "*"],
        ["trailing", null],
      ],
    });
  });

  it("a throwing body never costs the request line: it is kept with extraError", async () => {
    await bridge.call("capture.start", { sessionId: "f3b", profiles: ["site"] });
    // A formData value list that is not an array makes recordRequestBody throw.
    fake.webRequest.onBeforeRequest.fire({ requestId: "bad1", url: "https://www.facebook.com/ajax/x", method: "POST", type: "xmlhttprequest", timeStamp: 1, requestBody: { formData: { a: 5 } } });
    const stop = await bridge.call("capture.stop", { sessionId: "f3b" });
    expect((stop["counters"] as { errors: number }).errors).toBe(1);
    const line = bridge.lines().find((l) => l["ev"] === "request" && l["rid"] === "bad1")!;
    expect(line["d"]).toMatchObject({ method: "POST", type: "xmlhttprequest" });
    expect(String(line["extraError"])).toContain("TypeError");
  });
});

describe("long-lived connections (F4)", () => {
  it("a WebSocket handshake is recorded as metadata with its type, host and path, and never tapped", async () => {
    await bridge.call("capture.start", { sessionId: "ws", profiles: ["site"] });
    const d = { requestId: "ws1", url: "wss://gateway.facebook.com/ws/realtime?x=1", method: "GET", type: "websocket", timeStamp: 9, tabId: 3, documentUrl: "https://www.facebook.com/" };
    fake.webRequest.onBeforeRequest.fire({ ...d });
    fake.webRequest.onSendHeaders.fire({ ...d, requestHeaders: [{ name: "Upgrade", value: "websocket" }, { name: "Sec-WebSocket-Protocol", value: "mqtt" }] });
    fake.webRequest.onHeadersReceived.fire({ ...d, statusCode: 101, statusLine: "HTTP/1.1 101 Switching Protocols", responseHeaders: [{ name: "Upgrade", value: "websocket" }] });
    fake.webRequest.onCompleted.fire({ ...d, statusCode: 101 });
    const stop = await bridge.call("capture.stop", { sessionId: "ws" });
    expect(stop["ok"]).toBe(true);
    expect(fake.filters.has("ws1")).toBe(false);
    const lines = bridge.lines().filter((l) => l["rid"] === "ws1");
    expect(lines.map((l) => l["ev"])).toEqual(["request", "sendHeaders", "headers", "completed"]);
    expect(lines[0]).toMatchObject({ own: true, d: { type: "websocket", url: "wss://gateway.facebook.com/ws/realtime?x=1" } });
    expect(lines[2]).toMatchObject({ d: { statusCode: 101 } });
  });
});
