package com.lumicontrol.app

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import com.lumicontrol.app.widget.TrajectoryOverlayView
import java.io.File

class CursorOverlayService : Service() {

    private var cursorView: ImageView? = null
    private lateinit var wm: WindowManager
    private var params: WindowManager.LayoutParams? = null
    private var screenW = 1920
    private var screenH = 1080
    private val inactivityHandler = Handler(Looper.getMainLooper())
    private val inactivityRunnable = Runnable {
        cursorVisible = false
        cursorView?.visibility = View.GONE
    }
    private var cursorVisible = true
    private var currentSize = 56
    private var trajectoryView: TrajectoryOverlayView? = null
    private var trajectoryParams: WindowManager.LayoutParams? = null
    private val trajectoryCleanup = Runnable { removeTrajectory() }

    private fun removeTrajectory() {
        trajectoryView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        trajectoryView = null
        trajectoryParams = null
    }


    companion object {
        var instance: CursorOverlayService? = null
        var lastScreenW: Int = 1920
            private set
        var lastScreenH: Int = 1080
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        SettingsManager.init(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        getScreenSize()
        currentSize = SettingsManager.get().cursorSize.coerceIn(20, 500)
        createCursor()
        applyInactivityTimeout()
        // Rebuild cursor to clear any WindowManager state that may interfere
        // with dispatchGesture (observed on certain Android versions)
        applySettings()
    }

    private fun getScreenSize() {
        try {
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            screenW = metrics.widthPixels
            screenH = metrics.heightPixels
            lastScreenW = screenW
            lastScreenH = screenH
        } catch (_: Exception) {}
    }

    private fun applyInactivityTimeout() {
        inactivityHandler.removeCallbacks(inactivityRunnable)
        val timeout = SettingsManager.get().inactivityTimeout * 1000L
        if (cursorVisible) {
            inactivityHandler.postDelayed(inactivityRunnable, timeout)
        }
    }

    private fun createCursor() {
        if (cursorView != null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "未获得悬浮窗权限，无法显示鼠标光标", Toast.LENGTH_LONG).show()
            return
        }

        val dot = ImageView(this)
        dot.setImageDrawable(SettingsManager.buildCursorDrawable(this))

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }

        params = WindowManager.LayoutParams(
            currentSize, currentSize,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenW / 2 - currentSize / 2
            y = screenH / 2 - currentSize / 2
        }

        restorePosition()

        try {
            wm.addView(dot, params)
            cursorView = dot
        } catch (_: Exception) {}
    }

    /** Rebuild cursor after settings change */
    fun applySettings() {
        val s = SettingsManager.get()
        currentSize = s.cursorSize.coerceIn(20, 500)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(this)) {
            return
        }

        val oldCursor = cursorView

        val dot = ImageView(this)
        dot.setImageDrawable(SettingsManager.buildCursorDrawable(this))

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }

        val oldParams = params
        val oldSize = params?.width ?: currentSize
        val ratioX = if (oldParams != null && oldSize > 0) oldParams.x.toFloat() / (screenW - oldSize).toFloat() else 0.5f
        val ratioY = if (oldParams != null && oldSize > 0) oldParams.y.toFloat() / (screenH - oldSize).toFloat() else 0.5f

        params = WindowManager.LayoutParams(
            currentSize, currentSize,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val maxX = screenW - currentSize
            val maxY = screenH - currentSize
            x = (ratioX * maxX).toInt().coerceIn(0, maxX)
            y = (ratioY * maxY).toInt().coerceIn(0, maxY)
        }

        try {
            wm.addView(dot, params)
            cursorView = dot
            if (oldCursor != null) {
                wm.removeView(oldCursor)
            }
        } catch (_: Exception) {
            cursorView = oldCursor
        }

        applyInactivityTimeout()
        showCursor()
        savePosition()
    }

    private fun resetInactivityTimer() {
        inactivityHandler.removeCallbacks(inactivityRunnable)
        if (cursorVisible) {
            val timeout = SettingsManager.get().inactivityTimeout * 1000L
            inactivityHandler.postDelayed(inactivityRunnable, timeout)
        }
    }

    fun moveCursor(x: Float, y: Float) {
        try {
            if (cursorView == null || params == null) return
            getScreenSize()
            val maxX = screenW - currentSize
            val maxY = screenH - currentSize
            params!!.x = (x - currentSize / 2f).toInt().coerceIn(0, maxX)
            params!!.y = (y - currentSize / 2f).toInt().coerceIn(0, maxY)
            wm.updateViewLayout(cursorView, params)
            showCursor()
            savePosition()
        } catch (_: Exception) {}
    }

    fun moveCursorBy(dx: Float, dy: Float) {
        try {
            if (cursorView == null || params == null) return
            getScreenSize()
            val maxX = screenW - currentSize
            val maxY = screenH - currentSize
            params!!.x = (params!!.x + dx).toInt().coerceIn(0, maxX)
            params!!.y = (params!!.y + dy).toInt().coerceIn(0, maxY)
            wm.updateViewLayout(cursorView, params)
            showCursor()
            savePosition()
        } catch (_: Exception) {}
    }

    private fun posFile() = File(applicationContext.filesDir, "cursor_pos")

    private fun savePosition() {
        val p = params ?: return
        try {
            posFile().writeText("${p.x},${p.y}")
        } catch (_: Exception) {}
    }

    private fun loadPosition(): Pair<Int, Int>? {
        return try {
            val f = posFile()
            if (!f.exists()) return null
            val parts = f.readText().split(",")
            Pair(parts[0].toInt(), parts[1].toInt())
        } catch (_: Exception) { null }
    }

    private fun restorePosition() {
        val saved = loadPosition() ?: return
        getScreenSize()
        val maxX = screenW - currentSize
        val maxY = screenH - currentSize
        params?.x = saved.first.coerceIn(0, maxX)
        params?.y = saved.second.coerceIn(0, maxY)
    }

    fun showCursor() {
        cursorVisible = true
        cursorView?.visibility = View.VISIBLE
        resetInactivityTimer()
    }

    fun resetCursor() {
        createCursor()
        if (params == null) return
        getScreenSize()
        params!!.x = screenW / 2 - currentSize / 2
        params!!.y = screenH / 2 - currentSize / 2
        try {
            cursorView?.let { wm.updateViewLayout(it, params) }
            showCursor()
            savePosition()
        } catch (_: Exception) {}
    }

    fun getCursorPosition(): Pair<Float, Float> {
        getScreenSize()
        val p = params ?: return Pair(screenW / 2f, screenH / 2f)
        return Pair((p.x + currentSize / 2f).toFloat(), (p.y + currentSize / 2f).toFloat())
    }

    fun retry() {
        createCursor()
    }

    fun showSwipePath(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long) {
        if (!SettingsManager.get().showTrajectory) return
        inactivityHandler.removeCallbacks(trajectoryCleanup)
        if (trajectoryView == null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                !Settings.canDrawOverlays(this)) return
            val view = TrajectoryOverlayView(this)
            view.onEmpty = { inactivityHandler.post(trajectoryCleanup) }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            } else {
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                flags,
                PixelFormat.TRANSLUCENT
            )
            try {
                wm.addView(view, lp)
                trajectoryView = view
                trajectoryParams = lp
            } catch (_: Exception) { return }
        }
        trajectoryView?.showSwipe(x1, y1, x2, y2, duration)
    }

    override fun onDestroy() {
        instance = null
        inactivityHandler.removeCallbacks(inactivityRunnable)
        inactivityHandler.removeCallbacks(trajectoryCleanup)
        cursorView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        cursorView = null
        removeTrajectory()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
