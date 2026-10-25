package io.github.kyotobyte.cdmplus.service

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import io.github.kyotobyte.cdmplus.engine.TsToMp4
import java.io.File
import java.nio.ByteBuffer

/** On-phone remuxing with Android's MediaExtractor + MediaMuxer: no re-encode, no ffmpeg. */
object MediaTools {

    /** Video-only file + audio-only file -> one file. MP4 (H.264/AV1 + AAC) or WebM (VP9 + Opus). */
    fun mux(video: File, audio: File, out: File): Boolean {
        // 1) CDM+'s own box-level joiner: any codec in MP4 (Instagram = VP9 + AAC, which MediaMuxer can't write),
        //    fragmented (DASH) or plain MP4 inputs. Tested on real Instagram/YouTube parts.
        if (io.github.kyotobyte.cdmplus.engine.Mp4Joiner.isMp4(video) && io.github.kyotobyte.cdmplus.engine.Mp4Joiner.isMp4(audio)) {
            val ok = runCatching { io.github.kyotobyte.cdmplus.engine.Mp4Joiner.join(video, audio, out); out.length() > 0 }.getOrDefault(false)
            if (ok) return true
            out.delete()
        }
        // 2) Android's MediaMuxer (WebM VP9 + Opus, or MP4 H.264/HEVC + AAC).
        return muxWithMediaMuxer(video, audio, out)
    }

    private fun muxWithMediaMuxer(video: File, audio: File, out: File): Boolean {
        val ve = MediaExtractor().apply { setDataSource(video.path) }
        val ae = MediaExtractor().apply { setDataSource(audio.path) }
        try {
            val vt = (0 until ve.trackCount).firstOrNull { ve.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true } ?: return false
            val at = (0 until ae.trackCount).firstOrNull { ae.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return false
            val vf = ve.getTrackFormat(vt); val af = ae.getTrackFormat(at)
            val vm = vf.getString(MediaFormat.KEY_MIME) ?: ""
            val am = af.getString(MediaFormat.KEY_MIME) ?: ""
            val webm = vm.contains("vp9") || vm.contains("vp8")
            // MediaMuxer: WebM takes only Opus/Vorbis audio, MP4 takes no VP8/VP9. Don't write a broken file.
            if (webm && !(am.contains("opus") || am.contains("vorbis"))) return false
            if (!webm && (am.contains("opus") || am.contains("vorbis")) && android.os.Build.VERSION.SDK_INT < 29) return false
            val muxer = MediaMuxer(out.path, if (webm) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                ve.selectTrack(vt); ae.selectTrack(at)
                if (vf.containsKey(MediaFormat.KEY_ROTATION)) runCatching { muxer.setOrientationHint(vf.getInteger(MediaFormat.KEY_ROTATION)) }
                val vi = muxer.addTrack(vf); val ai = muxer.addTrack(af)
                muxer.start()
                copy(ve, muxer, vi); copy(ae, muxer, ai)
                muxer.stop()
            } finally { runCatching { muxer.release() } }
            return out.length() > 0
        } catch (e: Exception) {
            out.delete(); return false
        } finally { ve.release(); ae.release() }
    }

    /** Keeps only the audio track. AAC -> .m4a, Opus/Vorbis -> .webm. MPEG-TS goes through CDM+'s own remuxer. */
    fun extractAudio(input: File, out: File): String? {
        val head = input.inputStream().use { s -> ByteArray(1).also { s.read(it) } }
        if (head[0] == 0x47.toByte()) {
            return runCatching { input.inputStream().use { TsToMp4.convert(it, out, audioOnly = true) }; "m4a" }.getOrNull()
        }
        val ex = MediaExtractor()
        try {
            ex.setDataSource(input.path)
            val t = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
            val f = ex.getTrackFormat(t)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: return null
            val (fmt, ext) = when {
                mime == MediaFormat.MIMETYPE_AUDIO_AAC || mime.contains("mp4a") -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4 to "m4a"
                mime.contains("opus") || mime.contains("vorbis") -> MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM to "webm"
                else -> return null
            }
            val m = MediaMuxer(out.path, fmt)
            try { ex.selectTrack(t); val i = m.addTrack(f); m.start(); copy(ex, m, i); m.stop() } finally { runCatching { m.release() } }
            return ext
        } catch (e: Exception) { out.delete(); return null } finally { ex.release() }
    }

    private fun copy(ex: MediaExtractor, m: MediaMuxer, track: Int) {
        val buf = ByteBuffer.allocate(4 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        while (true) {
            info.offset = 0
            info.size = ex.readSampleData(buf, 0)
            if (info.size < 0) break
            info.presentationTimeUs = maxOf(0L, ex.sampleTime)
            info.flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            m.writeSampleData(track, buf, info)
            ex.advance()
        }
    }
}
