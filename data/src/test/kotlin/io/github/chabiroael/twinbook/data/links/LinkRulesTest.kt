package io.github.chabiroael.twinbook.data.links

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** S6: the link rules of the web shell, as a table of cases (docs/SHELL.md section 3). */
class LinkRulesTest {
    private val rules = LinkRules.forSite()

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
    private fun shim(target: String, host: String = "lm.facebook.com") = "https://$host/l.php?u=${enc(target)}&h=AT0abcDEF123"

    private val cases: List<Pair<String, LinkDecision>> = listOf(
        // Navigation within the site stays, untouched (even its own tracking parameters).
        "https://m.facebook.com/" to LinkDecision.Stay("https://m.facebook.com/"),
        "https://m.facebook.com/story.php?story_fbid=1&id=2&__cft__[0]=AZx&__tn__=R" to LinkDecision.Stay("https://m.facebook.com/story.php?story_fbid=1&id=2&__cft__[0]=AZx&__tn__=R"),
        "https://www.facebook.com/groups/1/" to LinkDecision.Stay("https://www.facebook.com/groups/1/"),
        "https://facebook.com/x" to LinkDecision.Stay("https://facebook.com/x"),
        "https://M.FaceBook.com./home.php" to LinkDecision.Stay("https://M.FaceBook.com./home.php"),
        "https://scontent.xx.fbcdn.net/v/t39/photo.jpg?stp=x" to LinkDecision.Stay("https://scontent.xx.fbcdn.net/v/t39/photo.jpg?stp=x"),
        "https://www.fbsbx.com/x" to LinkDecision.Stay("https://www.fbsbx.com/x"),
        // Look-alike hosts are not the site.
        "https://facebook.com.evil.example/x?fbclid=1" to LinkDecision.Browser("https://facebook.com.evil.example/x"),
        "https://evilfacebook.com/" to LinkDecision.Browser("https://evilfacebook.com/"),
        "https://m.facebook.com@evil.example/" to LinkDecision.Browser("https://m.facebook.com@evil.example/"),
        // Outbound: browser, tracking parameters removed, the rest kept byte for byte.
        "https://example.com/a?x=1&fbclid=IwAR0abc&utm_source=fb&utm_medium=social&y=%20z#frag" to LinkDecision.Browser("https://example.com/a?x=1&y=%20z#frag"),
        "https://example.com/?fbclid=1" to LinkDecision.Browser("https://example.com/"),
        "http://example.com/plain" to LinkDecision.Browser("http://example.com/plain"),
        "https://example.com/?utm_campaign=a&gclid=b&msclkid=c&mc_eid=d&_hsenc=e&keep=f" to LinkDecision.Browser("https://example.com/?keep=f"),
        "https://example.com/?fbclidx=keep&xfbclid=keep2" to LinkDecision.Browser("https://example.com/?fbclidx=keep&xfbclid=keep2"),
        "https://example.com/?fb%63lid=encoded-name" to LinkDecision.Browser("https://example.com/"),
        // The redirect page, unwrapped without loading it.
        shim("https://example.com/article?id=7&fbclid=IwAR1") to LinkDecision.Browser("https://example.com/article?id=7"),
        shim("https://example.com/p?a=1&b=2", host = "l.facebook.com") to LinkDecision.Browser("https://example.com/p?a=1&b=2"),
        "https://lm.facebook.com/l.php?h=AT1&u=https%3A%2F%2Fexample.com%2F%3Futm_source%3Dfacebook%26q%3Dcats%23top&s=1" to LinkDecision.Browser("https://example.com/?q=cats#top"),
        // Encoded targets: UTF-8 path, an encoded parameter, a target that is encoded twice.
        shim("https://example.com/caf%C3%A9?q=%26amp&fbclid=x") to LinkDecision.Browser("https://example.com/caf%C3%A9?q=%26amp"),
        "https://lm.facebook.com/l.php?u=${enc(enc("https://example.com/twice?fbclid=1&k=v"))}&h=x" to LinkDecision.Browser("https://example.com/twice?k=v"),
        "https://lm.facebook.com/l.php?u=https://example.com/raw%3Fz%3D1&h=x" to LinkDecision.Browser("https://example.com/raw?z=1"),
        // Nested redirects.
        shim(shim("https://example.com/nested?fbclid=1")) to LinkDecision.Browser("https://example.com/nested"),
        shim(shim(shim("https://example.com/three"), host = "l.facebook.com")) to LinkDecision.Browser("https://example.com/three"),
        // A target on the site's own hosts stays in the shell, as a replacement navigation.
        shim("https://m.facebook.com/groups/42/?fbclid=1&ref=share") to LinkDecision.Stay("https://m.facebook.com/groups/42/?ref=share", rewritten = true),
        shim("https://www.facebook.com/events/9") to LinkDecision.Stay("https://www.facebook.com/events/9", rewritten = true),
        // A redirect target handed to the system.
        shim("mailto:someone@example.com") to LinkDecision.System("mailto:someone@example.com"),
        // Malformed: never loaded, never opened.
        "https://lm.facebook.com/l.php?h=AT0" to LinkDecision.Ignore("redirect without a usable target"),
        "https://lm.facebook.com/l.php?u=&h=AT0" to LinkDecision.Ignore("redirect without a usable target"),
        "https://lm.facebook.com/l.php?u=%ZZbad" to LinkDecision.Ignore("redirect without a usable target"),
        "https://lm.facebook.com/l.php?u=not%20a%20url" to LinkDecision.Ignore("redirect without a usable target"),
        "https://lm.facebook.com/l.php?u=https%3A%2F%2F%2Fpath-only" to LinkDecision.Ignore("redirect without a usable target"),
        shim("javascript:alert(1)") to LinkDecision.Ignore("redirect target scheme javascript"),
        shim("intent://x#Intent;scheme=fb;end") to LinkDecision.Ignore("redirect target scheme intent"),
        shim("fb://profile/1") to LinkDecision.Ignore("redirect target scheme fb"),
        "https:///no-host" to LinkDecision.Ignore("no host"),
        "no scheme at all" to LinkDecision.Ignore("no scheme"),
        "" to LinkDecision.Ignore("no scheme"),
        // Other paths on the redirect hosts are not the redirect page; those hosts are the site's.
        "https://lm.facebook.com/other.php?u=https%3A%2F%2Fexample.com" to LinkDecision.Stay("https://lm.facebook.com/other.php?u=https%3A%2F%2Fexample.com"),
        // System schemes.
        "tel:+15551234567" to LinkDecision.System("tel:+15551234567"),
        "mailto:a@example.com?subject=hi" to LinkDecision.System("mailto:a@example.com?subject=hi"),
        "geo:0,0?q=Doha" to LinkDecision.System("geo:0,0?q=Doha"),
        "TEL:123" to LinkDecision.System("TEL:123"),
        // Everything else is left to the engine (which loads about:, data:, blob: and ignores the rest).
        "intent://profile/1#Intent;scheme=fb;end" to LinkDecision.EngineDefault,
        "fb://profile/1" to LinkDecision.EngineDefault,
        "about:blank" to LinkDecision.EngineDefault,
        "data:text/html,x" to LinkDecision.EngineDefault,
    )

    @Test
    fun tableOfCases() {
        val failures = cases.mapNotNull { (url, expected) ->
            val got = rules.decide(url)
            if (got == expected) null else "decide($url)\n  expected $expected\n  got      $got"
        }
        assertTrue("${failures.size} of ${cases.size} cases failed:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun nestingDeeperThanTheLimitIsIgnored() {
        var url = "https://example.com/deep"
        repeat(LinkRules.MAX_UNWRAP + 1) { url = shim(url) }
        assertEquals(LinkDecision.Ignore("redirects nested deeper than ${LinkRules.MAX_UNWRAP}"), rules.decide(url))
        var ok = "https://example.com/deep"
        repeat(LinkRules.MAX_UNWRAP) { ok = shim(ok) }
        assertEquals(LinkDecision.Browser("https://example.com/deep"), rules.decide(ok))
    }

    @Test
    fun dataFileHasAReasonForEveryEntryAndTheExpectedHosts() {
        val text = LinkRules::class.java.getResourceAsStream("/links-v1.json")!!.readBytes().toString(Charsets.UTF_8)
        @Suppress("UNCHECKED_CAST")
        val root = MiniJson.parse(text) as Map<String, Any?>
        for (key in listOf("ownHosts", "redirects", "trackingParams")) {
            @Suppress("UNCHECKED_CAST")
            for (e in root[key] as List<Map<String, Any?>>) assertTrue("$key entry without a reason: $e", (e["why"] as String?).orEmpty().length > 20)
        }
        assertEquals(listOf("facebook.com", "fbcdn.net", "fbsbx.com"), rules.site.own.map { it.host })
        assertEquals("https://m.facebook.com/", rules.startUrl)
        assertTrue(rules.trackingParams.any { it.name == "fbclid" && !it.prefix })
    }

    @Test
    fun mockSiteUsesTheSameParametersWithItsOwnHosts() {
        val mock = LinkRules.withSite(
            SiteHosts(listOf(OwnHost("127.0.0.1", false)), listOf(RedirectRule(setOf("127.0.0.1"), "/l.php", "u"))),
            "http://127.0.0.1:4000/shell/home.html",
        )
        assertEquals(LinkDecision.Stay("http://127.0.0.1:4000/shell/feed.html"), mock.decide("http://127.0.0.1:4000/shell/feed.html"))
        assertEquals(
            LinkDecision.Browser("https://example.com/target?keep=1"),
            mock.decide("http://127.0.0.1:4000/l.php?u=${enc("https://example.com/target?keep=1&fbclid=x&utm_source=m")}&h=1"),
        )
        assertEquals(LinkDecision.Stay("http://127.0.0.1:4000/shell/feed.html", rewritten = true), mock.decide("http://127.0.0.1:4000/l.php?u=${enc("http://127.0.0.1:4000/shell/feed.html?fbclid=1")}"))
        assertEquals(LinkDecision.Browser("https://m.facebook.com/"), mock.decide("https://m.facebook.com/"))
    }
}
