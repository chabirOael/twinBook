// Request recorder: sends the app the form fields of every POST to the GraphQL endpoint.

import type { BridgeClient } from "./lib/bridge";
import { TARGET_URL_PATTERNS, isGraphqlUrl } from "./config";
import { isOwnRequest } from "./filters";
import { readFormFields } from "./lib/formFields";

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
    { urls: TARGET_URL_PATTERNS },
    ["requestBody"],
  );
}
