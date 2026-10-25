package io.github.kyotobyte.cdmplus.ui

/** Pure link-routing and user-agent rules for the in-app browser (unit tested, no Android types). */
object WebPolicy {
    sealed class Route {
        /** Load in the WebView as normal. */
        object Web : Route()
        /** intent:// link: hand to Android; [fallback] is the site's browser_fallback_url, [pkg] the app. */
        data class AppIntent(val fallback: String?, val pkg: String?) : Route()
        /** Any other app scheme (market:, tel:, mailto:, upi:, whatsapp:, tg:, geo: …): open with an app. */
        data class External(val scheme: String) : Route()
        /** Never navigate (javascript:, file:, content: from a web page). */
        object Block : Route()
    }

    private val webSchemes = setOf("http", "https", "about", "data", "blob")

    fun scheme(url: String): String {
        val i = url.indexOf(':')
        if (i <= 0) return ""
        val s = url.substring(0, i)
        return if (s.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }) s.lowercase() else ""
    }

    fun route(url: String): Route {
        val s = scheme(url.trim())
        return when {
            s.isEmpty() || s in webSchemes -> Route.Web
            s == "javascript" || s == "file" || s == "content" -> Route.Block
            s == "intent" -> Route.AppIntent(intentExtra(url, "S.browser_fallback_url")?.takeIf { scheme(it) == "http" || scheme(it) == "https" }, intentExtra(url, "package"))
            else -> Route.External(s)
        }
    }

    /** Reads one field from the #Intent;...;end part of an intent:// URL (values are %-encoded). */
    fun intentExtra(url: String, key: String): String? {
        val start = url.indexOf("#Intent;")
        if (start < 0) return null
        val body = url.substring(start + 8).substringBefore(";end")
        for (part in body.split(';')) {
            val eq = part.indexOf('=')
            if (eq > 0 && part.substring(0, eq) == key) return runCatching { java.net.URLDecoder.decode(part.substring(eq + 1), "UTF-8") }.getOrNull()
        }
        return null
    }

    /** Play Store web page for an app package, used when the app isn't installed and the site gave no fallback. */
    fun storeUrl(pkg: String) = "https://play.google.com/store/apps/details?id=$pkg"

    /**
     * Chrome-like mobile UA from the WebView default: drops the "; wv" marker and "Version/4.0 ", which
     * Google sign-in and some sites use to refuse in-app browsers. Everything else (Chrome version,
     * device, Android version) stays the real one.
     */
    fun chromeLikeUa(defaultUa: String): String =
        defaultUa.replace("; wv)", ")").replace(" Version/4.0", "").replace("  ", " ")

    /** Short human hint for a WebView error code (WebViewClient.ERROR_*). */
    fun errorHint(code: Int): String = when (code) {
        -2 -> "The site's address couldn't be found. Check the spelling or your connection. If it opens in Chrome, try switching 1.1.1.1 mode off."
        -6 -> "The site refused the connection."
        -8 -> "The site took too long to answer."
        -7, -15 -> "Couldn't reach the site. Check your connection."
        -11 -> "Secure connection failed."
        -12 -> "That link isn't a valid address."
        -10 -> "No app can open this kind of link."
        -9 -> "Too many redirects."
        -14 -> "The page wasn't found."
        else -> "The page couldn't be loaded."
    }


    /** Removes YouTube's ad fields from a player response (or a {playerResponse:…} wrapper). Returns the same object. */
    const val YT_PRUNE_FN = """function __cdmPrune(o){try{if(!o||typeof o!=='object')return o;var ks=['adPlacements','playerAds','adSlots'];
var t=[o];if(o.playerResponse&&typeof o.playerResponse==='object')t.push(o.playerResponse);
if(Array.isArray(o))for(var i=0;i<o.length;i++){if(o[i]&&o[i].playerResponse)t.push(o[i].playerResponse);}
for(var j=0;j<t.length;j++){for(var k=0;k<ks.length;k++){if(Object.prototype.hasOwnProperty.call(t[j],ks[k]))delete t[j][ks[k]];}}}catch(e){}return o;}"""

    /**
     * Injected at document start on youtube.com (what open-source blockers do): strips ad fields from
     * ytInitialPlayerResponse and from every JSON the page parses (fetch / XHR /youtubei/v1/player).
     */
    const val YT_DOCSTART_JS = "(function(){if(window.__cdmYtDS)return;window.__cdmYtDS=1;" + YT_PRUNE_FN + """
var P=JSON.parse;JSON.parse=function(){var r=P.apply(this,arguments);if(r&&typeof r==='object'&&(r.adPlacements||r.playerAds||r.adSlots||(r.playerResponse&&(r.playerResponse.adPlacements||r.playerResponse.playerAds||r.playerResponse.adSlots))))__cdmPrune(r);return r;};
try{var RJ=Response.prototype.json;Response.prototype.json=function(){return RJ.apply(this,arguments).then(function(r){return __cdmPrune(r);});};}catch(e){}
try{var _pr;Object.defineProperty(window,'ytInitialPlayerResponse',{configurable:true,get:function(){return _pr;},set:function(v){_pr=__cdmPrune(v);}});}catch(e){}
})();"""

    fun isYouTube(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return h == "youtube.com" || h.endsWith(".youtube.com")
    }

    /** Runs once per YouTube page: hides ad slots and skips / fast-forwards ad videos. Normal videos untouched. */
    const val YT_ADSKIP_JS = """(function(){if(window.__cdmYt)return;window.__cdmYt=1;
var css='ytd-ad-slot-renderer,ytd-in-feed-ad-layout-renderer,ytd-promoted-sparkles-web-renderer,ytd-display-ad-renderer,ytd-companion-slot-renderer,ytd-action-companion-ad-renderer,ytd-banner-promo-renderer,ytd-statement-banner-renderer,ytd-player-legacy-desktop-watch-ads-renderer,#player-ads,#masthead-ad,.ytp-ad-overlay-container,.ytp-ad-module .ytp-ad-overlay-slot,ad-slot-renderer,ytm-promoted-sparkles-web-renderer,ytm-companion-ad-renderer,ytm-promoted-video-renderer,.ytm-promoted-sparkles-web-renderer,.masthead-ad{display:none!important}';
var st=document.createElement('style');st.textContent=css;(document.head||document.documentElement).appendChild(st);
function tick(){try{
var b=document.querySelector('.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button,.ytm-skip-ad-button,button[class*="skip-ad"],.ytp-ad-skip-button-container button');if(b)b.click();
var p=document.querySelector('.html5-video-player');if(p&&(p.classList.contains('ad-showing')||p.classList.contains('ad-interrupting'))){var v=p.querySelector('video');if(v){v.muted=true;if(isFinite(v.duration)&&v.duration>0)v.currentTime=v.duration;try{v.playbackRate=16}catch(e){}}}
else{var v2=document.querySelector('.html5-video-player video');if(v2&&v2.playbackRate===16){v2.playbackRate=1;v2.muted=false}}
var o=document.querySelector('.ytp-ad-overlay-close-button');if(o)o.click();
}catch(e){}}
setInterval(tick,250);})();"""
}
