package io.github.kyotobyte.cdmplus.engine

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * A small MPEG-TS -> MP4 remuxer written for CDM+ (no ffmpeg). It copies H.264 video and
 * AAC (ADTS) audio packets into a normal, seekable .mp4 without re-encoding.
 * With [audioOnly] it writes just the AAC track (an .m4a), used by "Extract music".
 * Anything else (HEVC, AC-3, MP3 audio...) throws [UnsupportedStreamException]; callers then keep the .ts.
 */
class UnsupportedStreamException(msg: String) : IOException(msg)

object TsToMp4 {

    data class Result(val videoSamples: Int, val audioSamples: Int, val width: Int, val height: Int, val durationMs: Long)

    fun convert(input: InputStream, out: File, audioOnly: Boolean = false): Result {
        val demux = TsDemuxer(audioOnly)
        Mp4Writer(out).use { mp4 ->
            demux.sink = mp4
            val pkt = ByteArray(188)
            val bin = input.buffered(1 shl 20)
            var synced = false
            while (true) {
                if (!synced) {
                    // find sync byte
                    var b: Int
                    do { b = bin.read() } while (b >= 0 && b != 0x47)
                    if (b < 0) break
                    pkt[0] = 0x47
                    if (!readFully(bin, pkt, 1, 187)) break
                    synced = true
                } else if (!readFully(bin, pkt, 0, 188)) break
                if (pkt[0] != 0x47.toByte()) { synced = false; continue }
                demux.packet(pkt)
            }
            demux.flush()
            if (mp4.video == null && mp4.audio == null) throw UnsupportedStreamException(demux.unsupported ?: "No H.264/AAC streams found")
            if (!audioOnly && mp4.video == null && demux.unsupported != null) throw UnsupportedStreamException(demux.unsupported!!)
            mp4.finish()
            return Result(mp4.video?.count ?: 0, mp4.audio?.count ?: 0, mp4.video?.width ?: 0, mp4.video?.height ?: 0, mp4.durationMs())
        }
    }

    private fun readFully(s: InputStream, b: ByteArray, off: Int, len: Int): Boolean {
        var o = off; var left = len
        while (left > 0) { val n = s.read(b, o, left); if (n < 0) return false; o += n; left -= n }
        return true
    }
}

/** Reads 188-byte TS packets, rebuilds PES packets and hands out H.264 access units and AAC frames. */
internal class TsDemuxer(private val audioOnly: Boolean) {
    lateinit var sink: Mp4Writer
    private var pmtPid = -1
    private var videoPid = -1
    private var audioPid = -1
    var unsupported: String? = null
    private val pes = HashMap<Int, ByteArrayOutputStream>()
    private val adtsLeft = ByteArrayOutputStream()
    private var lastAudioPts = -1L
    private var audioFramesSincePts = 0

    fun packet(p: ByteArray) {
        val pusi = p[1].toInt() and 0x40 != 0
        val pid = ((p[1].toInt() and 0x1F) shl 8) or (p[2].toInt() and 0xFF)
        val afc = (p[3].toInt() shr 4) and 3
        var off = 4
        if (afc and 2 != 0) off += 1 + (p[4].toInt() and 0xFF)
        if (afc and 1 == 0 || off >= 188) return
        when {
            pid == 0 -> if (pusi) parsePat(p, off)
            pid == pmtPid -> if (pusi) parsePmt(p, off)
            pid == videoPid || pid == audioPid -> {
                if (pusi) { pes[pid]?.let { finishPes(pid, it.toByteArray()) }; pes[pid] = ByteArrayOutputStream(64 * 1024) }
                pes[pid]?.write(p, off, 188 - off)
            }
        }
    }

    fun flush() {
        for ((pid, buf) in pes.toList()) finishPes(pid, buf.toByteArray())
        pes.clear()
    }

    private fun parsePat(p: ByteArray, start: Int) {
        var o = start + 1 + (p[start].toInt() and 0xFF)
        if (o + 8 > 188) return
        val len = ((p[o + 1].toInt() and 0x0F) shl 8) or (p[o + 2].toInt() and 0xFF)
        val end = minOf(o + 3 + len - 4, 188)
        o += 8
        while (o + 4 <= end) {
            val prog = ((p[o].toInt() and 0xFF) shl 8) or (p[o + 1].toInt() and 0xFF)
            val pid = ((p[o + 2].toInt() and 0x1F) shl 8) or (p[o + 3].toInt() and 0xFF)
            if (prog != 0) { pmtPid = pid; return }
            o += 4
        }
    }

    private fun parsePmt(p: ByteArray, start: Int) {
        var o = start + 1 + (p[start].toInt() and 0xFF)
        if (o + 12 > 188) return
        val len = ((p[o + 1].toInt() and 0x0F) shl 8) or (p[o + 2].toInt() and 0xFF)
        val end = minOf(o + 3 + len - 4, 188)
        val infoLen = ((p[o + 10].toInt() and 0x0F) shl 8) or (p[o + 11].toInt() and 0xFF)
        o += 12 + infoLen
        while (o + 5 <= end) {
            val type = p[o].toInt() and 0xFF
            val pid = ((p[o + 1].toInt() and 0x1F) shl 8) or (p[o + 2].toInt() and 0xFF)
            val esLen = ((p[o + 3].toInt() and 0x0F) shl 8) or (p[o + 4].toInt() and 0xFF)
            when (type) {
                0x1B -> if (videoPid < 0 && !audioOnly) videoPid = pid
                0x0F -> if (audioPid < 0) audioPid = pid
                0x24 -> if (!audioOnly) unsupported = "HEVC video (kept as .ts)"
                0x03, 0x04 -> if (audioPid < 0) unsupported = "MP3 audio (kept as .ts)"
                0x81, 0x87 -> if (audioPid < 0) unsupported = "AC-3 audio (kept as .ts)"
            }
            o += 5 + esLen
        }
    }

    private fun readTs(b: ByteArray, o: Int): Long =
        (((b[o].toLong() shr 1) and 7) shl 30) or ((b[o + 1].toLong() and 0xFF) shl 22) or (((b[o + 2].toLong() and 0xFF) shr 1) shl 15) or
            ((b[o + 3].toLong() and 0xFF) shl 7) or ((b[o + 4].toLong() and 0xFF) shr 1)

    private fun finishPes(pid: Int, d: ByteArray) {
        if (d.size < 9 || d[0].toInt() != 0 || d[1].toInt() != 0 || d[2].toInt() != 1) return
        val flags = d[7].toInt() and 0xFF
        val hlen = d[8].toInt() and 0xFF
        var pts = -1L; var dts = -1L
        if (flags and 0x80 != 0 && d.size >= 14) pts = readTs(d, 9)
        if (flags and 0x40 != 0 && d.size >= 19) dts = readTs(d, 14)
        if (dts < 0) dts = pts
        val payloadStart = 9 + hlen
        if (payloadStart >= d.size) return
        val payload = d.copyOfRange(payloadStart, d.size)
        if (pid == videoPid) video(payload, pts, dts) else audio(payload, pts)
    }

    private fun video(es: ByteArray, pts: Long, dts: Long) {
        val nals = splitAnnexB(es)
        val out = ByteArrayOutputStream(es.size + 16)
        var key = false
        for (n in nals) {
            if (n.isEmpty()) continue
            when (n[0].toInt() and 0x1F) {
                9 -> continue // access unit delimiter
                7 -> { sink.setSps(n); continue }
                8 -> { sink.setPps(n); continue }
                5 -> key = true
            }
            out.write(n.size ushr 24); out.write(n.size ushr 16); out.write(n.size ushr 8); out.write(n.size)
            out.write(n)
        }
        if (out.size() > 0) sink.videoSample(out.toByteArray(), pts, dts, key)
    }

    private fun audio(es: ByteArray, pts: Long) {
        if (pts >= 0) { lastAudioPts = pts; audioFramesSincePts = 0 }
        adtsLeft.write(es)
        val b = adtsLeft.toByteArray()
        var o = 0
        while (o + 7 <= b.size) {
            if (b[o].toInt() and 0xFF != 0xFF || b[o + 1].toInt() and 0xF0 != 0xF0) { o++; continue }
            val protAbsent = b[o + 1].toInt() and 1
            val profile = (b[o + 2].toInt() shr 6) and 3
            val sfi = (b[o + 2].toInt() shr 2) and 0xF
            val ch = ((b[o + 2].toInt() and 1) shl 2) or ((b[o + 3].toInt() shr 6) and 3)
            val flen = ((b[o + 3].toInt() and 3) shl 11) or ((b[o + 4].toInt() and 0xFF) shl 3) or ((b[o + 5].toInt() and 0xFF) shr 5)
            val hdr = if (protAbsent == 1) 7 else 9
            if (flen < hdr) { o++; continue }
            if (o + flen > b.size) break
            sink.setAudioConfig(profile + 1, sfi, ch)
            val framePts = if (lastAudioPts >= 0) lastAudioPts + audioFramesSincePts * 1024L * 90000L / Mp4Writer.SAMPLE_RATES.getOrElse(sfi) { 44100 } else -1
            sink.audioSample(b.copyOfRange(o + hdr, o + flen), framePts)
            audioFramesSincePts++
            o += flen
        }
        adtsLeft.reset()
        if (o < b.size) adtsLeft.write(b, o, b.size - o)
    }

    companion object {
        fun splitAnnexB(b: ByteArray): List<ByteArray> {
            val res = ArrayList<ByteArray>()
            var i = 0; var start = -1
            while (i + 2 < b.size) {
                if (b[i].toInt() == 0 && b[i + 1].toInt() == 0 && b[i + 2].toInt() == 1) {
                    if (start >= 0) {
                        var e = i
                        while (e > start && b[e - 1].toInt() == 0) e--
                        res.add(b.copyOfRange(start, e))
                    }
                    i += 3; start = i
                } else i++
            }
            if (start >= 0 && start < b.size) res.add(b.copyOfRange(start, b.size))
            return res
        }
    }
}

/** Streams samples into mdat, then writes the moov index at the end. */
internal class Mp4Writer(file: File) : AutoCloseable {
    companion object {
        val SAMPLE_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
        private const val MOVIE_TS = 1000L
    }

    inner class Track(val isVideo: Boolean, val timescale: Long) {
        val sizes = IntArrayList(); val offsets = LongArrayList(); val dts = LongArrayList(); val cto = IntArrayList(); val keys = IntArrayList()
        var width = 0; var height = 0
        val count get() = sizes.size
        var firstPts = -1L
        fun durationTs(): Long = if (count == 0) 0 else dts[count - 1] - dts[0] + lastDelta()
        fun lastDelta(): Long = if (count >= 2) dts[count - 1] - dts[count - 2] else if (isVideo) timescale / 30 else 1024
    }

    private val raf = RandomAccessFile(file, "rw").apply { setLength(0) }
    private val out = DataOutputStream(java.io.BufferedOutputStream(java.io.FileOutputStream(raf.fd), 1 shl 20))
    private var pos = 0L
    private val mdatStart: Long
    var video: Track? = null
    var audio: Track? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var aObj = 2; private var aSfi = 4; private var aCh = 2
    private var vPrevDts = Long.MIN_VALUE; private var vWrap = 0L
    private var aPrev = Long.MIN_VALUE; private var aWrap = 0L

    init {
        val ftyp = box("ftyp") { s("isom"); i(0x200); s("isom"); s("iso2"); s("avc1"); s("mp41") }
        out.write(ftyp); pos += ftyp.size
        mdatStart = pos
        out.writeInt(1); out.writeBytes("mdat"); out.writeLong(0) // 64-bit size, patched in finish()
        pos += 16
    }

    fun setSps(n: ByteArray) {
        if (sps == null) { sps = n; runCatching { H264Sps.size(n) }.getOrNull()?.let { (w, h) -> video?.width = w; video?.height = h; spsSize = w to h } }
    }
    private var spsSize: Pair<Int, Int>? = null
    fun setPps(n: ByteArray) { if (pps == null) pps = n }
    fun setAudioConfig(obj: Int, sfi: Int, ch: Int) { if (audio == null) { aObj = obj; aSfi = sfi; aCh = ch } }

    private fun unwrap(t: Long, prev: Long, wrap: Long): Pair<Long, Long> {
        var w = wrap
        var v = t + w
        if (prev != Long.MIN_VALUE) {
            if (v < prev - (1L shl 32)) { w += 1L shl 33; v = t + w }
        }
        return v to w
    }

    fun videoSample(data: ByteArray, pts0: Long, dts0: Long, key: Boolean) {
        val tr = video ?: Track(true, 90000).also { video = it; spsSize?.let { (w, h) -> it.width = w; it.height = h } }
        var dts = dts0; var pts = pts0
        if (dts < 0) dts = if (tr.count > 0) tr.dts[tr.count - 1] + tr.lastDelta() else 0
        else { val (v, nw) = unwrap(dts, vPrevDts, vWrap); vWrap = nw; dts = v }
        pts = if (pts < 0) dts else pts + vWrap
        if (tr.count > 0 && dts <= tr.dts[tr.count - 1]) dts = tr.dts[tr.count - 1] + 1
        vPrevDts = dts
        if (tr.firstPts < 0) tr.firstPts = pts
        write(tr, data, dts, (pts - dts).toInt().coerceAtLeast(0), key)
    }

    fun audioSample(data: ByteArray, pts90k: Long) {
        val sr = SAMPLE_RATES.getOrElse(aSfi) { 44100 }.toLong()
        val tr = audio ?: Track(false, sr).also { audio = it }
        if (tr.firstPts < 0 && pts90k >= 0) { val (v, nw) = unwrap(pts90k, aPrev, aWrap); aWrap = nw; tr.firstPts = v }
        val dts = if (tr.count == 0) 0 else tr.dts[tr.count - 1] + 1024
        write(tr, data, dts, 0, true)
    }

    private fun write(tr: Track, data: ByteArray, dts: Long, cto: Int, key: Boolean) {
        tr.offsets.add(pos); tr.sizes.add(data.size); tr.dts.add(dts); tr.cto.add(cto); tr.keys.add(if (key) 1 else 0)
        out.write(data); pos += data.size
    }

    fun durationMs(): Long = listOfNotNull(video, audio).maxOfOrNull { it.durationTs() * 1000 / it.timescale } ?: 0

    fun finish() {
        out.flush()
        val tracks = listOfNotNull(video, audio).filter { it.count > 0 }
        // Common start: the earliest first presentation time (90 kHz) across tracks.
        val base = tracks.mapNotNull { t -> if (t.firstPts < 0) null else t.firstPts }.minOrNull() ?: 0
        val moov = box("moov") {
            raw(box("mvhd") { i(0); i(0); i(0); i(MOVIE_TS.toInt()); i(durationMs().toInt()); i(0x00010000); sh(0x0100); zeros(10); matrix(); zeros(24); i(tracks.size + 1) })
            tracks.forEachIndexed { idx, t -> raw(trak(t, idx + 1, base)) }
        }
        out.write(moov); pos += moov.size
        out.flush()
        raf.seek(mdatStart + 8); raf.writeLong(pos - moov.size - mdatStart)
    }

    private fun trak(t: Track, id: Int, base: Long): ByteArray {
        val durMedia = t.durationTs()
        val durMovie = durMedia * MOVIE_TS / t.timescale
        val startDelay90k = if (t.firstPts >= 0) t.firstPts - base else 0
        val delayMovie = startDelay90k * MOVIE_TS / 90000
        val firstCto = if (t.count > 0) t.cto[0].toLong() else 0
        return box("trak") {
            raw(box("tkhd") { i(3); i(0); i(0); i(id); i(0); i(durMovie.toInt()); zeros(8); sh(0); sh(0); sh(if (t.isVideo) 0 else 0x0100); sh(0); matrix(); i(t.width shl 16); i(t.height shl 16) })
            raw(box("edts") {
                raw(box("elst") {
                    i(0)
                    if (delayMovie > 0) { i(2); i(delayMovie.toInt()); i(-1); i(0x00010000) } else i(1)
                    i(durMovie.toInt()); i(firstCto.toInt()); i(0x00010000)
                })
            })
            raw(box("mdia") {
                raw(box("mdhd") { i(0); i(0); i(0); i(t.timescale.toInt()); i(durMedia.toInt()); sh(0x55C4); sh(0) })
                raw(box("hdlr") { i(0); i(0); s(if (t.isVideo) "vide" else "soun"); zeros(12); s(if (t.isVideo) "CDM+ Video" else "CDM+ Audio"); b(0) })
                raw(box("minf") {
                    if (t.isVideo) raw(box("vmhd") { i(1); zeros(8) }) else raw(box("smhd") { i(0); i(0) })
                    raw(box("dinf") { raw(box("dref") { i(0); i(1); raw(box("url ") { i(1) }) }) })
                    raw(stbl(t))
                })
            })
        }
    }

    private fun stbl(t: Track): ByteArray = box("stbl") {
        raw(box("stsd") {
            i(0); i(1)
            if (t.isVideo) raw(box("avc1") {
                zeros(6); sh(1); zeros(16); sh(t.width); sh(t.height); i(0x00480000); i(0x00480000); i(0); sh(1); zeros(32); sh(0x18); sh(-1)
                val s = sps ?: byteArrayOf(0x67, 0x64, 0, 0x1F); val p = pps ?: byteArrayOf(0x68)
                raw(box("avcC") { b(1); b(s.getOrElse(1) { 0x64 }.toInt()); b(s.getOrElse(2) { 0 }.toInt()); b(s.getOrElse(3) { 0x1F }.toInt()); b(0xFF); b(0xE1); sh(s.size); raw(s); b(1); sh(p.size); raw(p) })
            }) else raw(box("mp4a") {
                zeros(6); sh(1); zeros(8); sh(aCh.coerceAtLeast(1)); sh(16); sh(0); sh(0); i((t.timescale shl 16).toInt())
                val asc0 = (aObj shl 3) or (aSfi shr 1); val asc1 = ((aSfi and 1) shl 7) or (aCh shl 3)
                raw(box("esds") {
                    i(0)
                    b(3); b(23); sh(0); b(0)
                    b(4); b(15); b(0x40); b(0x15); b(0); sh(0); i(0); i(0)
                    b(5); b(2); b(asc0); b(asc1)
                    b(6); b(1); b(2)
                })
            })
        })
        // stts
        val stts = ArrayList<LongArray>()
        for (k in 0 until t.count) {
            val d = if (k + 1 < t.count) t.dts[k + 1] - t.dts[k] else t.lastDelta()
            if (stts.isNotEmpty() && stts.last()[1] == d) stts.last()[0]++ else stts.add(longArrayOf(1, d))
        }
        raw(box("stts") { i(0); i(stts.size); stts.forEach { i(it[0].toInt()); i(it[1].toInt()) } })
        if (t.isVideo && (0 until t.count).any { t.cto[it] != 0 }) {
            val ctts = ArrayList<IntArray>()
            for (k in 0 until t.count) { val c = t.cto[k]; if (ctts.isNotEmpty() && ctts.last()[1] == c) ctts.last()[0]++ else ctts.add(intArrayOf(1, c)) }
            raw(box("ctts") { i(0); i(ctts.size); ctts.forEach { i(it[0]); i(it[1]) } })
        }
        if (t.isVideo) { val ks = (0 until t.count).filter { t.keys[it] == 1 }; raw(box("stss") { i(0); i(ks.size); ks.forEach { i(it + 1) } }) }
        raw(box("stsz") { i(0); i(0); i(t.count); for (k in 0 until t.count) i(t.sizes[k]) })
        raw(box("stsc") { i(0); i(1); i(1); i(1); i(1) })
        val big = t.count > 0 && t.offsets[t.count - 1] > 0xFFFFFFF0L
        if (big) raw(box("co64") { i(0); i(t.count); for (k in 0 until t.count) l(t.offsets[k]) })
        else raw(box("stco") { i(0); i(t.count); for (k in 0 until t.count) i(t.offsets[k].toInt()) })
    }

    override fun close() { runCatching { out.flush() }; raf.close() }

    // ---- tiny box builder ----
    class B { val bo = ByteArrayOutputStream(); val d = DataOutputStream(bo)
        fun i(v: Int) = d.writeInt(v); fun l(v: Long) = d.writeLong(v); fun sh(v: Int) = d.writeShort(v); fun b(v: Int) = d.writeByte(v)
        fun s(v: String) = d.writeBytes(v); fun raw(v: ByteArray) = d.write(v); fun zeros(n: Int) = d.write(ByteArray(n))
        fun matrix() { i(0x00010000); i(0); i(0); i(0); i(0x00010000); i(0); i(0); i(0); i(0x40000000) } }
    private fun box(type: String, body: B.() -> Unit): ByteArray {
        val b = B().apply(body).bo.toByteArray()
        val o = ByteArrayOutputStream(b.size + 8); val d = DataOutputStream(o)
        d.writeInt(b.size + 8); d.writeBytes(type); d.write(b); return o.toByteArray()
    }
}

internal class IntArrayList { private var a = IntArray(1024); var size = 0; private set
    fun add(v: Int) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
    operator fun get(i: Int) = a[i] }
internal class LongArrayList { private var a = LongArray(1024); var size = 0; private set
    fun add(v: Long) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
    operator fun get(i: Int) = a[i] }

/** Reads the picture size out of an H.264 SPS. */
internal object H264Sps {
    private class Bits(val b: ByteArray) { var p = 0
        fun u(n: Int): Int { var v = 0; repeat(n) { v = (v shl 1) or ((b[p shr 3].toInt() shr (7 - (p and 7))) and 1); p++ }; return v }
        fun ue(): Int { var z = 0; while (u(1) == 0) z++; return (1 shl z) - 1 + if (z > 0) u(z) else 0 }
        fun se(): Int { val k = ue(); return if (k and 1 == 1) (k + 1) / 2 else -(k / 2) } }

    fun size(nal: ByteArray): Pair<Int, Int> {
        // remove emulation-prevention bytes
        val o = ByteArrayOutputStream(); var zeros = 0
        for (i in 1 until nal.size) { val v = nal[i].toInt() and 0xFF; if (zeros >= 2 && v == 3) { zeros = 0; continue }; o.write(v); zeros = if (v == 0) zeros + 1 else 0 }
        val r = Bits(o.toByteArray())
        val profile = r.u(8); r.u(8); r.u(8); r.ue()
        var chroma = 1
        if (profile in setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
            chroma = r.ue(); if (chroma == 3) r.u(1)
            r.ue(); r.ue(); r.u(1)
            if (r.u(1) == 1) for (i in 0 until if (chroma != 3) 8 else 12) if (r.u(1) == 1) { var last = 8; var next = 8; repeat(if (i < 6) 16 else 64) { if (next != 0) next = (last + r.se() + 256) % 256; last = if (next == 0) last else next } }
        }
        r.ue()
        when (r.ue()) { 0 -> r.ue(); 1 -> { r.u(1); r.se(); r.se(); repeat(r.ue()) { r.se() } } }
        r.ue(); r.u(1)
        val wMbs = r.ue() + 1; val hMap = r.ue() + 1
        val fmo = r.u(1); if (fmo == 0) r.u(1)
        r.u(1)
        var cl = 0; var cr = 0; var ct = 0; var cb = 0
        if (r.u(1) == 1) { cl = r.ue(); cr = r.ue(); ct = r.ue(); cb = r.ue() }
        val cux = if (chroma == 0 || chroma == 3) 1 else 2
        val cuy = (if (chroma == 1) 2 else 1) * (2 - fmo)
        return (wMbs * 16 - (cl + cr) * cux) to ((2 - fmo) * hMap * 16 - (ct + cb) * cuy)
    }
}
