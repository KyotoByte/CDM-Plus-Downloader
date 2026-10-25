package io.github.kyotobyte.cdmplus.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import io.github.kyotobyte.cdmplus.data.AppSettings
import io.github.kyotobyte.cdmplus.engine.DownloadItem
import io.github.kyotobyte.cdmplus.engine.Segment
import io.github.kyotobyte.cdmplus.engine.Status
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode


/** Build 8 device-compat: the main screens must lay out on a small phone and a tablet in landscape. */
abstract class DeviceShotsBase(private val tag: String) {
    @get:Rule val compose = createComposeRule()
    private val out = System.getProperty("cdm.shots") ?: "build/cdmplus-shots"
    private val noop = object : DownloadActions {
        override fun pause(id: String) {}; override fun resume(id: String) {}; override fun retry(id: String) {}; override fun cancel(id: String) {}
        override fun open(item: DownloadItem) {}; override fun share(item: DownloadItem) {}; override fun delete(item: DownloadItem, withFile: Boolean) {}
    }
    private val mb = 1024L * 1024
    private val now = System.currentTimeMillis()
    private val sample = listOf(
        DownloadItem("1", "https://example.com/ubuntu-24.04-desktop-amd64.iso", "ubuntu-24.04-desktop-amd64.iso", 5900 * mb, 2480 * mb, Status.RUNNING, 16, List(16) { val sz = 5900 * mb / 16; Segment(it, it * sz, (it + 1) * sz - 1, (sz * ((it * 37 % 9) + 2) / 11).coerceAtMost(sz)) }, true),
        DownloadItem("2", "https://example.com/holiday-vlog-4k.mp4", "holiday-vlog-4k.mp4", 812 * mb, 655 * mb, Status.RUNNING, 8, List(8) { val sz = 812 * mb / 8; Segment(it, it * sz, (it + 1) * sz - 1, sz * (5 + it % 4) / 10) }, true),
        DownloadItem("3", "https://example.com/podcast-ep42.mp3", "podcast-ep42.mp3", 96 * mb, 31 * mb, Status.PAUSED, 8),
        DownloadItem("4", "https://example.com/project-assets.zip", "project-assets.zip", 1200 * mb, 0, Status.SCHEDULED, 8, scheduledAt = now + 3_600_000),
        DownloadItem("5", "https://example.com/annual-report-2026.pdf", "annual-report-2026.pdf", 14 * mb, 14 * mb, Status.DONE, finishedAt = now - 600_000),
        DownloadItem("6", "https://example.com/expired-link.apk", "expired-link.apk", -1, 0, Status.FAILED, error = "Link expired (HTTP 410)"),
    )
    private val graph = List(60) { i -> (6_000_000 + 5_000_000 * kotlin.math.sin(i / 6.0) + (i * 7919 % 13) * 300_000).toLong().coerceAtLeast(0) + if (i > 40) 4_000_000 else 0 }
    private val speeds = mapOf("1" to 9_400_000L, "2" to 3_100_000L)

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent { CdmTheme(0) { Box(Modifier.fillMaxSize()) { content() } } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$out/compat-$tag-$name.png")
    }

    @Test fun downloads() = shot("downloads") {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) { DownloadsScreen(sample, speeds, noop, {}, clipboardUrl = "https://cdn.example.org/files/sample-video.mp4", tick = 2, history = graph) }
            BottomBar(Tab.DOWNLOADS) {}
        }
    }

    @Test fun settings() = shot("settings") {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) { SettingsScreen(AppSettings(threads = 8, maxSimultaneous = 3, wifiOnly = true, speedLimitKb = 0, batteryStop = 15), {}, {}) }
            BottomBar(Tab.SETTINGS) {}
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w320dp-h480dp-mdpi", fontScale = 1.3f, application = android.app.Application::class)
class SmallPhoneShotsTest : DeviceShotsBase("small320dp-font130")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi", application = android.app.Application::class)
class TabletLandscapeShotsTest : DeviceShotsBase("tablet1280dp-land")
