// Diagnostic request counter (diag.netlog.*), for measurements on the device: how many requests
// went to which host, of which type, and how each ended. Host names, types and Gecko error codes
// only; no path, query, header or body is kept. Listeners exist only between start and stop and
// never block. Like the other diag.* methods it goes away with the release build type (M12).

import type { BridgeClient } from "./lib/bridge";

interface Counts {
  seen: number;
  completed: number;
  errors: Record<string, number>;
}

type Table = Record<string, Record<string, Counts>>;

export function installNetlog(bridge: BridgeClient): void {
  let table: Table | null = null;
  const types = new Map<string, { host: string; type: string }>();
  const patterns = { urls: ["<all_urls>"] };

  const entry = (host: string, type: string): Counts => {
    const byType = (table![host] ??= {});
    return (byType[type] ??= { seen: 0, completed: 0, errors: {} });
  };
  const hostOf = (url: string): string => {
    try {
      return new URL(url).hostname || new URL(url).protocol;
    } catch {
      return "?";
    }
  };
  const onBefore = (d: browser.webRequest._OnBeforeRequestDetails): void => {
    if (table === null) return;
    const key = { host: hostOf(d.url), type: d.type };
    types.set(d.requestId, key);
    entry(key.host, key.type).seen++;
  };
  const onCompleted = (d: browser.webRequest._OnCompletedDetails): void => {
    const key = types.get(d.requestId);
    if (table === null || key === undefined) return;
    types.delete(d.requestId);
    entry(key.host, key.type).completed++;
  };
  const onError = (d: browser.webRequest._OnErrorOccurredDetails): void => {
    const key = types.get(d.requestId);
    if (table === null || key === undefined) return;
    types.delete(d.requestId);
    const e = entry(key.host, key.type);
    e.errors[d.error] = (e.errors[d.error] ?? 0) + 1;
  };

  bridge.handle("diag.netlog.start", () => {
    if (table === null) {
      browser.webRequest.onBeforeRequest.addListener(onBefore, patterns);
      browser.webRequest.onCompleted.addListener(onCompleted, patterns);
      browser.webRequest.onErrorOccurred.addListener(onError, patterns);
    }
    table = {};
    types.clear();
    return { started: true };
  });

  bridge.handle("diag.netlog.stop", () => {
    const result = table ?? {};
    browser.webRequest.onBeforeRequest.removeListener(onBefore);
    browser.webRequest.onCompleted.removeListener(onCompleted);
    browser.webRequest.onErrorOccurred.removeListener(onError);
    table = null;
    types.clear();
    return { hosts: result };
  });
}
