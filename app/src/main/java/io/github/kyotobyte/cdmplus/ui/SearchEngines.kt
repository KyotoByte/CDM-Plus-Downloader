package io.github.kyotobyte.cdmplus.ui

/** Address-bar search engines. Custom = any URL with %s where the words go. */
enum class SearchEngine(val id: String, val title: String, val template: String, val home: String) {
    GOOGLE("google", "Google", "https://www.google.com/search?q=%s", "https://www.google.com"),
    DUCKDUCKGO("ddg", "DuckDuckGo", "https://duckduckgo.com/?q=%s", "https://duckduckgo.com"),
    BING("bing", "Bing", "https://www.bing.com/search?q=%s", "https://www.bing.com"),
    BRAVE("brave", "Brave", "https://search.brave.com/search?q=%s", "https://search.brave.com"),
    STARTPAGE("startpage", "Startpage", "https://www.startpage.com/do/search?q=%s", "https://www.startpage.com"),
    YANDEX("yandex", "Yandex", "https://yandex.com/search/?text=%s", "https://yandex.com"),
    CUSTOM("custom", "Custom", "", "");

    companion object {
        fun byId(id: String?) = entries.firstOrNull { it.id == id } ?: GOOGLE

        /** Template + home page for the chosen engine (custom falls back to Google if it has no %s). */
        fun resolve(id: String?, custom: String): Pair<String, String> {
            val e = byId(id)
            if (e != CUSTOM) return e.template to e.home
            val t = custom.trim()
            if (!t.contains("%s") || !(t.startsWith("http://") || t.startsWith("https://"))) return GOOGLE.template to GOOGLE.home
            val home = t.substringBefore("://") + "://" + t.substringAfter("://").substringBefore('/')
            return t to home
        }
    }
}
