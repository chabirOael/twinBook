// Anchor content script: runs only on the anchor page, a same-origin page with no site
// JavaScript (see manifest.json content_scripts). It executes replay requests in the page's
// context with content.fetch(), so they carry the page's origin and cookies.
//
// It refuses to run on a page that has any script element: twin-bridge never touches a page
// that runs a site's own JavaScript application.

import { requestInit, toResult, type ReplayRequest } from "./lib/replayResult";

// Firefox content scripts expose the page's own fetch as content.fetch (MV2).
declare const content: { fetch: typeof fetch };

if (document.scripts.length === 0) {
  const port = browser.runtime.connect({ name: "anchor" });
  port.onMessage.addListener((raw) => {
    const m = raw as { id: number; via: string; request: ReplayRequest };
    void (async () => {
      try {
        const init = requestInit(m.request);
        // Called on its owner object: a detached content.fetch throws.
        const response = m.via === "anchor" ? await content.fetch(m.request.url, init) : await fetch(m.request.url, init);
        port.postMessage({ id: m.id, ok: true, result: await toResult(response, m.via) });
      } catch (e) {
        port.postMessage({ id: m.id, ok: false, error: e instanceof Error ? e.message : String(e) });
      }
    })();
  });
}
