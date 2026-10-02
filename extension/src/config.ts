// Which requests twin-bridge touches. M1 targets only the loopback mock server; the real site
// is added in M2. Host permissions in manifest.json must cover every host listed here.

/** URL patterns for webRequest listeners. Match patterns ignore the port. */
export const TARGET_URL_PATTERNS = ["http://127.0.0.1/*", "http://localhost/*"];

/** Path of the site's GraphQL endpoint: responses are NDJSON-filtered, request bodies recorded. */
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
