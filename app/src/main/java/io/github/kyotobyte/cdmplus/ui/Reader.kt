package io.github.kyotobyte.cdmplus.ui

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.safety.Safelist

/** Reading mode: finds the main article in a page and returns clean, styled HTML. */
object Reader {
    data class Article(val title: String, val html: String, val words: Int)

    fun extract(pageUrl: String, html: String): Article? {
        val doc = Jsoup.parse(html, pageUrl)
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() } ?: doc.title()
        doc.select("script,style,noscript,nav,header,footer,aside,form,iframe,button,svg,[role=navigation],[aria-hidden=true],.ad,.ads,.advert,.share,.social,.comments,#comments,.related,.newsletter,.cookie").remove()
        // Score each block by the text of its paragraphs; the best one is the article.
        val scores = HashMap<Element, Double>()
        for (p in doc.select("p, pre, blockquote")) {
            val text = p.text()
            if (text.length < 25) continue
            val s = 1.0 + text.count { it == ',' } + minOf(text.length / 100.0, 3.0)
            p.parent()?.let { scores[it] = (scores[it] ?: 0.0) + s }
            p.parent()?.parent()?.let { scores[it] = (scores[it] ?: 0.0) + s / 2 }
        }
        val best = scores.maxByOrNull { (e, s) -> s * (1 - linkDensity(e)) }?.key
            ?: doc.selectFirst("article") ?: doc.selectFirst("main") ?: return null
        val clean = Jsoup.clean(best.html(), pageUrl, Safelist.relaxed().preserveRelativeLinks(false).removeTags("span", "div").addTags("figure", "figcaption"))
        val words = Jsoup.parse(clean).text().split(Regex("\\s+")).count { it.isNotBlank() }
        if (words < 60) return null
        return Article(title, clean, words)
    }

    private fun linkDensity(e: Element): Double {
        val all = e.text().length.coerceAtLeast(1)
        val links = e.select("a").sumOf { it.text().length }
        return links.toDouble() / all
    }

    fun page(a: Article, dark: Boolean, sizePx: Int): String {
        val bg = if (dark) "#0C0D10" else "#F6F1E7"; val fg = if (dark) "#E8E8E8" else "#1E1E1E"; val link = if (dark) "#39FF88" else "#00704A"
        return """<!doctype html><html><head><meta name=viewport content="width=device-width,initial-scale=1"><style>
body{background:$bg;color:$fg;font:${sizePx}px/1.65 Georgia,serif;margin:0;padding:20px 18px 60px;max-width:720px}
h1{font:bold 1.35em/1.3 sans-serif;margin:0 0 6px}.m{opacity:.6;font:13px sans-serif;margin-bottom:22px}
img,video{max-width:100%;height:auto}a{color:$link}pre{white-space:pre-wrap}blockquote{border-left:3px solid $link;margin:0;padding-left:12px;opacity:.85}
</style></head><body><h1>${Jsoup.clean(a.title, Safelist.none())}</h1><div class=m>${a.words} words · ~${(a.words / 220).coerceAtLeast(1)} min read</div>${a.html}</body></html>"""
    }
}
