package io.github.kyotobyte.cdmplus.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import io.github.kyotobyte.cdmplus.CdmApp
import io.github.kyotobyte.cdmplus.MainActivity
import io.github.kyotobyte.cdmplus.R
import io.github.kyotobyte.cdmplus.engine.Status

/** Floating speed window: a small draggable pixel box over other apps while downloads run. */
class FloatingService : Service() {
    private var view: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { update(); handler.postDelayed(this, 1000) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        val wm = getSystemService(WindowManager::class.java)
        val tv = TextView(this).apply {
            typeface = ResourcesCompat.getFont(this@FloatingService, R.font.silkscreen)
            textSize = 13f
            setTextColor(Color.parseColor("#39FF88"))
            setBackgroundColor(Color.parseColor("#E60C0D10"))
            setPadding(22, 14, 22, 14)
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 24; y = 300; DisplayRate.applyTo(this, DisplayRate.displayOf(this@FloatingService)) }
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = ox + (e.rawX - sx).toInt(); lp.y = oy + (e.rawY - sy).toInt()
                    if (kotlin.math.abs(e.rawX - sx) + kotlin.math.abs(e.rawY - sy) > 12) moved = true
                    runCatching { wm.updateViewLayout(tv, lp) }
                }
                MotionEvent.ACTION_UP -> if (!moved) startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            true
        }
        runCatching { wm.addView(tv, lp); view = tv; DisplayRate.hintView(tv, DisplayRate.displayOf(this)) }.onFailure { stopSelf(); return }
        handler.post(tick)
    }

    private fun update() {
        val app = CdmApp.instance
        if (!app.settings.value.floating) { stopSelf(); return }
        val active = app.core.items.value.count { it.status == Status.RUNNING }
        val speed = app.core.speeds.value.values.sum()
        view?.visibility = if (active > 0) View.VISIBLE else View.GONE
        view?.text = "CDM+  ${io.github.kyotobyte.cdmplus.ui.formatBytes(speed)}/s  x$active"
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        view?.let { v -> runCatching { getSystemService(WindowManager::class.java).removeView(v) } }
        view = null
        super.onDestroy()
    }

    companion object {
        fun sync(ctx: Context) {
            val on = CdmApp.instance.settings.value.floating && Settings.canDrawOverlays(ctx)
            val i = Intent(ctx, FloatingService::class.java)
            runCatching { if (on) ctx.startService(i) else ctx.stopService(i) }
        }
    }
}
