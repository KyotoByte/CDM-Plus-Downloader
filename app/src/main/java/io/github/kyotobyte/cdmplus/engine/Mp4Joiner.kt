package io.github.kyotobyte.cdmplus.engine

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Joins a video-only MP4 and an audio-only MP4 into one regular (non-fragmented, "fast start") MP4,
 * at box level: no decoding, no re-encoding, any codec (H.264, HEVC, VP9-in-MP4, AV1 + AAC/Opus).
 *
 * Inputs may be fragmented MP4 (DASH: moov + moof/mdat pairs, e.g. Instagram/YouTube video parts) or
 * plain MP4 (stbl sample tables, e.g. Instagram's audio part). Android's MediaMuxer can't write
 * VP9 + AAC in any container, which is what Instagram serves, so CDM+ uses this first.
 */
object Mp4Joiner {

    class Sample(val offset: Long, val size: Int, val duration: Long, val cto: Int, val sync: Boolean)

    class Track(
        val handler: String, val timescale: Long, val tkhd: ByteArray, val mdhdLang: Int, val hdlr: ByteArray,
        val mediaHeader: ByteArray, val stsd: ByteArray, val elst: List<LongArray>?, val movieTimescale: Long,
        val samples: List<Sample>,
    ) { val duration: Long get() = samples.sumOf { it.duration } }

    private class Box(val type: String, val start: Long, val headerSize: Int, val size: Long) {
        val bodyStart get() = start + headerSize
        val end get() = start + size
    }

    /** Handler types ("vide", "soun", ...) of every hdlr box in the first bytes of an MP4. */
    fun handlersIn(head: ByteArray): Set<String> {
        val out = LinkedHashSet<String>(); var i = 4
        while (i + 16 <= head.size) {
            if (head[i] == 'h'.code.toByte() && head[i + 1] == 'd'.code.toByte() && head[i + 2] == 'l'.code.toByte() && head[i + 3] == 'r'.code.toByte()) {
                out += String(head, i + 12, 4, Charsets.ISO_8859_1); i += 16
            } else i++
        }
        return out
    }

    /** Sample counts of the video and audio tracks of [f] (for checking a joined file really has sound). */
    fun counts(f: File): Pair<Int, Int> = readTrack(f, "vide").samples.size to readTrack(f, "soun").samples.size

    fun isMp4(f: File): Boolean = runCatching {
        RandomAccessFile(f, "r").use { r -> val b = ByteArray(8); r.readFully(b); String(b, 4, 4, Charsets.ISO_8859_1) in setOf("ftyp", "moov", "styp", "free", "skip", "sidx", "wide") }
    }.getOrDefault(false)

    // ---------- reading ----------

    private fun topBoxes(r: RandomAccessFile): List<Box> {
        val out = ArrayList<Box>(); var pos = 0L; val len = r.length()
        while (pos + 8 <= len) {
            r.seek(pos)
            var size = r.readInt().toLong() and 0xffffffffL
            val type = String(ByteArray(4).also { r.readFully(it) }, Charsets.ISO_8859_1)
            var hs = 8
            if (size == 1L) { size = r.readLong(); hs = 16 } else if (size == 0L) size = len - pos
            if (size < hs) throw IOException("Broken MP4 box '$type' at $pos")
            out += Box(type, pos, hs, size); pos += size
        }
        return out
    }

    private fun children(b: ByteBuffer, from: Int, to: Int): List<Triple<String, Int, Int>> { // type, bodyStart, end
        val out = ArrayList<Triple<String, Int, Int>>(); var p = from
        while (p + 8 <= to) {
            var size = b.getInt(p).toLong() and 0xffffffffL
            val type = String(ByteArray(4) { b.get(p + 4 + it) }, Charsets.ISO_8859_1)
            var hs = 8
            if (size == 1L) { size = b.getLong(p + 8); hs = 16 } else if (size == 0L) size = (to - p).toLong()
            if (size < hs || p + size > to) break
            out += Triple(type, p + hs, (p + size).toInt()); p += size.toInt()
        }
        return out
    }

    private fun List<Triple<String, Int, Int>>.find(t: String) = firstOrNull { it.first == t }
    private fun ByteBuffer.bytes(from: Int, to: Int) = ByteArray(to - from) { get(from + it) }
    private fun ByteBuffer.u32(p: Int) = getInt(p).toLong() and 0xffffffffL

    private class Trex(val dur: Long, val size: Long, val flags: Long)

    /** Reads the first track of [want] ("vide" or "soun") with all its samples. */
    fun readTrack(f: File, want: String): Track {
        RandomAccessFile(f, "r").use { r ->
            val tops = topBoxes(r)
            val moovBox = tops.firstOrNull { it.type == "moov" } ?: throw IOException("No moov in ${f.name}")
            if (moovBox.size > 64L * 1024 * 1024) throw IOException("moov too big")
            val moov = ByteArray((moovBox.size - moovBox.headerSize).toInt()).also { r.seek(moovBox.bodyStart); r.readFully(it) }
            val b = ByteBuffer.wrap(moov)
            val mk = children(b, 0, moov.size)
            val mvhd = mk.find("mvhd") ?: throw IOException("No mvhd")
            val movieTs = if (b.get(mvhd.second) == 1.toByte()) b.u32(mvhd.second + 20) else b.u32(mvhd.second + 12)
            val trexes = HashMap<Long, Trex>()
            mk.find("mvex")?.let { mv -> children(b, mv.second, mv.third).filter { it.first == "trex" }.forEach { t ->
                trexes[b.u32(t.second + 4)] = Trex(b.u32(t.second + 12), b.u32(t.second + 16), b.u32(t.second + 20)) } }
            for (trak in mk.filter { it.first == "trak" }) {
                val tk = children(b, trak.second, trak.third)
                val tkhd = tk.find("tkhd") ?: continue
                val mdia = tk.find("mdia") ?: continue
                val md = children(b, mdia.second, mdia.third)
                val hdlr = md.find("hdlr") ?: continue
                val handler = String(b.bytes(hdlr.second + 8, hdlr.second + 12), Charsets.ISO_8859_1)
                if (handler != want) continue
                val mdhd = md.find("mdhd") ?: continue
                val v1 = b.get(mdhd.second) == 1.toByte()
                val ts = if (v1) b.u32(mdhd.second + 20) else b.u32(mdhd.second + 12)
                val lang = (b.getShort(mdhd.second + if (v1) 32 else 20).toInt() and 0xffff)
                val minf = md.find("minf") ?: continue
                val mi = children(b, minf.second, minf.third)
                val mh = mi.firstOrNull { it.first == "vmhd" || it.first == "smhd" || it.first == "nmhd" }
                val stbl = mi.find("stbl") ?: continue
                val st = children(b, stbl.second, stbl.third)
                val stsd = st.find("stsd") ?: continue
                val trackId = if (b.get(tkhd.second) == 1.toByte()) b.u32(tkhd.second + 20) else b.u32(tkhd.second + 12)
                val elst = tk.find("edts")?.let { e -> children(b, e.second, e.third).find("elst") }?.let { el ->
                    val ev1 = b.get(el.second) == 1.toByte(); val n = b.u32(el.second + 4).toInt(); var p = el.second + 8
                    List(n) {
                        val segDur = if (ev1) b.getLong(p) else b.u32(p); val mt = if (ev1) b.getLong(p + 8) else b.getInt(p + 4).toLong()
                        val rate = b.getInt(p + if (ev1) 16 else 8).toLong(); p += if (ev1) 20 else 12
                        longArrayOf(segDur, mt, rate)
                    }
                }
                val box = { t: Triple<String, Int, Int> -> b.bytes(t.second - 8, t.third).also { require(b.u32(t.second - 8) == (t.third - t.second + 8).toLong()) } }
                val samples = ArrayList<Sample>()
                samplesFromStbl(b, st, samples)
                if (tops.any { it.type == "moof" }) samplesFromFragments(r, tops, trackId, trexes[trackId], samples)
                if (samples.isEmpty()) throw IOException("No $want samples in ${f.name}")
                return Track(handler, ts, b.bytes(tkhd.second, tkhd.third), lang, box(hdlr), mh?.let(box) ?: ByteArray(0), box(stsd), elst, movieTs, samples)
            }
            throw IOException("No $want track in ${f.name}")
        }
    }

    private fun samplesFromStbl(b: ByteBuffer, st: List<Triple<String, Int, Int>>, out: MutableList<Sample>) {
        val stsz = st.find("stsz") ?: return
        val uniform = b.u32(stsz.second + 4); val count = b.u32(stsz.second + 8).toInt()
        if (count == 0) return
        val sizes = IntArray(count) { if (uniform != 0L) uniform.toInt() else b.getInt(stsz.second + 12 + 4 * it) }
        val durs = LongArray(count); st.find("stts")?.let { t ->
            var i = 0; repeat(b.u32(t.second + 4).toInt()) { e -> val c = b.u32(t.second + 8 + 8 * e); val d = b.u32(t.second + 12 + 8 * e); var k = 0L; while (k < c && i < count) { durs[i++] = d; k++ } } }
        val ctos = IntArray(count); st.find("ctts")?.let { t ->
            var i = 0; repeat(b.u32(t.second + 4).toInt()) { e -> val c = b.u32(t.second + 8 + 8 * e); val o = b.getInt(t.second + 12 + 8 * e); var k = 0L; while (k < c && i < count) { ctos[i++] = o; k++ } } }
        val sync = BooleanArray(count) { true }; st.find("stss")?.let { t ->
            java.util.Arrays.fill(sync, false); repeat(b.u32(t.second + 4).toInt()) { e -> val s = b.u32(t.second + 8 + 4 * e).toInt() - 1; if (s in 0 until count) sync[s] = true } }
        val chunkOffs: LongArray = st.find("stco")?.let { t -> LongArray(b.u32(t.second + 4).toInt()) { b.u32(t.second + 8 + 4 * it) } }
            ?: st.find("co64")?.let { t -> LongArray(b.u32(t.second + 4).toInt()) { b.getLong(t.second + 8 + 8 * it) } } ?: return
        val stsc = st.find("stsc") ?: return
        val n = b.u32(stsc.second + 4).toInt()
        val firsts = IntArray(n) { b.u32(stsc.second + 8 + 12 * it).toInt() }; val per = IntArray(n) { b.u32(stsc.second + 12 + 12 * it).toInt() }
        var s = 0
        for (c in chunkOffs.indices) {
            var e = 0; while (e + 1 < n && firsts[e + 1] <= c + 1) e++
            var off = chunkOffs[c]
            repeat(per[e]) { if (s < count) { out += Sample(off, sizes[s], durs[s], ctos[s], sync[s]); off += sizes[s]; s++ } }
        }
    }

    private fun samplesFromFragments(r: RandomAccessFile, tops: List<Box>, trackId: Long, trex: Trex?, out: MutableList<Sample>) {
        for (moof in tops.filter { it.type == "moof" }) {
            if (moof.size > 32L * 1024 * 1024) throw IOException("moof too big")
            val raw = ByteArray((moof.size - moof.headerSize).toInt()).also { r.seek(moof.bodyStart); r.readFully(it) }
            val b = ByteBuffer.wrap(raw)
            for (traf in children(b, 0, raw.size).filter { it.first == "traf" }) {
                val tc = children(b, traf.second, traf.third)
                val tfhd = tc.find("tfhd") ?: continue
                val ff = b.getInt(tfhd.second).toLong() and 0xffffff
                if (b.u32(tfhd.second + 4) != trackId) continue
                var p = tfhd.second + 8
                var base = moof.start
                if (ff and 0x1L != 0L) { base = b.getLong(p); p += 8 }
                if (ff and 0x2L != 0L) p += 4
                var defDur = trex?.dur ?: 0L; var defSize = trex?.size ?: 0L; var defFlags = trex?.flags ?: 0L
                if (ff and 0x8L != 0L) { defDur = b.u32(p); p += 4 }
                if (ff and 0x10L != 0L) { defSize = b.u32(p); p += 4 }
                if (ff and 0x20L != 0L) { defFlags = b.u32(p); p += 4 }
                var next = base
                for (trun in tc.filter { it.first == "trun" }) {
                    val vf = b.getInt(trun.second); val ver = vf ushr 24; val tf = vf.toLong() and 0xffffff
                    val n = b.u32(trun.second + 4).toInt(); var q = trun.second + 8
                    var pos = next
                    if (tf and 0x1L != 0L) { pos = base + b.getInt(q); q += 4 }
                    var first: Long? = null
                    if (tf and 0x4L != 0L) { first = b.u32(q); q += 4 }
                    repeat(n) { i ->
                        val d = if (tf and 0x100L != 0L) b.u32(q).also { q += 4 } else defDur
                        val sz = if (tf and 0x200L != 0L) b.u32(q).also { q += 4 } else defSize
                        val fl = if (tf and 0x400L != 0L) b.u32(q).also { q += 4 } else if (i == 0 && first != null) first else defFlags
                        val c = if (tf and 0x800L != 0L) b.getInt(q).also { q += 4 }.let { if (ver == 0) (it.toLong() and 0xffffffffL).toInt() else it } else 0
                        out += Sample(pos, sz.toInt(), d, c, fl and 0x10000L == 0L)
                        pos += sz
                    }
                    next = pos
                }
            }
        }
    }

    // ---------- writing ----------

    private class Chunk(val track: Int, val first: Int, val count: Int, val time: Double) { var outOffset = 0L }

    /** Joins [video]'s video track and [audio]'s audio track into [out]. Throws on anything it can't handle. */
    fun join(video: File, audio: File, out: File) {
        val tracks = listOf(readTrack(video, "vide"), readTrack(audio, "soun"))
        val srcs = listOf(video, audio)
        // ~0.5 s chunks, interleaved by time, so players read both tracks together.
        val chunks = ArrayList<Chunk>()
        tracks.forEachIndexed { ti, t ->
            var i = 0; var tm = 0L
            while (i < t.samples.size) {
                val start = i; val startT = tm; var bytes = 0L
                while (i < t.samples.size && (i == start || (tm - startT < t.timescale / 2 && bytes < 4L * 1024 * 1024))) { tm += t.samples[i].duration; bytes += t.samples[i].size; i++ }
                chunks += Chunk(ti, start, i - start, startT.toDouble() / t.timescale)
            }
        }
        chunks.sortWith(compareBy<Chunk> { it.time }.thenBy { it.track })
        val dataSize = chunks.sumOf { c -> (c.first until c.first + c.count).sumOf { tracks[c.track].samples[it].size.toLong() } }
        val ftyp = box("ftyp") { w -> w.write("isom".toByteArray()); w.writeInt(512); listOf("isom", "iso2", "mp41").forEach { w.write(it.toByteArray()) } }
        val co64 = dataSize + 64L * 1024 * 1024 > 0xffffffffL
        val moovSize = moov(tracks, chunks, co64).size
        val mdatHeader = if (dataSize + 8 > 0xffffffffL) 16 else 8
        var off = ftyp.size.toLong() + moovSize + mdatHeader
        for (c in chunks) { c.outOffset = off; off += (c.first until c.first + c.count).sumOf { tracks[c.track].samples[it].size.toLong() } }
        val moov = moov(tracks, chunks, co64)
        check(moov.size == moovSize)
        val tmp = File(out.path + ".tmp")
        val ins = srcs.map { RandomAccessFile(it, "r") }
        try {
            java.io.BufferedOutputStream(tmp.outputStream(), 1 shl 20).use { os ->
                val d = DataOutputStream(os)
                d.write(ftyp); d.write(moov)
                if (mdatHeader == 16) { d.writeInt(1); d.write("mdat".toByteArray()); d.writeLong(dataSize + 16) } else { d.writeInt((dataSize + 8).toInt()); d.write("mdat".toByteArray()) }
                val buf = ByteArray(1 shl 20)
                for (c in chunks) {
                    val r = ins[c.track]; val ss = tracks[c.track].samples
                    var k = c.first
                    while (k < c.first + c.count) {
                        // copy runs of samples that are contiguous in the source in one go
                        val s0 = ss[k].offset; var len = ss[k].size.toLong(); k++
                        while (k < c.first + c.count && ss[k].offset == s0 + len) { len += ss[k].size; k++ }
                        r.seek(s0); var left = len
                        while (left > 0) { val n = r.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n <= 0) throw IOException("Source file ended early (download incomplete?)"); d.write(buf, 0, n); left -= n }
                    }
                }
                d.flush()
            }
        } finally { ins.forEach { runCatching { it.close() } } }
        if (tmp.length() != ftyp.size + moovSize + mdatHeader + dataSize) { tmp.delete(); throw IOException("Joined file has the wrong size") }
        // Read the result back: both tracks must be there with every sample, or it's a failure (never a silent no-sound file).
        val (vc, ac) = runCatching { counts(tmp) }.getOrElse { tmp.delete(); throw IOException("Joined file doesn't read back: ${it.message}") }
        if (vc != tracks[0].samples.size || ac != tracks[1].samples.size || ac == 0) { tmp.delete(); throw IOException("Joined file is missing samples (video $vc/${tracks[0].samples.size}, audio $ac/${tracks[1].samples.size})") }
        out.delete(); if (!tmp.renameTo(out)) { tmp.copyTo(out, true); tmp.delete() }
    }

    private fun box(type: String, body: (DataOutputStream) -> Unit): ByteArray {
        val bo = ByteArrayOutputStream(); val d = DataOutputStream(bo); body(d); d.flush()
        val b = bo.toByteArray(); val o = ByteArrayOutputStream(b.size + 8); val od = DataOutputStream(o)
        od.writeInt(b.size + 8); od.write(type.toByteArray(Charsets.ISO_8859_1)); od.write(b); od.flush(); return o.toByteArray()
    }

    private const val MOVIE_TS = 1000L

    private fun moov(tracks: List<Track>, chunks: List<Chunk>, co64: Boolean): ByteArray {
        val durs = tracks.map { it.duration * MOVIE_TS / it.timescale }
        return box("moov") { m ->
            m.write(box("mvhd") { w ->
                w.writeInt(0); w.writeInt(0); w.writeInt(0); w.writeInt(MOVIE_TS.toInt()); w.writeInt(durs.max().toInt())
                w.writeInt(0x00010000); w.writeShort(0x0100); w.writeShort(0); w.writeInt(0); w.writeInt(0)
                intArrayOf(0x10000, 0, 0, 0, 0x10000, 0, 0, 0, 0x40000000).forEach(w::writeInt)
                repeat(6) { w.writeInt(0) }; w.writeInt(tracks.size + 1)
            })
            tracks.forEachIndexed { ti, t -> m.write(trak(ti, t, durs[ti], chunks.filter { it.track == ti }, co64)) }
        }
    }

    private fun trak(ti: Int, t: Track, movieDur: Long, chunks: List<Chunk>, co64: Boolean): ByteArray = box("trak") { tr ->
        tr.write(box("tkhd") { w ->
            val v1 = t.tkhd[0] == 1.toByte(); val rest = t.tkhd.copyOfRange(if (v1) 36 else 24, t.tkhd.size)
            w.writeInt(0x01000003); w.writeLong(0); w.writeLong(0); w.writeInt(ti + 1); w.writeInt(0); w.writeLong(movieDur); w.write(rest)
        })
        t.elst?.takeIf { it.isNotEmpty() }?.let { el ->
            tr.write(box("edts") { e -> e.write(box("elst") { w ->
                w.writeInt(0x01000000); w.writeInt(el.size)
                el.forEach { (seg, mt, rate) ->
                    val sd = if (seg == 0L) (movieDur - (if (mt > 0) mt * MOVIE_TS / t.timescale else 0)).coerceAtLeast(0) else seg * MOVIE_TS / maxOf(1L, t.movieTimescale)
                    w.writeLong(sd); w.writeLong(mt); w.writeInt(rate.toInt())
                }
            }) })
        }
        tr.write(box("mdia") { md ->
            md.write(box("mdhd") { w -> w.writeInt(0x01000000); w.writeLong(0); w.writeLong(0); w.writeInt(t.timescale.toInt()); w.writeLong(t.duration); w.writeShort(t.mdhdLang); w.writeShort(0) })
            md.write(t.hdlr)
            md.write(box("minf") { mi ->
                if (t.mediaHeader.isNotEmpty()) mi.write(t.mediaHeader)
                else mi.write(box(if (t.handler == "vide") "vmhd" else "smhd") { w -> if (t.handler == "vide") { w.writeInt(1); w.writeLong(0) } else { w.writeInt(0); w.writeInt(0) } })
                mi.write(box("dinf") { di -> di.write(box("dref") { w -> w.writeInt(0); w.writeInt(1); w.write(box("url ") { u -> u.writeInt(1) }) }) })
                mi.write(stbl(t, chunks, co64))
            })
        })
    }

    private fun stbl(t: Track, chunks: List<Chunk>, co64: Boolean): ByteArray = box("stbl") { st ->
        val s = t.samples
        st.write(t.stsd)
        st.write(box("stts") { w -> val runs = runs(s.map { it.duration }); w.writeInt(0); w.writeInt(runs.size); runs.forEach { (v, c) -> w.writeInt(c); w.writeInt(v.toInt()) } })
        if (s.any { it.cto != 0 }) st.write(box("ctts") { w -> val runs = runs(s.map { it.cto.toLong() }); w.writeInt(if (s.any { it.cto < 0 }) 0x01000000 else 0); w.writeInt(runs.size); runs.forEach { (v, c) -> w.writeInt(c); w.writeInt(v.toInt()) } })
        if (s.any { !it.sync }) st.write(box("stss") { w -> val idx = s.indices.filter { s[it].sync }; w.writeInt(0); w.writeInt(idx.size); idx.forEach { w.writeInt(it + 1) } })
        st.write(box("stsc") { w ->
            val e = ArrayList<IntArray>(); chunks.forEachIndexed { i, c -> if (e.isEmpty() || e.last()[1] != c.count) e += intArrayOf(i + 1, c.count) }
            w.writeInt(0); w.writeInt(e.size); e.forEach { w.writeInt(it[0]); w.writeInt(it[1]); w.writeInt(1) }
        })
        st.write(box("stsz") { w -> w.writeInt(0); w.writeInt(0); w.writeInt(s.size); s.forEach { w.writeInt(it.size) } })
        if (co64) st.write(box("co64") { w -> w.writeInt(0); w.writeInt(chunks.size); chunks.forEach { w.writeLong(it.outOffset) } })
        else st.write(box("stco") { w -> w.writeInt(0); w.writeInt(chunks.size); chunks.forEach { w.writeInt(it.outOffset.toInt()) } })
    }

    private fun runs(v: List<Long>): List<Pair<Long, Int>> {
        val out = ArrayList<Pair<Long, Int>>()
        for (x in v) if (out.isNotEmpty() && out.last().first == x) out[out.size - 1] = x to out.last().second + 1 else out += x to 1
        return out
    }
}
