package io.github.kyotobyte.cdmplus.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    val threads: Int = 8,
    val maxSimultaneous: Int = 3,
    val wifiOnly: Boolean = false,
    val accent: Int = 0,
    val speedLimitKb: Int = 0,
    val autoResume: Boolean = true,
    val clipboardDetect: Boolean = true,
    val incognito: Boolean = false,
    val notifyOnComplete: Boolean = true,
    val userAgent: String = "",
    val proxyHost: String = "",
    val proxyPort: Int = 0,
    val adBlock: Boolean = true,
    val saveTreeUri: String = "",
    val retryCount: Int = 5,
    val retryDelaySec: Int = 5,
    val domainParts: String = "",
    val batteryStop: Int = 0,
    val proxySocks: Boolean = false,
    val amoled: Boolean = false,
    val floating: Boolean = false,
    val searchEngine: String = "google",
    val customSearch: String = "",
    /** Daily scheduler: hour to resume all / pause all (-1 = off). */
    val dailyStartHour: Int = -1,
    val dailyStopHour: Int = -1,
    /** "dark", "light", "amoled" (amoled kept in its own flag for old settings). */
    val themeMode: String = "dark",
    /** Custom accent colour as 0xRRGGBB (-1 = use one of the preset accents). */
    val customAccent: Int = -1,
    val seedEnabled: Boolean = false,
    val seedRatio: Float = 1.0f,
    val seedMinutes: Int = 0,
    val uploadLimitKb: Int = 0,
    val savePasswords: Boolean = true,
    val crawlDepth: Int = 1,
    val crawlSameSite: Boolean = true,
) {
    fun domainPartMap(): Map<String, Int> = domainParts.lineSequence().mapNotNull { l ->
        val (d, n) = l.split('=', ' ', ':').map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.size >= 2 } ?: return@mapNotNull null
        n.toIntOrNull()?.let { d.lowercase().removePrefix("*.") to it.coerceIn(1, 32) }
    }.toMap()
}

class SettingsRepo(context: Context) {
    private val prefs = context.getSharedPreferences("cdm_settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppSettings> = _state.asStateFlow()
    val value: AppSettings get() = _state.value

    private fun read() = AppSettings(
        threads = prefs.getInt("threads", 8),
        maxSimultaneous = prefs.getInt("max_sim", 3),
        wifiOnly = prefs.getBoolean("wifi_only", false),
        accent = prefs.getInt("accent", 0),
        speedLimitKb = prefs.getInt("speed_limit_kb", 0),
        autoResume = prefs.getBoolean("auto_resume", true),
        clipboardDetect = prefs.getBoolean("clipboard", true),
        incognito = prefs.getBoolean("incognito", false),
        notifyOnComplete = prefs.getBoolean("notify_done", true),
        userAgent = prefs.getString("ua", "") ?: "",
        proxyHost = prefs.getString("proxy_host", "") ?: "",
        proxyPort = prefs.getInt("proxy_port", 0),
        adBlock = prefs.getBoolean("adblock", true),
        saveTreeUri = prefs.getString("tree", "") ?: "",
        retryCount = prefs.getInt("retry_n", 5),
        retryDelaySec = prefs.getInt("retry_s", 5),
        domainParts = prefs.getString("dom_parts", "") ?: "",
        batteryStop = prefs.getInt("bat_stop", 0),
        proxySocks = prefs.getBoolean("socks", false),
        amoled = prefs.getBoolean("amoled", false),
        floating = prefs.getBoolean("floating", false),
        searchEngine = prefs.getString("search", "google") ?: "google",
        customSearch = prefs.getString("search_custom", "") ?: "",
        dailyStartHour = prefs.getInt("daily_start", -1),
        dailyStopHour = prefs.getInt("daily_stop", -1),
        themeMode = prefs.getString("theme_mode", "dark") ?: "dark",
        customAccent = prefs.getInt("custom_accent", -1),
        seedEnabled = prefs.getBoolean("seed", false),
        seedRatio = prefs.getFloat("seed_ratio", 1.0f),
        seedMinutes = prefs.getInt("seed_min", 0),
        uploadLimitKb = prefs.getInt("up_limit_kb", 0),
        savePasswords = prefs.getBoolean("save_pw", true),
        crawlDepth = prefs.getInt("crawl_depth", 1),
        crawlSameSite = prefs.getBoolean("crawl_same", true),
    )

    fun update(f: (AppSettings) -> AppSettings) {
        val s = f(_state.value)
        prefs.edit()
            .putInt("threads", s.threads.coerceIn(1, 32))
            .putInt("max_sim", s.maxSimultaneous.coerceIn(1, 30))
            .putBoolean("wifi_only", s.wifiOnly)
            .putInt("accent", s.accent)
            .putInt("speed_limit_kb", s.speedLimitKb.coerceAtLeast(0))
            .putBoolean("auto_resume", s.autoResume)
            .putBoolean("clipboard", s.clipboardDetect)
            .putBoolean("incognito", s.incognito)
            .putBoolean("notify_done", s.notifyOnComplete)
            .putString("ua", s.userAgent)
            .putString("proxy_host", s.proxyHost)
            .putInt("proxy_port", s.proxyPort)
            .putBoolean("adblock", s.adBlock)
            .putString("tree", s.saveTreeUri)
            .putInt("retry_n", s.retryCount.coerceIn(0, 50))
            .putInt("retry_s", s.retryDelaySec.coerceIn(1, 300))
            .putString("dom_parts", s.domainParts)
            .putInt("bat_stop", s.batteryStop.coerceIn(0, 50))
            .putBoolean("socks", s.proxySocks)
            .putBoolean("amoled", s.amoled)
            .remove("hi_refresh") // old build-7 toggle: high refresh is native now
            .putBoolean("floating", s.floating)
            .putString("search", s.searchEngine)
            .putString("search_custom", s.customSearch)
            .putInt("daily_start", s.dailyStartHour.coerceIn(-1, 23))
            .putInt("daily_stop", s.dailyStopHour.coerceIn(-1, 23))
            .putString("theme_mode", s.themeMode)
            .putInt("custom_accent", s.customAccent)
            .putBoolean("seed", s.seedEnabled)
            .putFloat("seed_ratio", s.seedRatio.coerceIn(0f, 100f))
            .putInt("seed_min", s.seedMinutes.coerceIn(0, 100000))
            .putInt("up_limit_kb", s.uploadLimitKb.coerceAtLeast(0))
            .putBoolean("save_pw", s.savePasswords)
            .putInt("crawl_depth", s.crawlDepth.coerceIn(1, 3))
            .putBoolean("crawl_same", s.crawlSameSite)
            .apply()
        _state.value = s
    }

    /** Settings backup as JSON (every preference, typed). */
    fun exportJson(): String {
        val o = org.json.JSONObject()
        prefs.all.forEach { (k, v) -> if (k != "last_clip" && k != "tree") o.put(k, org.json.JSONArray().put(v?.javaClass?.simpleName ?: "Null").put(v)) }
        return org.json.JSONObject().put("app", "CDM+").put("settings", o).toString(2)
    }

    fun importJson(text: String): Int {
        val o = org.json.JSONObject(text).getJSONObject("settings")
        val e = prefs.edit(); var n = 0
        o.keys().forEach { k ->
            val a = o.getJSONArray(k)
            when (a.getString(0)) {
                "Integer" -> e.putInt(k, a.getInt(1)); "Long" -> e.putLong(k, a.getLong(1)); "Boolean" -> e.putBoolean(k, a.getBoolean(1))
                "Float" -> e.putFloat(k, a.getDouble(1).toFloat()); "String" -> e.putString(k, a.getString(1)); else -> return@forEach
            }
            n++
        }
        e.commit()
        _state.value = read()
        return n
    }

    fun lastClipboard(): String? = prefs.getString("last_clip", null)
    fun setLastClipboard(v: String) = prefs.edit().putString("last_clip", v).apply()
}
