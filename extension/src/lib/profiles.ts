// Site profiles: which hosts twin-bridge treats as one site, and what it may do there.
//
// - mock: the loopback mock server of the tests. Everything M1 does: enforce, observe or off,
//   replay, header rewrite for replay, the anchor content script.
// - site: the real site. Observe only: its filter mode is locked to observe or off, replay and
//   header rewriting are refused, and no content script matches its hosts (manifest.json).
//   Its listeners exist only while a capture of it is running.
//
// Request metadata is recorded for every host a profile's pages contact (third parties
// included); bodies only for the profile's own hosts.

export type FilterMode = "enforce" | "observe" | "off";
export type ProfileName = "mock" | "site";

export interface SiteProfile {
  readonly name: ProfileName;
  /** webRequest match patterns of the profile's own hosts (ports are ignored by match patterns). */
  readonly urlPatterns: readonly string[];
  readonly allowedModes: readonly FilterMode[];
  readonly defaultMode: FilterMode;
  /** Replay (`replay.fetch`) may target this profile's hosts. */
  readonly replay: boolean;
  /** Request headers of the extension's own requests to these hosts may be rewritten. */
  readonly rewriteHeaders: boolean;
  /** The rule its filters run: the M1 mock ad rule, or the observe-only probe. */
  readonly rule: "mock-ad" | "probe";
  isOwnHost(host: string): boolean;
}

export const MOCK_PROFILE: SiteProfile = Object.freeze({
  name: "mock",
  urlPatterns: Object.freeze(["http://127.0.0.1/*", "http://localhost/*"]),
  allowedModes: Object.freeze(["enforce", "observe", "off"] as FilterMode[]),
  defaultMode: "enforce",
  replay: true,
  rewriteHeaders: true,
  rule: "mock-ad",
  isOwnHost: (host: string) => host === "127.0.0.1" || host === "localhost",
});

export const SITE_PROFILE: SiteProfile = Object.freeze({
  name: "site",
  urlPatterns: Object.freeze(["*://facebook.com/*", "*://*.facebook.com/*"]),
  allowedModes: Object.freeze(["observe", "off"] as FilterMode[]),
  defaultMode: "observe",
  replay: false,
  rewriteHeaders: false,
  rule: "probe",
  isOwnHost: (host: string) => host === "facebook.com" || host.endsWith(".facebook.com"),
});

export const PROFILES: readonly SiteProfile[] = Object.freeze([MOCK_PROFILE, SITE_PROFILE]);

export function profileByName(name: string): SiteProfile | undefined {
  return PROFILES.find((p) => p.name === name);
}

export function hostOf(url: string | undefined): string | null {
  if (url === undefined || url === "") return null;
  try {
    return new URL(url).hostname;
  } catch {
    return null;
  }
}

/** The profile whose own hosts include `url`'s host. */
export function ownProfileOf(url: string | undefined): SiteProfile | undefined {
  const host = hostOf(url);
  return host === null ? undefined : PROFILES.find((p) => p.isOwnHost(host));
}

export interface RequestLike {
  url: string;
  documentUrl?: string | undefined;
  originUrl?: string | undefined;
  frameAncestors?: { url: string }[] | undefined;
}

export interface Membership {
  profile: SiteProfile;
  /** True if the request goes to one of the profile's own hosts. */
  own: boolean;
}

/**
 * Which profile a request belongs to: the profile of its own host, else the profile of the
 * document, origin or any ancestor frame that made it (a third-party request of that site).
 */
export function membershipOf(details: RequestLike): Membership | undefined {
  const own = ownProfileOf(details.url);
  if (own !== undefined) return { profile: own, own: true };
  const contexts = [details.documentUrl, details.originUrl, ...(details.frameAncestors ?? []).map((f) => f.url)];
  for (const u of contexts) {
    const p = ownProfileOf(u);
    if (p !== undefined) return { profile: p, own: false };
  }
  return undefined;
}

export class ModeError extends Error {
  readonly code = "mode_not_allowed";
}

/** Filter mode per profile. The site profile can never be put into enforce. */
export class FilterModes {
  private readonly modes = new Map<ProfileName, FilterMode>(PROFILES.map((p) => [p.name, p.defaultMode]));

  get(profile: SiteProfile): FilterMode {
    return this.modes.get(profile.name) ?? "off";
  }

  set(profile: SiteProfile, mode: FilterMode): void {
    if (!profile.allowedModes.includes(mode)) {
      throw new ModeError(`filter mode ${mode} is not allowed for profile ${profile.name} (allowed: ${profile.allowedModes.join(", ")})`);
    }
    this.modes.set(profile.name, mode);
  }

  snapshot(): Record<string, FilterMode> {
    return Object.fromEntries(this.modes);
  }
}
