package io.github.kyotobyte.cdmplus.ui

import io.github.kyotobyte.cdmplus.data.AdRules
import org.junit.Assert.*
import org.junit.Test

class BrowserPolicyTest {
    private fun blocked(url: String, page: String? = "example.com", main: Boolean = false, off: Set<String> = emptySet(), lists: ((String) -> Boolean)? = null): Boolean {
        val u = java.net.URI(url)
        return AdRules.shouldBlock(u.host, u.rawPath ?: "", u.rawQuery, page, main, off, lists)
    }

    @Test fun webSchemesLoadInWebView() {
        for (u in listOf("https://a.com", "http://a.com/x?y", "about:blank", "data:text/html,hi", "blob:https://a.com/1")) assertEquals(u, WebPolicy.Route.Web, WebPolicy.route(u))
    }

    @Test fun appSchemesGoToApps() {
        assertEquals(WebPolicy.Route.External("market"), WebPolicy.route("market://details?id=com.whatsapp"))
        assertEquals(WebPolicy.Route.External("upi"), WebPolicy.route("upi://pay?pa=x@upi&am=1"))
        assertEquals(WebPolicy.Route.External("tel"), WebPolicy.route("tel:+911234"))
        assertEquals(WebPolicy.Route.External("mailto"), WebPolicy.route("mailto:a@b.com"))
        assertEquals(WebPolicy.Route.External("whatsapp"), WebPolicy.route("whatsapp://send?text=hi"))
        assertEquals(WebPolicy.Route.External("tg"), WebPolicy.route("tg://resolve?domain=x"))
        assertEquals(WebPolicy.Route.Block, WebPolicy.route("javascript:alert(1)"))
        assertEquals(WebPolicy.Route.Block, WebPolicy.route("file:///sdcard/x"))
    }

    @Test fun intentLinksParseFallbackAndPackage() {
        val u = "intent://scan/#Intent;scheme=zxing;package=com.google.zxing.client.android;S.browser_fallback_url=https%3A%2F%2Fexample.com%2Fapp%3Fa%3D1;end"
        assertEquals(WebPolicy.Route.AppIntent("https://example.com/app?a=1", "com.google.zxing.client.android"), WebPolicy.route(u))
        val noFb = "intent://open#Intent;scheme=instagram;package=com.instagram.android;end"
        assertEquals(WebPolicy.Route.AppIntent(null, "com.instagram.android"), WebPolicy.route(noFb))
        // A javascript: fallback is never used.
        val bad = "intent://x#Intent;scheme=a;S.browser_fallback_url=javascript%3Aalert(1);end"
        assertEquals(WebPolicy.Route.AppIntent(null, null), WebPolicy.route(bad))
        assertEquals("https://play.google.com/store/apps/details?id=com.x", WebPolicy.storeUrl("com.x"))
    }

    @Test fun userAgentLooksLikeChrome() {
        val wv = "Mozilla/5.0 (Linux; Android 14; SM-A146B Build/UP1A.231005.007; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/141.0.7390.122 Mobile Safari/537.36"
        assertEquals("Mozilla/5.0 (Linux; Android 14; SM-A146B Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.7390.122 Mobile Safari/537.36", WebPolicy.chromeLikeUa(wv))
    }

    @Test fun adsBlockedButNeverThePageItself() {
        assertTrue(blocked("https://securepubads.g.doubleclick.net/tag/js/gpt.js"))
        assertTrue(blocked("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js"))
        assertTrue(blocked("https://cdn.taboola.com/libtrc/x.js"))
        // Tapping a Sponsored Google result opens googleadservices as the page: must load.
        assertFalse(blocked("https://www.googleadservices.com/pagead/aclk?sa=L", page = "www.google.com", main = true))
    }

    @Test fun cdnsCaptchasLoginsNeverBlocked() {
        val everything: (String) -> Boolean = { true } // even a list that blocks everything
        for (u in listOf("https://cdn.jsdelivr.net/npm/x", "https://fastly.jsdelivr.net/x", "https://cdnjs.cloudflare.com/ajax/libs/x.js",
            "https://challenges.cloudflare.com/turnstile/v0/api.js", "https://www.gstatic.com/recaptcha/x.js", "https://fonts.gstatic.com/s/x.woff2",
            "https://fonts.googleapis.com/css2", "https://ajax.googleapis.com/x.js", "https://www.recaptcha.net/recaptcha/api.js",
            "https://js.hcaptcha.com/1/api.js", "https://newassets.hcaptcha.com/x", "https://unpkg.com/react", "https://code.jquery.com/jquery.js",
            "https://d1.cloudfront.net/x", "https://rr3---sn-abc.googlevideo.com/videoplayback?x", "https://i.ytimg.com/vi/x/hq.jpg",
            "https://www.googletagmanager.com/gtm.js", "https://checkout.razorpay.com/v1/checkout.js", "https://accounts.google.com/gsi/client",
            "https://js.stripe.com/v3", "https://www.paypalobjects.com/x.js", "https://cdn.segment.com/analytics.js"))
            assertFalse(u, blocked(u, lists = everything))
    }

    @Test fun firstPartyNeverBlockedEvenIfListed() {
        val list: (String) -> Boolean = { it.endsWith("flixcart.com") }
        assertFalse(blocked("https://static-assets-web.flixcart.com/x.js", page = "www.flixcart.com", lists = list))
        assertTrue(blocked("https://static-assets-web.flixcart.com/x.js", page = "www.flipkart.com", lists = list))
        assertEquals("amazon.co.in", AdRules.siteKey("www.amazon.co.in"))
        assertEquals("youtube.com", AdRules.siteKey("m.youtube.com"))
    }

    @Test fun perSiteOffSwitch() {
        assertTrue(blocked("https://ad.doubleclick.net/x", page = "www.ndtv.com"))
        assertFalse(blocked("https://ad.doubleclick.net/x", page = "www.ndtv.com", off = setOf("ndtv.com")))
    }

    @Test fun youTubeAdCallsBlockedVideoNot() {
        val p = "m.youtube.com"
        assertTrue(blocked("https://www.youtube.com/api/stats/ads?ver=2", page = p))
        assertTrue(blocked("https://www.youtube.com/pagead/adview?ai=x", page = p))
        assertTrue(blocked("https://www.youtube.com/ptracking?pltype=adhost", page = p))
        assertTrue(blocked("https://www.youtube.com/get_midroll_info?ei=x", page = p))
        assertTrue(blocked("https://googleads.g.doubleclick.net/pagead/id", page = p))
        assertFalse(blocked("https://m.youtube.com/watch?v=dQw4w9WgXcQ", page = p, main = true))
        assertFalse(blocked("https://www.youtube.com/youtubei/v1/player?key=x", page = p))
        assertFalse(blocked("https://www.youtube.com/s/player/abc/base.js", page = p))
        assertFalse(blocked("https://rr1---sn-x.googlevideo.com/videoplayback?itag=18", page = p))
        assertFalse(blocked("https://www.youtube.com/api/stats/qoe?docid=x", page = p))
        assertTrue(WebPolicy.isYouTube("m.youtube.com")); assertFalse(WebPolicy.isYouTube("notyoutube.com"))
    }

    private fun pruneInRhino(json: String): String {
        val cx = org.mozilla.javascript.Context.enter()
        try {
            cx.optimizationLevel = -1
            val scope = cx.initStandardObjects()
            cx.evaluateString(scope, WebPolicy.YT_PRUNE_FN, "prune", 1, null)
            scope.put("input", scope, json)
            return cx.evaluateString(scope, "JSON.stringify(__cdmPrune(JSON.parse(input)))", "run", 1, null) as String
        } finally { org.mozilla.javascript.Context.exit() }
    }

    @Test fun youTubePlayerResponseAdsStripped() {
        val raw = javaClass.getResource("/yt_player_response.json")!!.readText()
        assertTrue(raw.contains("adPlacements") && raw.contains("playerAds") && raw.contains("adSlots"))
        val out = pruneInRhino(raw)
        for (k in listOf("adPlacements", "playerAds", "adSlots")) assertFalse(k, out.contains("\"$k\""))
        // The video itself is untouched.
        val o = kotlinx.serialization.json.Json.parseToJsonElement(out).toString()
        assertTrue(o.contains("\"videoDetails\"") && o.contains("\"streamingData\"") && o.contains("\"playabilityStatus\""))
        assertTrue(o.contains("dQw4w9WgXcQ") || o.contains("videoId"))
        val wrapped = pruneInRhino(javaClass.getResource("/yt_next_wrapped.json")!!.readText())
        assertFalse(wrapped.contains("\"adPlacements\"")); assertTrue(wrapped.contains("\"videoDetails\""))
        // Non-YouTube JSON passes through unchanged.
        assertEquals("{\"a\":1,\"b\":[1,2]}", pruneInRhino("{\"a\":1,\"b\":[1,2]}"))
    }
}
