package io.github.kyotobyte.cdmplus.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Keeps incognito cookies/storage away from normal tabs.
 * - WebView with multi-profile support: incognito tabs use their own profile, wiped when the last one closes.
 * - Older WebView: incognito shares the store, so every host visited in incognito has its cookies and
 *   site storage removed when the last incognito tab closes.
 */
object IncognitoJar {
    const val PROFILE = "cdm_incognito"
    private val hosts = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    val separate: Boolean by lazy { runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }.getOrDefault(false) }

    /** Call right after constructing an incognito WebView, before it loads anything. */
    @SuppressLint("RequiresFeature")
    fun attach(wv: WebView): Boolean {
        if (!separate) return false
        return runCatching { ProfileStore.getInstance().getOrCreateProfile(PROFILE); WebViewCompat.setProfile(wv, PROFILE); true }.getOrDefault(false)
    }

    @SuppressLint("RequiresFeature")
    fun cookies(incognito: Boolean): CookieManager =
        if (incognito && separate) runCatching { ProfileStore.getInstance().getOrCreateProfile(PROFILE).cookieManager }.getOrNull() ?: CookieManager.getInstance()
        else CookieManager.getInstance()

    fun note(url: String) { Uri.parse(url).host?.lowercase()?.let { hosts.add(it) } }

    /** Expired-cookie lines for every cookie name in a Cookie header (pure, unit-tested). */
    fun expiryLines(cookieHeader: String?, host: String): List<String> {
        if (cookieHeader.isNullOrBlank()) return emptyList()
        val names = cookieHeader.split(';').mapNotNull { it.substringBefore('=', "").trim().takeIf { n -> n.isNotEmpty() } }.distinct()
        val parent = host.split('.').takeLast(2).joinToString(".")
        return names.flatMap { n -> listOf("$n=; Max-Age=0; Path=/", "$n=; Max-Age=0; Path=/; Domain=$host", "$n=; Max-Age=0; Path=/; Domain=.$parent") }
    }

    /** The last incognito tab closed: forget everything it stored. */
    @SuppressLint("RequiresFeature")
    fun wipe() {
        if (separate) runCatching {
            val p = ProfileStore.getInstance().getProfile(PROFILE)
            p?.cookieManager?.removeAllCookies(null)
            p?.webStorage?.deleteAllData()
            runCatching { ProfileStore.getInstance().deleteProfile(PROFILE) } // fails if a WebView still holds it: data is already gone
        }
        val cm = CookieManager.getInstance()
        val list = synchronized(hosts) { hosts.toList().also { hosts.clear() } }
        if (!separate) for (h in list) runCatching {
            for (scheme in listOf("https://", "http://")) {
                val u = scheme + h
                expiryLines(cm.getCookie(u), h).forEach { cm.setCookie(u, it) }
                WebStorage.getInstance().deleteOrigin(u)
            }
        }
        runCatching { cm.removeSessionCookies(null); cm.flush() }
    }
}
