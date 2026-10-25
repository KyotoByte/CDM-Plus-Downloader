package io.github.kyotobyte.cdmplus.service

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.View
import android.view.Window
import android.view.WindowManager

/**
 * Native high refresh rate: always asks for the display mode with the highest refresh rate
 * at the CURRENT resolution (never changes resolution). No setting: this is how CDM+ runs.
 */
object DisplayRate {
    /** Best mode at the current physical resolution, or null when the display has only one rate. */
    fun bestMode(d: Display?): Display.Mode? {
        d ?: return null
        val cur = d.mode ?: return null
        return pickBest(cur.physicalWidth, cur.physicalHeight, d.supportedModes.orEmpty().map { Triple(it.modeId, it.physicalWidth to it.physicalHeight, it.refreshRate) })
            ?.let { id -> d.supportedModes.firstOrNull { it.modeId == id } }
    }

    /** Pure selection logic (unit-tested): highest refresh rate among modes with the same resolution. */
    fun pickBest(w: Int, h: Int, modes: List<Triple<Int, Pair<Int, Int>, Float>>): Int? =
        modes.filter { it.second.first == w && it.second.second == h && it.third > 0f }.maxByOrNull { it.third }?.first

    fun displayOf(ctx: Context): Display? = runCatching {
        if (Build.VERSION.SDK_INT >= 30) ctx.display
        else ctx.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
    }.getOrNull() ?: runCatching { ctx.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY) }.getOrNull()

    /** Fills window params with the best mode. Returns true when anything changed. */
    fun applyTo(lp: WindowManager.LayoutParams, d: Display?): Boolean {
        val best = bestMode(d) ?: return false
        if (lp.preferredDisplayModeId == best.modeId && lp.preferredRefreshRate == best.refreshRate) return false
        lp.preferredDisplayModeId = best.modeId
        lp.preferredRefreshRate = best.refreshRate
        return true
    }

    /** Activity window + its content view (Android 15+ adaptive refresh votes per view). */
    fun apply(window: Window, d: Display?) {
        runCatching {
            val lp = window.attributes
            if (applyTo(lp, d)) window.attributes = lp
            hintView(window.decorView, d)
        }
    }

    /** Frame-rate hint on a view: API 35+ requestedFrameRate (adaptive refresh). */
    fun hintView(v: View?, d: Display?) {
        v ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= 35) {
                val best = bestMode(d)?.refreshRate ?: return
                v.requestedFrameRate = best
            }
        }
    }
}
