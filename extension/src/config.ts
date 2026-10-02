// Build-time and protocol constants. Which hosts twin-bridge touches is decided by the site
// profiles in src/lib/profiles.ts; host permissions in manifest.json must cover them.

/** Path of the site's GraphQL endpoint: on the mock, responses are NDJSON-filtered and request bodies reported. */
export const GRAPHQL_PATH = "/api/graphql/";

/** Name of the native app the background script connects to. Must match the Kotlin side. */
export const NATIVE_APP = "twinbook";

export function isGraphqlUrl(url: string): boolean {
  try {
    return new URL(url).pathname === GRAPHQL_PATH;
  } catch {
    return false;
  }
}

/** Build-time constants injected by build.mjs. */
declare const __TWIN_BRIDGE_MARKER__: string;
export const BUILD_MARKER: string = typeof __TWIN_BRIDGE_MARKER__ === "string" ? __TWIN_BRIDGE_MARKER__ : "dev";
