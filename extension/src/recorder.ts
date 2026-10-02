// M1 request reporter, mock profile only: sends the app the form fields of every POST to the
// mock GraphQL endpoint as a `recorder.request` event (the instrumented tests use it). It never
// runs for the real site, whose requests are recorded only by the capture recorder
// (capture.ts), with redaction.

import type { BridgeClient } from "./lib/bridge";
import { isGraphqlUrl } from "./config";
import { isOwnRequest } from "./filters";
import { readFormFields } from "./lib/formFields";
import { MOCK_PROFILE } from "./lib/profiles";

export function installRecorder(bridge: BridgeClient): void {
  browser.webRequest.onBeforeRequest.addListener(
    (details) => {
      if (details.method !== "POST" || !isGraphqlUrl(details.url) || isOwnRequest(details)) return;
      const form = readFormFields(details.requestBody as Parameters<typeof readFormFields>[0]);
      bridge.emit("recorder.request", {
        requestId: details.requestId,
        url: details.url,
        method: details.method,
        type: details.type,
        documentUrl: details.documentUrl ?? null,
        timeStamp: details.timeStamp,
        source: form.source,
        fieldNames: form.fieldNames,
        fields: form.fields,
      });
    },
    { urls: [...MOCK_PROFILE.urlPatterns] },
    ["requestBody"],
  );
}
