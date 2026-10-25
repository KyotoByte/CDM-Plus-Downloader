package io.github.kyotobyte.cdmplus.ui

/**
 * Batch links from a pattern: "https://site.com/img[1-100].jpg" or "[001-120]" (zero padded)
 * or letters "[a-z]". Several ranges multiply. Capped at 2000 links.
 */
fun expandPattern(url: String, cap: Int = 2000): List<String> {
    val m = Regex("""\[(\d+)-(\d+)]|\[([a-z])-([a-z])]""").find(url) ?: return listOf(url)
    val head = url.substring(0, m.range.first)
    val tail = url.substring(m.range.last + 1)
    val values: List<String> = if (m.groupValues[1].isNotEmpty()) {
        val a = m.groupValues[1]; val b = m.groupValues[2]
        val from = a.toLong(); val to = b.toLong()
        val width = if (a.startsWith("0") && a.length > 1) a.length else 0
        val range = if (from <= to) from..to else (to..from)
        range.take(cap).map { if (width > 0) it.toString().padStart(width, '0') else it.toString() }
    } else {
        val a = m.groupValues[3][0]; val b = m.groupValues[4][0]
        (minOf(a, b)..maxOf(a, b)).map { it.toString() }
    }
    val out = ArrayList<String>()
    for (v in values) {
        for (rest in expandPattern(tail, cap)) {
            out += head + v + rest
            if (out.size >= cap) return out
        }
    }
    return out
}
