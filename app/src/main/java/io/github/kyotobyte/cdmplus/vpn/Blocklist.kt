package io.github.kyotobyte.cdmplus.vpn

/** A block-list source the user can switch on. Lists are downloaded in-app, never bundled. */
enum class BlockSource(val id: String, val title: String, val url: String, val license: String) {
    STEVENBLACK("stevenblack", "StevenBlack hosts", "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts", "MIT"),
    ADGUARD("adguard", "AdGuard DNS filter", "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt", "GPL-3.0 (downloaded data)"),
    OISD("oisd", "OISD small", "https://small.oisd.nl/", "OISD terms (downloaded data)"),
    EASYLIST("easylist", "EasyList (domains)", "https://easylist.to/easylist/easylist.txt", "GPL-3.0 / CC BY-SA (downloaded data)"),
}

/** Turns hosts files, adblock-style lists (||domain^) and plain domain lists into clean domains. */
object ListParser {
    private val domainRe = Regex("^[a-z0-9_]([a-z0-9_-]{0,62}[a-z0-9_])?(\\.[a-z0-9_]([a-z0-9-_]{0,62}[a-z0-9_])?)+$")
    private val skip = setOf("localhost", "localhost.localdomain", "local", "broadcasthost", "ip6-localhost", "ip6-loopback", "0.0.0.0")

    fun parse(text: CharSequence): List<String> {
        val out = ArrayList<String>(64_000)
        text.lineSequence().forEach { raw ->
            val d = domainOf(raw.trim()) ?: return@forEach
            out += d
        }
        return out
    }

    fun domainOf(line: String): String? {
        if (line.isEmpty() || line[0] == '#' || line[0] == '!' || line[0] == '[' || line.startsWith("@@")) return null
        if (!line.startsWith("||")) {
            val hash = line.indexOf('#')
            if (hash > 0 && !line[hash - 1].isWhitespace()) return null
        }
        val cand: String = when {
            line.startsWith("||") -> {
                // adblock network rule: only pure "||domain^" (optionally $important) is a whole-domain block
                val caret = line.indexOf('^')
                if (caret < 0) return null
                val rest = line.substring(caret + 1)
                if (rest.isNotEmpty() && rest != "\$important" && rest != "|") return null
                line.substring(2, caret)
            }
            line[0].isDigit() || line[0] == ':' -> {
                // hosts format: "0.0.0.0 domain # comment"
                val parts = line.substringBefore('#').trim().split(' ', '\t').filter { it.isNotEmpty() }
                if (parts.size < 2) {
                    if (parts.size == 1 && !parts[0][0].isDigit()) parts[0] else return null
                } else parts[1]
            }
            else -> {
                // plain domain list; a '#' glued to the name is a cosmetic rule (site.com##.ad), not a comment
                val hash = line.indexOf('#')
                if (hash > 0 && !line[hash - 1].isWhitespace()) return null
                if ('$' in line || '|' in line || '^' in line) return null
                line.substringBefore('#').trim().split(' ', '\t').first()
            }
        }
        val d = cand.lowercase().trimEnd('.')
        if (d in skip || d.length > 253 || '*' in d || '/' in d) return null
        if (!domainRe.matches(d)) return null
        if (d.all { it.isDigit() || it == '.' }) return null
        return d
    }
}

/**
 * Compact domain set: 64-bit hashes in a sorted LongArray (~8 bytes per domain, so 300k
 * domains is ~2.4 MB). A name is blocked if it, or any parent domain, is in the set.
 */
class Blocklist private constructor(private val hashes: LongArray) {
    val size get() = hashes.size

    fun matches(name: String): Boolean {
        if (hashes.isEmpty()) return false
        var h = name.lowercase().trimEnd('.')
        while (true) {
            if (hashes.binarySearch(hash(h)) >= 0) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
            if (h.indexOf('.') < 0) return false // never block a bare TLD
        }
    }

    companion object {
        val EMPTY = Blocklist(LongArray(0))

        fun of(domains: Iterable<String>): Blocklist {
            var arr = LongArray(1024); var n = 0
            for (d in domains) {
                if (n == arr.size) arr = arr.copyOf(n * 2)
                arr[n++] = hash(d)
            }
            val sorted = arr.copyOf(n).also { it.sort() }
            var w = 0
            for (i in sorted.indices) if (i == 0 || sorted[i] != sorted[i - 1]) sorted[w++] = sorted[i]
            return Blocklist(sorted.copyOf(w))
        }

        /** FNV-1a 64. */
        fun hash(s: String): Long {
            var h = -0x340d631b7bdddcdbL
            for (c in s) { h = h xor c.code.toLong(); h *= 0x100000001b3L }
            return h
        }
    }
}

/** What the filter decides for one name: user allow list first, then user deny list, then the lists. */
class DnsFilter(val lists: Blocklist, private val allow: Set<String>, private val deny: Set<String>) {
    fun isBlocked(name: String): Boolean {
        val n = name.lowercase().trimEnd('.')
        if (suffixIn(n, allow)) return false
        if (suffixIn(n, deny)) return true
        return lists.matches(n)
    }

    private fun suffixIn(n: String, set: Set<String>): Boolean {
        if (set.isEmpty()) return false
        var h = n
        while (true) {
            if (h in set) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
        }
    }

    companion object {
        val NONE = DnsFilter(Blocklist.EMPTY, emptySet(), emptySet())
        fun userSet(text: String): Set<String> = text.lineSequence().mapNotNull { ListParser.domainOf(it.trim()) }.toHashSet()
    }
}
