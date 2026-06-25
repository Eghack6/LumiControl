package com.lumicontrol.app

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast

class CursorOverlayService : Service() {

    private var cursorView: ImageView? = null
    private lateinit var wm: WindowManager
    private var params: WindowManager.LayoutParams? = null
    private var screenW = 1920
    private var screenH = 1080

    companion object {
        var instance: CursorOverlayService? = null
        private const val SIZE = 56
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        getScreenSize()
        createCursor()
    }

    private fun getScreenSize() {
        try {
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            screenW = metrics.widthPixels
            screenH = metrics.heightPixels
        } catch (_: Exception) {}
    }

    private fun createCursor() {
        if (cursorView != null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "未获得悬浮窗权限，无法显示鼠标光标", Toast.LENGTH_LONG).show()
            return
        }

        val dot = ImageView(this)
        val shape = GradientDrawable().apply {
            setShape(GradientDrawable.OVAL)
            setStroke(2, 0xCCFFFFFF.toInt())
            setColor(0x66E94560.toInt())
        }
        dot.setImageDrawable(shape)

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }

        params = WindowManager.LayoutParams(
            SIZE, SIZE,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenW / 2 - SIZE / 2
            y = screenH / 2 - SIZE / 2
        }

        try {
            wm.addView(dot, params)
            cursorView = dot
            Toast.makeText(this, "鼠标光标已启动", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}
    }

    fun moveCursor(x: Float, y: Float) {
        if (cursorView == null || params == null) return
        params!!.x = (x - SIZE / 2).toInt()
        params!!.y = (y - SIZE / 2).toInt()
        try { wm.updateViewLayout(cursorView, params) } catch (_: Exception) {}
    }

    /** Move cursor by delta from current position */
    fun moveCursorBy(dx: Float, dy: Float) {
        if (cursorView == null || params == null) return
        params!!.x = (params!!.x + dx).toInt()
        params!!.y = (params!!.y + dy).toInt()
        try { wm.updateViewLayout(cursorView, params) } catch (_: Exception) {}
    }

    /** Get current cursor center position */
    fun getCursorPosition(): Pair<Float, Float> {
        val p = params ?: return Pair(screenW / 2f, screenH / 2f)
        return Pair((p.x + SIZE / 2).toFloat(), (p.y + SIZE / 2).toFloat())
    }

    fun retry() {
        createCursor()
    }

    override fun onDestroy() {
        instance = null
        cursorView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        cursorView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
