package com.lumicontrol.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import com.google.gson.Gson
import java.io.File

data class AppSettings(
    val cursorColor: String = "#6366f1",
    val cursorSize: Int = 56,
    val cursorShape: String = "circle",
    val cursorOpacity: Int = 100,
    val inactivityTimeout: Int = 5,
    val sensitivity: Float = 2.5f,
    val tapToClick: Boolean = true,
    val scrollDirection: String = "normal",
    val autoScrollSpeed: Int = 5,
    val invertVertical: Boolean = false,
    val invertHorizontal: Boolean = false,
    val vibrationFeedback: Boolean = false,
    val keySound: Boolean = false,
    val autoStart: Boolean = true
)

object SettingsManager {
    private val gson = Gson()
    private var currentSettings = AppSettings()
    private var settingsFile: File? = null

    fun init(ctx: Context) {
        settingsFile = File(ctx.filesDir, "settings.json")
        File(ctx.filesDir, "cursors").mkdirs()
        load()
    }

    fun get(): AppSettings = currentSettings

    fun update(s: AppSettings): AppSettings {
        currentSettings = s
        save()
        applyToCursor()
        return currentSettings
    }

    fun buildCursorDrawable(ctx: Context): Drawable {
        val s = currentSettings
        val color = try {
            Color.parseColor(s.cursorColor)
        } catch (_: Exception) {
            0xE94560
        }
        val alpha = (s.cursorOpacity * 255 / 100).coerceIn(0, 255)
        val size = s.cursorSize.coerceIn(20, 120)

        return try {
            when (s.cursorShape) {
                "dot" -> buildDotDrawable(size, color, alpha)
                "arrow" -> buildArrowDrawable(ctx, size, color, alpha)
                "crosshair" -> buildCrosshairDrawable(size, color, alpha)
                "custom" -> buildCustomDrawable(ctx, size, alpha)
                else -> buildCircleDrawable(size, color, alpha)
            }
        } catch (_: Exception) {
            buildCircleDrawable(size, 0x6366F1, alpha)
        }
    }

    private fun buildCircleDrawable(size: Int, color: Int, alpha: Int): GradientDrawable {
        val strokeColor = (0xCCFFFFFF.toInt() and 0x00FFFFFF) or (alpha.coerceAtMost(200) shl 24)
        val fillColor = (color and 0x00FFFFFF) or (alpha * 66 / 100 shl 24)
        return GradientDrawable().apply {
            setShape(GradientDrawable.OVAL)
            setStroke(2, strokeColor)
            setColor(fillColor)
            setSize(size, size)
        }
    }

    private fun buildDotDrawable(size: Int, color: Int, alpha: Int): GradientDrawable {
        val fillColor = (color and 0x00FFFFFF) or (alpha shl 24)
        return GradientDrawable().apply {
            setShape(GradientDrawable.OVAL)
            setColor(fillColor)
            setSize(size, size)
        }
    }

    private fun buildArrowDrawable(ctx: Context, size: Int, color: Int, alpha: Int): BitmapDrawable {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = (color and 0x00FFFFFF) or (alpha shl 24)
            style = Paint.Style.FILL
        }
        val s = size.toFloat()
        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(s * 0.45f, 0f)
            lineTo(s * 0.35f, s * 0.15f)
            lineTo(s * 0.60f, s * 0.60f)
            lineTo(s * 0.45f, s * 0.70f)
            lineTo(s * 0.15f, s * 0.30f)
            lineTo(0f, s * 0.40f)
            close()
        }
        canvas.drawPath(path, paint)
        return BitmapDrawable(ctx.resources, bmp)
    }

    private fun buildCrosshairDrawable(size: Int, color: Int, alpha: Int): BitmapDrawable {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = (color and 0x00FFFFFF) or (alpha shl 24)
            style = Paint.Style.FILL
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = (0xCCFFFFFF.toInt() and 0x00FFFFFF) or (alpha.coerceAtMost(200) shl 24)
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
        }
        val cx = size / 2f
        val cy = size / 2f
        val r = size / 3.5f
        val line = size / 2.8f
        canvas.drawCircle(cx, cy, r, strokePaint)
        canvas.drawRect(cx - 1.5f, cy - line, cx + 1.5f, cy + line, paint)
        canvas.drawRect(cx - line, cy - 1.5f, cx + line, cy + 1.5f, paint)
        canvas.drawCircle(cx, cy, 3f, paint)
        return BitmapDrawable(bmp)
    }

    private fun buildCustomDrawable(ctx: Context, size: Int, alpha: Int): Drawable {
        val cursorDir = File(ctx.filesDir, "cursors")
        val file = File(cursorDir, "custom_cursor.png")
        if (!file.exists()) return buildCircleDrawable(size, 0x6366F1, alpha)

        val srcBitmap = BitmapFactory.decodeFile(file.absolutePath)
        if (srcBitmap == null) return buildCircleDrawable(size, 0x6366F1, alpha)

        val scaled = Bitmap.createScaledBitmap(srcBitmap, size, size, true)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.alpha = alpha }
        canvas.drawBitmap(scaled, 0f, 0f, paint)
        if (scaled != srcBitmap) srcBitmap.recycle()
        return BitmapDrawable(ctx.resources, bmp)
    }

    private fun applyToCursor() {
        CursorOverlayService.instance?.applySettings()
    }

    private fun load() {
        try {
            val file = settingsFile ?: return
            if (!file.exists()) return
            val text = file.readText()
            val s = gson.fromJson(text, AppSettings::class.java) ?: return
            currentSettings = s
        } catch (_: Exception) {}
    }

    private fun save() {
        try {
            val file = settingsFile ?: return
            file.writeText(gson.toJson(currentSettings))
        } catch (_: Exception) {}
    }
}
