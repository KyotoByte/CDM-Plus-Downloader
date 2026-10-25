package io.github.kyotobyte.cdmplus.vpn

import java.io.ByteArrayOutputStream

/** Minimal DNS wire-format helpers (RFC 1035). Pure Kotlin, no Android imports. */
object DnsMessage {
    const val TYPE_A = 1
    const val TYPE_AAAA = 28

    data class Question(val name: String, val type: Int, val end: Int)

    /** Reads the first question of a DNS message. Returns null for anything malformed. */
    fun question(msg: ByteArray, off: Int = 0, len: Int = msg.size - off): Question? {
        if (len < 17) return null
        val qd = u16(msg, off + 4)
        if (qd < 1) return null
        var p = off + 12
        val end = off + len
        val sb = StringBuilder()
        var guard = 0
        while (true) {
            if (p >= end || guard++ > 64) return null
            val l = msg[p].toInt() and 0xff
            if (l == 0) { p++; break }
            if (l and 0xC0 != 0) return null // no compression in questions
            if (p + 1 + l > end) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (i in 0 until l) sb.append((msg[p + 1 + i].toInt() and 0xff).toChar().lowercaseChar())
            p += 1 + l
        }
        if (p + 4 > end) return null
        return Question(sb.toString(), u16(msg, p), p + 4 - off)
    }

    /** Builds a standard recursive query (used for the downloader's own encrypted lookups and in tests). */
    fun query(name: String, type: Int, id: Int = 0): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(id shr 8 and 0xff); o.write(id and 0xff)
        o.write(0x01); o.write(0x00) // RD
        o.write(0); o.write(1); repeat(6) { o.write(0) }
        for (label in name.trimEnd('.').split('.')) {
            val b = label.toByteArray(Charsets.US_ASCII)
            o.write(b.size); o.write(b)
        }
        o.write(0)
        o.write(type shr 8); o.write(type and 0xff); o.write(0); o.write(1)
        return o.toByteArray()
    }

    /**
     * Answer for a blocked name: same id and question, NOERROR with 0.0.0.0 (A) or :: (AAAA),
     * or an empty NOERROR answer for other record types. Apps fail fast instead of waiting.
     */
    fun blockedResponse(query: ByteArray, q: Question): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(query, 0, 2) // id
        val rd = query[2].toInt() and 0x01
        o.write(0x80 or rd); o.write(0x80) // QR, RD copied, RA
        val answer = q.type == TYPE_A || q.type == TYPE_AAAA
        o.write(0); o.write(1)
        o.write(0); o.write(if (answer) 1 else 0)
        repeat(4) { o.write(0) }
        o.write(query, 12, q.end - 12) // question section as received
        if (answer) {
            o.write(0xC0); o.write(0x0C) // pointer to the question name
            o.write(0); o.write(q.type); o.write(0); o.write(1)
            o.write(0); o.write(0); o.write(0); o.write(60) // TTL 60 s
            val n = if (q.type == TYPE_A) 4 else 16
            o.write(0); o.write(n); repeat(n) { o.write(0) }
        }
        return o.toByteArray()
    }

    /** SERVFAIL for the same question: used when the encrypted upstream can't be reached. */
    fun servFail(query: ByteArray, q: Question): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(query, 0, 2)
        o.write(0x80 or (query[2].toInt() and 0x01)); o.write(0x82)
        o.write(0); o.write(1); repeat(6) { o.write(0) }
        o.write(query, 12, q.end - 12)
        return o.toByteArray()
    }

    data class Record(val type: Int, val ttl: Long, val data: ByteArray)

    /** Parses the answer section (A/AAAA data kept raw). */
    fun answers(msg: ByteArray): List<Record> {
        if (msg.size < 12) return emptyList()
        val qd = u16(msg, 4); val an = u16(msg, 6)
        var p = 12
        repeat(qd) { p = skipName(msg, p) + 4 }
        val out = ArrayList<Record>()
        repeat(an) {
            p = skipName(msg, p)
            if (p + 10 > msg.size) return out
            val type = u16(msg, p); val ttl = ((u16(msg, p + 4).toLong() shl 16) or u16(msg, p + 6).toLong())
            val rdl = u16(msg, p + 8)
            p += 10
            if (p + rdl > msg.size) return out
            out += Record(type, ttl, msg.copyOfRange(p, p + rdl))
            p += rdl
        }
        return out
    }

    fun rcode(msg: ByteArray): Int = if (msg.size > 3) msg[3].toInt() and 0x0f else -1

    private fun skipName(msg: ByteArray, start: Int): Int {
        var p = start
        while (p < msg.size) {
            val l = msg[p].toInt() and 0xff
            if (l == 0) return p + 1
            if (l and 0xC0 == 0xC0) return p + 2
            p += 1 + l
        }
        return p
    }

    fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)
}
