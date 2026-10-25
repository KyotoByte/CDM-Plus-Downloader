package io.github.kyotobyte.cdmplus.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchTest {
    @Test fun addressBar() {
        val (bing, _) = SearchEngine.resolve("bing", "")
        assertEquals("https://www.bing.com/search?q=pixel+art", toUrl("pixel art", bing))
        assertEquals("https://example.com", toUrl("example.com", bing))
        assertEquals("https://x.org/a?b=1", toUrl("https://x.org/a?b=1", bing))
        assertEquals("https://www.google.com/search?q=cdm%2B", toUrl("cdm+", SearchEngine.resolve(null, "").first))
        val (t, home) = SearchEngine.resolve("custom", "https://search.example.net/find?query=%s&lang=en")
        assertEquals("https://search.example.net/find?query=hello&lang=en", toUrl("hello", t))
        assertEquals("https://search.example.net", home)
        // custom without %s falls back to Google
        assertEquals(SearchEngine.GOOGLE.template, SearchEngine.resolve("custom", "https://bad.example").first)
        assertEquals("https://yandex.com/search/?text=a+b", toUrl("a b", SearchEngine.YANDEX.template))
    }
}
