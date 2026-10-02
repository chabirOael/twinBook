// twin-bridge background script: bridge to the app, stream filters, capture recorder, M1
// request reporter, replay executor. Nothing here touches a page's JavaScript realm.

import { installCapture } from "./capture";
import { BUILD_MARKER, NATIVE_APP } from "./config";
import { Filters } from "./filters";
import { BridgeClient, BridgeRequestError, type PortLike } from "./lib/bridge";
import { FilterModes, ModeError, MOCK_PROFILE, PROFILES, profileByName, type FilterMode } from "./lib/profiles";
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

const modes = new FilterModes();
const filters = new Filters(bridge, modes);
filters.install();
installCapture(bridge, { siteListening: (on) => filters.setSiteListening(on), extensionStartedAt: startedAt });
installRecorder(bridge);
installReplay(bridge);

bridge.handle("extension.info", () => ({ ...extensionInfo(), permissions: manifest.permissions ?? [] }));

// M1 switch, kept for the measurements: the mock profile in enforce (on) or off.
bridge.handle("filter.setEnabled", (params) => {
  const enabled = params["enabled"] !== false;
  modes.set(MOCK_PROFILE, enabled ? "enforce" : "off");
  return { enabled };
});

bridge.handle("filter.setMode", (params) => {
  const profile = profileByName(String(params["profile"] ?? ""));
  if (profile === undefined) throw new BridgeRequestError("bad_request", `unknown profile ${String(params["profile"])}`);
  try {
    modes.set(profile, String(params["mode"]) as FilterMode);
  } catch (e) {
    if (e instanceof ModeError) throw new BridgeRequestError(e.code, e.message);
    throw e;
  }
  return { profile: profile.name, mode: modes.get(profile) };
});

bridge.handle("filter.describe", () => ({
  modes: modes.snapshot(),
  siteListening: filters.siteListening,
  profiles: PROFILES.map((p) => ({
    name: p.name,
    urlPatterns: p.urlPatterns,
    allowedModes: p.allowedModes,
    defaultMode: p.defaultMode,
    replay: p.replay,
    rewriteHeaders: p.rewriteHeaders,
    rule: p.rule,
  })),
}));

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
