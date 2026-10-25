package io.github.kyotobyte.cdmplus.data

/**
 * In-app browser ad-block. Pure matching rules live in [AdRules] (unit tested); this object wires
 * them to the user's settings: global on/off, per-site off list, and the 1.1.1.1 lists while that
 * mode is running. Blocking only ever returns an empty response; nothing is re-fetched or proxied.
 */
object AdBlock {
    @Volatile var siteOff: Set<String> = emptySet()
    private var prefs: android.content.SharedPreferences? = null

    fun init(ctx: android.content.Context) {
        val p = ctx.getSharedPreferences("cdm_browser", android.content.Context.MODE_PRIVATE)
        prefs = p
        siteOff = p.getStringSet("adblock_off_sites", emptySet())?.toSet() ?: emptySet()
    }

    fun isOffFor(pageHost: String?) = AdRules.siteKey(pageHost)?.let { it in siteOff } ?: false

    /** Turns ad-block off (or back on) for one site. Returns the new state: true = ads blocked there. */
    fun toggleSite(pageHost: String?): Boolean {
        val k = AdRules.siteKey(pageHost) ?: return true
        siteOff = if (k in siteOff) siteOff - k else siteOff + k
        prefs?.edit()?.putStringSet("adblock_off_sites", siteOff)?.apply()
        return k !in siteOff
    }

    fun shouldBlock(url: android.net.Uri, pageHost: String?, mainFrame: Boolean): Boolean {
        val vpn = runCatching { io.github.kyotobyte.cdmplus.CdmApp.instance.vpn }.getOrNull()
        // The big 1.1.1.1 lists only join in while that mode is actually switched on.
        val lists: ((String) -> Boolean)? = if (vpn != null && vpn.running.value) { h -> vpn.filter.isBlocked(h) } else null
        return AdRules.shouldBlock(url.host, url.encodedPath ?: "", url.encodedQuery, pageHost, mainFrame, siteOff, lists)
    }

    fun isBlocked(host: String?): Boolean = AdRules.shouldBlock(host, "", null, null, false, emptySet(), null)
}

object AdRules {
    /** Ad / tracking hosts blocked as whole hosts (and their subdomains). Hash-set lookup, no regex. */
    val hosts: Set<String> = hashSetOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "adnxs.com", "adsrvr.org", "advertising.com", "taboola.com", "outbrain.com", "criteo.com", "criteo.net", "pubmatic.com",
        "rubiconproject.com", "openx.net", "casalemedia.com", "moatads.com", "scorecardresearch.com", "zedo.com", "revcontent.com",
        "mgid.com", "propellerads.com", "popads.net", "popcash.net", "adsterra.com", "exoclick.com", "juicyads.com", "trafficjunky.net",
        "hilltopads.net", "onclickads.net", "adcash.com", "yllix.com", "ad-maven.com", "admaven.com", "clickadu.com", "bidvertiser.com",
        "media.net", "amazon-adsystem.com", "smartadserver.com", "teads.tv", "yieldmo.com", "sharethrough.com", "33across.com",
        "quantserve.com", "hotjar.com", "mouseflow.com", "adform.net", "serving-sys.com", "inmobi.com", "unityads.unity3d.com",
        "applovin.com", "vungle.com", "chartboost.com", "startappservice.com", "mopub.com", "adcolony.com", "ads.yahoo.com",
        "adtechus.com", "googletagservices.com", "googleads.g.doubleclick.net", "ads.youtube.com",
    )

    /** Hosts sites need to work (CDNs, captchas, logins, payments, video). Never blocked, whatever a list says. */
    val neverBlock: Set<String> = hashSetOf(
        "jsdelivr.net", "cdnjs.cloudflare.com", "challenges.cloudflare.com", "cloudflare.com", "cloudflare.net",
        "gstatic.com", "googleapis.com", "recaptcha.net", "hcaptcha.com", "unpkg.com", "jquery.com", "bootstrapcdn.com",
        "cloudfront.net", "akamaihd.net", "akamaized.net", "fastly.net", "fastly.com", "ytimg.com", "googlevideo.com",
        "googletagmanager.com", "segment.com", "segment.io", "branch.io", "app.link", "onesignal.com",
        "razorpay.com", "paytm.in", "paytm.com", "stripe.com", "stripe.network", "paypal.com", "paypalobjects.com",
        "fontawesome.com", "shopify.com", "accounts.google.com", "github.githubassets.com", "githubusercontent.com",
    )

    /** URL-path rules for ad calls that share a host with real content (YouTube, Google). */
    private val pathRules: List<Pair<String, String>> = listOf(
        "youtube.com" to "/api/stats/ads", "youtube.com" to "/pagead/", "youtube.com" to "/ptracking",
        "youtube.com" to "/get_midroll_info", "youtube.com" to "/api/stats/atr", "youtube.com" to "/youtubei/v1/ad_break",
        "youtube.com" to "/pagead", "youtube.com" to "/csi_204", "youtube-nocookie.com" to "/api/stats/ads",
        "google.com" to "/pagead/", "google.com" to "/adsense/",
    )

    private val twoPartTlds = hashSetOf("co.in", "co.uk", "com.au", "co.jp", "com.br", "co.nz", "org.in", "net.in", "gov.in", "ac.in", "co.za", "com.sg", "com.my", "com.pk", "com.bd", "com.np")

    /** "m.youtube.com" -> "youtube.com", "www.amazon.co.in" -> "amazon.co.in". */
    fun siteKey(host: String?): String? {
        val h = host?.lowercase()?.trimEnd('.')?.takeIf { it.isNotBlank() } ?: return null
        val parts = h.split('.')
        if (parts.size <= 2) return h
        val last2 = parts.takeLast(2).joinToString(".")
        return if (last2 in twoPartTlds) parts.takeLast(3).joinToString(".") else last2
    }

    fun suffixIn(host: String, set: Set<String>): Boolean {
        var h = host
        while (true) {
            if (h in set) return true
            val dot = h.indexOf('.')
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) return h in set
            h = h.substring(dot + 1)
        }
    }

    /**
     * The whole decision. Never blocks: the page itself (main frame), the page's own site,
     * CDN/captcha/login/payment hosts, or any page whose site the user switched ad-block off for.
     */
    fun shouldBlock(host: String?, path: String, query: String?, pageHost: String?, mainFrame: Boolean,
                    siteOff: Set<String>, lists: ((String) -> Boolean)?): Boolean {
        if (mainFrame) return false
        val h = host?.lowercase()?.trimEnd('.')?.takeIf { it.isNotBlank() } ?: return false
        val page = siteKey(pageHost)
        if (page != null && page in siteOff) return false
        val site = siteKey(h)
        // Same-site ad paths (YouTube / Google): checked before the first-party rule.
        if (site != null) for ((s, p) in pathRules) if (site == s && path.startsWith(p)) return true
        if (site == "youtube.com" && path == "/api/stats/qoe" && query?.contains("adformat") == true) return true
        if (suffixIn(h, neverBlock)) return false
        if (page != null && site == page) return false
        if (suffixIn(h, hosts)) return true
        return lists?.invoke(h) ?: false
    }
}
