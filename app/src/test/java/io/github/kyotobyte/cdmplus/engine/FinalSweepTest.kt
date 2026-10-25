package io.github.kyotobyte.cdmplus.engine

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** v1.0 final sweep: crash paths found in review. */
class FinalSweepTest {
    @Test fun hlsKeyWithoutUriIsAClearErrorNotACrash() {
        val pl = "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-KEY:METHOD=AES-128\n#EXTINF:2,\na.ts\n#EXT-X-ENDLIST"
        val e = runCatching { HlsDownloader(OkHttpClient()).parse("https://x.test/v.m3u8", pl) { "" } }.exceptionOrNull()
        assertTrue("expected IOException, got $e", e is IOException)
    }

    @Test fun hlsKeyMethodNoneStillParses() {
        val pl = "#EXTM3U\n#EXT-X-KEY:METHOD=NONE\n#EXTINF:2,\na.ts\n#EXTINF:2,\nb.ts\n#EXT-X-ENDLIST"
        val m = HlsDownloader(OkHttpClient()).parse("https://x.test/v.m3u8", pl) { "" }
        assertEquals(2, m.segs.size)
    }
}
