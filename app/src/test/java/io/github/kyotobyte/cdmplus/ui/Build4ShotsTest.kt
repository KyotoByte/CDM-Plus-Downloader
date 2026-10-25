package io.github.kyotobyte.cdmplus.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import io.github.kyotobyte.cdmplus.engine.DownloadItem
import io.github.kyotobyte.cdmplus.engine.Status
import io.github.kyotobyte.cdmplus.engine.TorrentDownloader
import io.github.kyotobyte.cdmplus.grab.GrabItem
import io.github.kyotobyte.cdmplus.grab.GrabOption
import io.github.kyotobyte.cdmplus.grab.GrabResult
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xxhdpi", application = android.app.Application::class)
class Build4ShotsTest {
    @get:Rule val compose = createComposeRule()
    private val out = System.getProperty("cdm.shots") ?: "build/cdmplus-shots"
    private val noop = object : DownloadActions {
        override fun pause(id: String) {}; override fun resume(id: String) {}; override fun retry(id: String) {}; override fun cancel(id: String) {}
        override fun open(item: DownloadItem) {}; override fun share(item: DownloadItem) {}; override fun delete(item: DownloadItem, withFile: Boolean) {}
    }
    private val mb = 1024L * 1024

    private fun shot(name: String, light: Boolean = false, custom: Int = -1, content: @Composable () -> Unit) {
        Px.light = light
        InlineDialogs = true
        compose.setContent { CdmTheme(0, custom) { Box(Modifier.fillMaxSize()) { content() } } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$out/b4-$name.png")
        Px.light = false
    }

    private val list = listOf(
        DownloadItem("1", "https://x/1", "TikTok_bbcnews_Moon mission.mp4", 9 * mb, 9 * mb, Status.DONE, source = "TikTok", finishedAt = System.currentTimeMillis()),
        DownloadItem("2", "https://x/2", "YouTube_Me at the zoo_1080p.mp4", 48 * mb, 21 * mb, Status.RUNNING, 8, audioUrl = "https://x/a"),
        DownloadItem("3", "https://x/3", "live-stream.mp4", -1, 30 * mb, Status.RUNNING, hlsTotal = 120, hlsDone = 71),
        DownloadItem("4", "magnet:?xt=urn:btih:abc", "debian-13-netinst.iso", 700 * mb, 700 * mb, Status.DONE, seeding = true, uploadedBytes = 312 * mb, torrentPeers = 9),
        DownloadItem("5", "https://x/5", "Original audio - @natgeoanimals.m4a", 2 * mb, 2 * mb, Status.DONE, finishedAt = System.currentTimeMillis()),
    )

    @Test fun a_grab_tiktok() = shot("grab-tiktok") {
        Column(Modifier.fillMaxSize()) { Box(Modifier.weight(1f)) { DownloadsScreen(list, mapOf("2" to 4_200_000L, "3" to 1_900_000L), noop, {}, onScanQr = {}) }; BottomBar(Tab.DOWNLOADS) {} }
        GrabDialog("https://www.tiktok.com/@bbcnews/video/1", load = {
            GrabResult("TikTok", "Nasa's first crewed mission around the Moon in more than 50 years", "bbcnews",
                listOf(GrabItem(0, listOf(
                    GrabOption("u1", "video", "720p HEVC · 382 kbps", 720, 1280, 3_900_000), GrabOption("u2", "video", "576p H.264 · 554 kbps", 576, 1024, 5_600_000),
                    GrabOption("u3", "video", "576p HEVC · 307 kbps", 576, 1024, 3_100_000), GrabOption("u4", "video", "With watermark", 576, 1024)))),
                "Original audio - @BBC News", "m")
        }, resolve = { error("x") }, onDownload = {}, onOpenInBrowser = {}, onDismiss = {})
    }

    @Test fun b_grab_instagram_carousel() = shot("grab-instagram") {
        Column(Modifier.fillMaxSize()) { Box(Modifier.weight(1f)) { DownloadsScreen(list, emptyMap(), noop, {}) }; BottomBar(Tab.DOWNLOADS) {} }
        GrabDialog("https://www.instagram.com/p/x/", load = {
            GrabResult("Instagram", "Weekend in the hills", "pixel.trails", listOf(
                GrabItem(0, listOf(GrabOption("a", "image", "Photo 1080x1350", 1080, 1350, ext = "jpg"))),
                GrabItem(1, listOf(GrabOption("b", "video", "Video 1920p", 1080, 1920), GrabOption("c", "image", "Cover photo 1080x1920", ext = "jpg"))),
                GrabItem(2, listOf(GrabOption("d", "image", "Photo 1080x1350", 1080, 1350, ext = "jpg")))), "Original audio - @pixel.trails")
        }, resolve = { error("x") }, onDownload = {}, onOpenInBrowser = {}, onDismiss = {})
    }

    @Test fun c_grab_youtube() = shot("grab-youtube") {
        Column(Modifier.fillMaxSize()) { Box(Modifier.weight(1f)) { DownloadsScreen(list, emptyMap(), noop, {}) }; BottomBar(Tab.DOWNLOADS) {} }
        GrabDialog("https://youtube.com/watch?v=x", load = {
            GrabResult("YouTube", "Me at the zoo", "jawed", listOf(GrabItem(0, listOf(
                GrabOption("v", "video", "1080p MP4 · avc1", 1920, 1080, audioUrl = "a"), GrabOption("v", "video", "1080p WebM · vp09", 1920, 1080, ext = "webm", audioUrl = "a"),
                GrabOption("v", "video", "720p MP4 · avc1", 1280, 720, audioUrl = "a"), GrabOption("v", "video", "360p MP4 (with sound, no merge)", 640, 360),
                GrabOption("a", "audio", "Audio only M4A · 130 kbps", ext = "m4a"), GrabOption("a", "audio", "Audio only Opus/WebM · 107 kbps", ext = "webm")))),
                "Me at the zoo - jawed", subtitles = listOf(GrabOption("s", "subtitle", "Subtitles: English", ext = "vtt"), GrabOption("s", "subtitle", "Subtitles: German (auto)", ext = "vtt")))
        }, resolve = { error("x") }, onDownload = {}, onOpenInBrowser = {}, onDismiss = {})
    }

    @Test fun d_torrent_files_light() = shot("torrent-light", light = true, custom = 0xFF7A00) {
        Column(Modifier.fillMaxSize()) { Box(Modifier.weight(1f)) { DownloadsScreen(list, mapOf("2" to 4_200_000L), noop, {}, onScanQr = {}) }; BottomBar(Tab.DOWNLOADS) {} }
        TorrentFilesDialog(load = { listOf(
            TorrentDownloader.TFile(0, "Show/S01E01.mkv", 1_400_000_000), TorrentDownloader.TFile(1, "Show/S01E02.mkv", 1_380_000_000),
            TorrentDownloader.TFile(2, "Show/S01E03.mkv", 1_420_000_000), TorrentDownloader.TFile(3, "Show/subs/en.srt", 60_000), TorrentDownloader.TFile(4, "Show/sample.mkv", 30_000_000)) },
            onConfirm = {}, onDismiss = {})
    }

    @Test fun e_light_downloads() = shot("light-downloads", light = true, custom = 0x2E7DFF) {
        Column(Modifier.fillMaxSize()) { Box(Modifier.weight(1f)) { DownloadsScreen(list, mapOf("2" to 4_200_000L, "3" to 1_900_000L), noop, {}, onScanQr = {}) }; BottomBar(Tab.DOWNLOADS) {} }
    }
}
