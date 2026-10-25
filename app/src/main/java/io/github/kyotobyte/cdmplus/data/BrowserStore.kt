package io.github.kyotobyte.cdmplus.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class WebEntry(val url: String, val title: String, val time: Long = System.currentTimeMillis())

/** Bookmarks + history for the built-in browser (history is skipped in incognito). */
class BrowserStore(context: Context) {
    private val prefs = context.getSharedPreferences("cdm_browser", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val _bookmarks = MutableStateFlow(load("bookmarks"))
    private val _history = MutableStateFlow(load("history"))
    val bookmarks: StateFlow<List<WebEntry>> = _bookmarks.asStateFlow()
    val history: StateFlow<List<WebEntry>> = _history.asStateFlow()

    private fun load(key: String): List<WebEntry> = runCatching { json.decodeFromString<List<WebEntry>>(prefs.getString(key, "[]") ?: "[]") }.getOrElse { emptyList() }
    private fun save(key: String, v: List<WebEntry>) = prefs.edit().putString(key, json.encodeToString(v)).apply()

    fun isBookmarked(url: String) = _bookmarks.value.any { it.url == url }
    fun toggleBookmark(url: String, title: String) {
        val v = if (isBookmarked(url)) _bookmarks.value.filterNot { it.url == url } else listOf(WebEntry(url, title.ifBlank { url })) + _bookmarks.value
        _bookmarks.value = v; save("bookmarks", v)
    }
    fun addHistory(url: String, title: String) {
        if (url.startsWith("about:")) return
        val v = (listOf(WebEntry(url, title.ifBlank { url })) + _history.value.filterNot { it.url == url }).take(300)
        _history.value = v; save("history", v)
    }
    /** Open (non-incognito) tabs, restored after a restart. */
    fun saveTabs(tabs: List<WebEntry>, active: Int) { prefs.edit().putString("tabs", json.encodeToString(tabs.take(20))).putInt("tab_active", active).apply() }
    fun loadTabs(): Pair<List<WebEntry>, Int> = load("tabs") to prefs.getInt("tab_active", 0)

    fun clearHistory() { _history.value = emptyList(); save("history", emptyList()) }
}
