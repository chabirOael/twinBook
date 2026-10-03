package io.github.chabiroael.twinbook.data.links

import java.net.URLDecoder

/** What the web shell does with a top-level navigation. */
sealed interface LinkDecision {
    /** Load [url] in the shell. [rewritten] when it came out of a redirect page, so it replaces the original navigation. */
    data class Stay(val url: String, val rewritten: Boolean = false) : LinkDecision

    /** Open [url] in the default browser, never in the shell. */
    data class Browser(val url: String) : LinkDecision

    /** Hand [url] (`tel:`, `mailto:`, `geo:`) to the system. */
    data class System(val url: String) : LinkDecision

    /** Not an http(s) link and not one the system takes: the engine's own scheme rules decide. */
    data object EngineDefault : LinkDecision

    /** Drop it. [reason] is for logs and never contains the URL. */
    data class Ignore(val reason: String) : LinkDecision
}

/** A host of the site: the host itself, and its subdomains when [subdomains]. */
data class OwnHost(val host: String, val subdomains: Boolean)

/** The site's outbound-link redirect page: `<host><path>?<param>=<target>`. */
data class RedirectRule(val hosts: Set<String>, val path: String, val param: String)

/** A tracking query parameter: an exact name, or every name starting with a prefix. */
data class TrackingParam(val name: String, val prefix: Boolean) {
    fun matches(param: String): Boolean = if (prefix) param.startsWith(name) else param == name
}

/** The site's hosts as far as navigation is concerned. Injectable, so tests use the mock's. */
data class SiteHosts(val own: List<OwnHost>, val redirects: List<RedirectRule>) {
    fun isOwn(host: String): Boolean {
        val h = normalizeHost(host)
        return own.any { h == it.host || (it.subdomains && h.endsWith("." + it.host)) }
    }

    fun redirectFor(host: String, path: String): RedirectRule? {
        val h = normalizeHost(host)
        return redirects.firstOrNull { h in it.hosts && path == it.path }
    }
}

/**
 * Link hygiene of the web shell (docs/SHELL.md section 3), from `rules/links-v1.json`:
 *
 * - `tel:`, `mailto:`, `geo:` go to the system; other non-http(s) schemes are left to the
 *   engine (which ignores anything it does not load itself).
 * - The redirect page is unwrapped without being loaded: its target parameter is the next URL,
 *   repeatedly (nested redirects, at most [MAX_UNWRAP] levels), decoded once more if it is still
 *   percent-encoded. A redirect without a usable http(s) target is ignored.
 * - Tracking parameters are removed from every target that leaves the shell or came out of a
 *   redirect. Navigation within the site is never rewritten.
 * - A target on an own host stays in the shell; any other http(s) target opens in the browser.
 */
class LinkRules(val site: SiteHosts, val trackingParams: List<TrackingParam>, val startUrl: String) {
    fun decide(url: String): LinkDecision {
        var current = url.trim()
        var unwrapped = false
        repeat(MAX_UNWRAP + 1) {
            val scheme = schemeOf(current) ?: return LinkDecision.Ignore("no scheme")
            when (scheme) {
                "tel", "mailto", "geo" -> return LinkDecision.System(current)
                "http", "https" -> Unit
                else -> return if (unwrapped) LinkDecision.Ignore("redirect target scheme $scheme") else LinkDecision.EngineDefault
            }
            val parts = UrlParts.parse(current) ?: return LinkDecision.Ignore("malformed URL")
            if (parts.host.isEmpty()) return LinkDecision.Ignore("no host")
            val redirect = site.redirectFor(parts.host, parts.path)
            if (redirect == null) {
                if (site.isOwn(parts.host)) {
                    return if (unwrapped) LinkDecision.Stay(stripTracking(current), rewritten = true) else LinkDecision.Stay(current)
                }
                return LinkDecision.Browser(stripTracking(current))
            }
            current = targetOf(parts, redirect) ?: return LinkDecision.Ignore("redirect without a usable target")
            unwrapped = true
        }
        return LinkDecision.Ignore("redirects nested deeper than $MAX_UNWRAP")
    }

    /** Removes [trackingParams] from the query of [url]; everything else is kept byte for byte. */
    fun stripTracking(url: String): String {
        val hash = url.indexOf('#')
        val beforeFragment = if (hash >= 0) url.substring(0, hash) else url
        val fragment = if (hash >= 0) url.substring(hash) else ""
        val q = beforeFragment.indexOf('?')
        if (q < 0) return url
        val base = beforeFragment.substring(0, q)
        val kept = beforeFragment.substring(q + 1).split('&').filter { pair ->
            if (pair.isEmpty()) return@filter false
            val name = decodeOrNull(pair.substringBefore('=')) ?: pair.substringBefore('=')
            trackingParams.none { it.matches(name) }
        }
        return base + (if (kept.isEmpty()) "" else "?" + kept.joinToString("&")) + fragment
    }

    private fun targetOf(parts: UrlParts, rule: RedirectRule): String? {
        val raw = parts.queryPairs().firstOrNull { (k, _) -> decodeOrNull(k) == rule.param }?.second ?: return null
        var target = decodeOrNull(raw)?.trim() ?: return null
        // A target that is still percent-encoded (https%3A%2F%2F...) is decoded once more.
        if (Regex("^(https?|tel|mailto|geo)%3A", RegexOption.IGNORE_CASE).containsMatchIn(target)) target = decodeOrNull(target)?.trim() ?: return null
        val scheme = schemeOf(target) ?: return null
        if (scheme in setOf("http", "https") && UrlParts.parse(target)?.host.isNullOrEmpty()) return null
        return target
    }

    companion object {
        const val MAX_UNWRAP = 5
        private const val RESOURCE = "/links-v1.json"

        /** The real site's rules, from `rules/links-v1.json` (packaged as a resource of :data). */
        fun forSite(): LinkRules = fromJson(LinkRules::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("missing $RESOURCE"))

        @Suppress("UNCHECKED_CAST")
        fun fromJson(text: String): LinkRules {
            val root = MiniJson.parse(text) as Map<String, Any?>
            val own = (root["ownHosts"] as List<Map<String, Any?>>).map { OwnHost(normalizeHost(it["host"] as String), it["subdomains"] == true) }
            val redirects = (root["redirects"] as List<Map<String, Any?>>).map { r ->
                RedirectRule((r["hosts"] as List<String>).map(::normalizeHost).toSet(), r["path"] as String, r["param"] as String)
            }
            val params = (root["trackingParams"] as List<Map<String, Any?>>).map {
                val prefix = it["prefix"] as String?
                TrackingParam(prefix ?: it["name"] as String, prefix != null)
            }
            return LinkRules(SiteHosts(own, redirects), params, root["startUrl"] as String)
        }

        /** The same tracking parameters with other hosts and start URL (the test mock). */
        fun withSite(site: SiteHosts, startUrl: String, base: LinkRules = forSite()): LinkRules = LinkRules(site, base.trackingParams, startUrl)

        internal fun schemeOf(url: String): String? = Regex("^([A-Za-z][A-Za-z0-9+.-]*):").find(url)?.groupValues?.get(1)?.lowercase()

        // URLDecoder.decode(String, Charset) needs Android API 33; the String overload works on every level.
        internal fun decodeOrNull(s: String): String? = try {
            URLDecoder.decode(s, "UTF-8")
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

internal fun normalizeHost(host: String): String = host.lowercase().trimEnd('.')

/**
 * A lenient split of an http(s) URL into host, path and query, as a browser reads it: the
 * authority ends at the first `/`, `?`, `#` or `\`, user info before the last `@` is dropped,
 * and characters java.net.URI would reject (`[`, `|`, spaces in the query) are accepted.
 */
internal class UrlParts private constructor(val host: String, val path: String, val query: String?) {
    fun queryPairs(): List<Pair<String, String>> =
        query.orEmpty().split('&').filter { it.isNotEmpty() }.map { it.substringBefore('=') to it.substringAfter('=', "") }

    companion object {
        fun parse(url: String): UrlParts? {
            val m = Regex("^[A-Za-z][A-Za-z0-9+.-]*:[/\\\\]{2}([^/?#\\\\]*)([^?#]*)(\\?[^#]*)?").find(url) ?: return null
            var authority = m.groupValues[1]
            authority = authority.substringAfterLast('@')
            val host = if (authority.startsWith("[")) authority.substringBefore(']') + "]" else authority.substringBefore(':')
            if (host.any { it.isWhitespace() || it == '%' }) return null
            val path = m.groupValues[2].replace('\\', '/').ifEmpty { "/" }
            val query = m.groupValues[3].takeIf { it.isNotEmpty() }?.substring(1)
            return UrlParts(normalizeHost(host), path, query)
        }
    }
}
