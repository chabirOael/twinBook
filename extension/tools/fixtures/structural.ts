// What a fixture may keep verbatim. Shared by the sanitizer (sanitize.ts) and the leak test
// (test/fixtures.test.ts), so both draw the line in the same place.
//
// Allowlist approach: a string is structural only if one of the rules below says so; every
// other string is replaced. When in doubt a string is not structural.

/** Keys whose string values are free text or names; never structural, whatever they look like. */
export const TEXT_KEYS = new Set([
  "name", "short_name", "alternate_name", "text", "title", "subtitle", "description", "message", "body", "username", "vanity", "url", "uri", "id",
  "caption", "accessibility_caption", "label_text", "headline", "snippet", "query", "user_input", "selected_text", "display_name", "first_name", "last_name",
  "NAME", "SHORT_NAME", "USER_ID", "ACCOUNT_ID",
]);

/** Keys whose string values are constants of the client or the schema. */
const CONSTANT_KEYS = new Set([
  "mime_type", "codecs", "quality", "lynx_mode", "fb_api_caller_class", "fb_api_req_friendly_name", "__crn", "__ccg", "__spin_b", "__comet_req",
  "server_timestamps", "__a", "__aaid", "dpr", "feedLocation", "feedStyle", "refreshMode", "renderLocation", "privacySelectorRenderLocation",
  "referringStoryRenderLocation", "context", "actionBarRenderLocation", "surface", "surface_type", "request_type", "player_behavior", "orderby",
  "video_channel_entry_point", "referral_source", "arltw_feed_section_type", "evt", "bucket_type", "row_type", "seen_state", "notif_type",
  "comet_video_player_nextgendash_availability", "audio_channel_configuration", "restriction_violation_status", "avatar_cover_photo_takeover_type",
  "web_reshare_variant", "connectionClass", "routing_namespace",
]);

/** Keys whose single lowercase word values are schema vocabulary (entity and style kinds). */
const LOWER_CONSTANT_KEYS = new Set(["type", "entity_type", "style_list", "glyph_name", "fbls_tier", "__rc", "source", "media_type", "attachment_type"]);

/**
 * Renderer, strategy, plugin and module names (`CometFeedStoryAudienceStrategy.react`,
 * `…_audience$normalization.graphql`, `cometUFIComposerEmojiPlugin`): structural wherever they
 * appear, so that a name kept under __dr is never "non-structural" under another key.
 */
const MODULE_NAME = /^[A-Za-z][A-Za-z0-9_]*[A-Z][A-Za-z0-9_]*(?:\$[A-Za-z0-9_]+)*(?:\.react|\.graphql|\.next)?$/;
const MODULE_HINT = /\.react$|\$normalization\.graphql$|\.graphql$|(?:Plugin|Renderer|Strategy|Section|Container|Component)(?:\.next)?$/;

export function isModuleName(value: string): boolean {
  return value.length >= 10 && value.length <= 120 && MODULE_NAME.test(value) && MODULE_HINT.test(value) && !/\d{5,}/.test(value);
}

const IDENTIFIER = /^[A-Za-z_$][A-Za-z0-9_$]*$/;
const ENUM = /^[A-Z][A-Z0-9_]{1,40}$/;
const LOWER_CONSTANT = /^[a-z][a-z0-9_]{1,40}$/;
const MODULE_REF = /^[A-Za-z0-9_$.]{1,120}$/;
const LABEL = /^[A-Za-z0-9_]+\$(stream|defer)\$[A-Za-z0-9_$]+$/;
const PLACEHOLDER = /^(!R\**!|\*{1,2}|!T:[A-Za-z0-9_.:-]+!)$/;

/**
 * True if `value`, found under `key`, may stay in a fixture: a type name, a renderer, strategy
 * or module name, an incremental-delivery label, an enum-like constant, a known client
 * constant, a MIME type or codec, or a redaction placeholder.
 */
export function isStructural(key: string | undefined, value: string): boolean {
  if (PLACEHOLDER.test(value)) return true;
  if (key === undefined) return false;
  if (TEXT_KEYS.has(key)) return false;
  if (key === "__typename" || key.startsWith("__is")) return IDENTIFIER.test(value) && value.length <= 80;
  if (isModuleName(value)) return true;
  if (LOWER_CONSTANT_KEYS.has(key) && LOWER_CONSTANT.test(value)) return true;
  if (key === "__dr" || key === "__jsr") return MODULE_REF.test(value) && !/\d{5,}/.test(value);
  if (key === "label") return LABEL.test(value);
  // Elements of an incremental document's path are schema field names.
  if (key === "path") return IDENTIFIER.test(value) && value.length <= 80 && !/\d{5,}/.test(value);
  if (CONSTANT_KEYS.has(key)) {
    if (key === "mime_type") return /^[a-z]+\/[a-z0-9.+-]+$/.test(value);
    if (key === "codecs") return /^[a-z0-9.]{3,40}$/i.test(value);
    return value.length <= 60 && /^[A-Za-z0-9_. -]+$/.test(value) && !/\d{5,}/.test(value);
  }
  if (ENUM.test(value)) return true;
  return false;
}

/** Variable names of requests whose lowercase string values are constants (render locations and the like). */
export function isStructuralVariable(key: string, value: string): boolean {
  return isStructural(key, value) || (CONSTANT_KEYS.has(key) && LOWER_CONSTANT.test(value));
}

/** Object keys that are schema field names; anything else (ids, URLs, hashes as keys) is replaced. */
export function isStructuralKey(key: string): boolean {
  return /^[A-Za-z_$][A-Za-z0-9_$]{0,100}$/.test(key) && !/\d{5,}/.test(key);
}

/** Keys whose numbers are times: shifted when they look like epoch seconds or milliseconds. */
export const TIME_KEY = /time|timestamp|Timestamp|_at$|At$|^expire|expiration|date|created|updated|deadline|^ts$|_ts$|epoch/;

/** True if the sanitizer replaces this number (an identifier or an epoch time). Counts, sizes and durations stay. */
export function numberIsRemapped(key: string | undefined, value: number): boolean {
  if (!Number.isInteger(value) || value <= 0) return false;
  if (TIME_KEY.test(key ?? "")) return (value >= 1_000_000_000 && value <= 2_100_000_000) || (value >= 1_000_000_000_000 && value <= 2_100_000_000_000);
  return isIdOrTimeKey(key) || value >= 100_000_000_000;
}

/** Number keys whose values are identifiers or times (remapped); other numbers are kept. */
export function isIdOrTimeKey(key: string | undefined): boolean {
  if (key === undefined) return false;
  return /(^|_)(id|ids|fbid|fbids)$|Id$|ID$|^id_|_id_|time|timestamp|Timestamp|_at$|At$|^expire|expiration|date|created|updated|deadline|^ts$|_ts$|epoch/.test(key);
}

/** Form fields of a request kept verbatim (constants of the client; no identity). */
export const KEPT_FORM_FIELDS = new Set(["__a", "__aaid", "__comet_req", "__ccg", "dpr", "server_timestamps", "fb_api_caller_class", "__crn", "__spin_b", "fb_api_req_friendly_name", "doc_id"]);

export interface EnumAllowlist {
  about: string;
  /** Enum-like values (ALL_CAPS) reviewed as constants of the schema or the client. */
  values: string[];
}

/**
 * The classifier shared by the sanitizer and the leak test: structural per isStructural, with
 * enum-like constants limited to the reviewed values.
 */
export function makeClassifier(allow: EnumAllowlist, vocabulary: ReadonlySet<string> = new Set()): (key: string | undefined, value: string, variable?: boolean) => boolean {
  const values = new Set(allow.values);
  return (key, value, variable = false) => {
    // Words of the schema itself (field names and type names of the GraphQL data) are
    // structural wherever they occur: a single schema word carries no personal information,
    // and replacing it would break paths and type names.
    if (vocabulary.has(value)) return true;
    if (!(variable ? isStructuralVariable(key ?? "", value) : isStructural(key, value))) return false;
    if (/^[A-Z][A-Z0-9_]{1,40}$/.test(value) && key !== "__typename" && !(key ?? "").startsWith("__is") && key !== "__ccg" && key !== "__crn") return values.has(value);
    return true;
  };
}

/** Field names and type names of the GraphQL data: identifier-shaped, 3 to 80 characters. */
export function addVocabulary(vocabulary: Set<string>, doc: unknown): void {
  const stack: unknown[] = [doc];
  while (stack.length > 0) {
    const v = stack.pop();
    if (Array.isArray(v)) stack.push(...v);
    else if (typeof v === "object" && v !== null) {
      for (const [k, x] of Object.entries(v)) {
        if (k.length >= 3 && isStructuralKey(k) && /^[A-Za-z_$][A-Za-z0-9_$]*$/.test(k)) vocabulary.add(k);
        if ((k === "__typename" || k.startsWith("__is")) && typeof x === "string" && /^[A-Za-z_$][A-Za-z0-9_$]{2,79}$/.test(x)) vocabulary.add(x);
        if (typeof x === "object" && x !== null) stack.push(x);
      }
    }
  }
}
