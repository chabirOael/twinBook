// twin-bridge background script: bridge to the app, stream filters, request recorder,
// replay executor. Nothing here touches a page's JavaScript realm.

import { BUILD_MARKER, NATIVE_APP } from "./config";
import { installFilters, type FilterSwitch } from "./filters";
import { BridgeClient, type PortLike } from "./lib/bridge";
import { installRecorder } from "./recorder";
import { installReplay } from "./replay";

const startedAt = Date.now();
const manifest = browser.runtime.getManifest();

function extensionInfo(): Record<string, unknown> {
  return { id: browser.runtime.id, version: manifest.version, marker: BUILD_MARKER, startedAt };
}

const bridge = new BridgeClient({
  connect: () => browser.runtime.connectNative(NATIVE_APP) as unknown as PortLike,
  hello: extensionInfo,
  log: (m) => console.warn(m),
});

// Sent before the port even exists: proves that early messages are queued, not lost.
bridge.emit("bridge.startup", extensionInfo());
const early: { result: unknown; error: string | null; done: boolean } = { result: null, error: null, done: false };
bridge.request("engine.info").then(
  (result) => Object.assign(early, { result, done: true }),
  (e: unknown) => Object.assign(early, { error: String(e), done: true }),
);

const filterSwitch: FilterSwitch = { enabled: true };
installFilters(bridge, filterSwitch);
installRecorder(bridge);
installReplay(bridge);

bridge.handle("extension.info", () => ({ ...extensionInfo(), permissions: manifest.permissions ?? [] }));

bridge.handle("filter.setEnabled", (params) => {
  filterSwitch.enabled = params["enabled"] !== false;
  return { enabled: filterSwitch.enabled };
});

bridge.handle("storage.get", async (params) => {
  const keys = Array.isArray(params["keys"]) ? (params["keys"] as string[]) : null;
  return { items: await browser.storage.local.get(keys) };
});

bridge.handle("storage.set", async (params) => {
  const items = (params["items"] ?? {}) as Record<string, unknown>;
  await browser.storage.local.set(items);
  return { stored: Object.keys(items) };
});

// Diagnostics used by the instrumented bridge tests. They only echo and count; harmless in
// production builds.
const notes: Record<string, unknown>[] = [];
bridge.on("diag.note", (data) => {
  notes.push({ ...data, receivedAt: Date.now() });
});
bridge.handle("diag.echo", (params) => params);
bridge.handle("diag.notes", () => ({ notes }));
bridge.handle("diag.early", () => early);
bridge.handle("diag.emit", (params) => {
  const count = Number(params["count"] ?? 1);
  for (let i = 0; i < count; i++) bridge.emit(String(params["name"]), { ...(params["data"] as object), seq: i });
  return { emitted: count };
});
bridge.handle("diag.callApp", async (params) => ({
  result: await bridge.request(String(params["method"]), (params["params"] ?? {}) as Record<string, unknown>),
}));

bridge.start();
console.info(`twin-bridge ${manifest.version} (${BUILD_MARKER}): background started`);
