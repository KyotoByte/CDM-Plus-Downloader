package io.github.kyotobyte.cdmplus.engine

import java.io.InputStream
import java.security.MessageDigest

/** MD5 / SHA-1 / SHA-256 of a finished file, in one pass. */
object Checksums {
    data class Result(val md5: String, val sha1: String, val sha256: String) {
        /** True if [expected] (any of the three, any case, spaces ignored) matches. */
        fun matches(expected: String): String? {
            val e = expected.trim().lowercase().replace(" ", "")
            return when (e) { md5 -> "MD5"; sha1 -> "SHA-1"; sha256 -> "SHA-256"; else -> null }
        }
    }

    fun of(input: InputStream): Result {
        val m = MessageDigest.getInstance("MD5"); val s1 = MessageDigest.getInstance("SHA-1"); val s2 = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(256 * 1024)
        input.use { while (true) { val n = it.read(buf); if (n < 0) break; m.update(buf, 0, n); s1.update(buf, 0, n); s2.update(buf, 0, n) } }
        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        return Result(hex(m.digest()), hex(s1.digest()), hex(s2.digest()))
    }
}
