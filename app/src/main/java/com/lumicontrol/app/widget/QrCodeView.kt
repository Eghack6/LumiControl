package com.lumicontrol.app.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

class QrCodeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var bitmap: Bitmap? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setData(data: String) {
        try {
            val size = 512
            val matrix = QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, size, size)
            val w = matrix.width
            val h = matrix.height
            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    pixels[y * w + x] = if (matrix[x, y]) 0xFF333333.toInt() else 0xFFFFFFFF.toInt()
                }
            }
            bitmap = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
            invalidate()
        } catch (_: Exception) {}
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        bitmap?.let { bm ->
            val padding = 16f
            val targetSize = minOf(measuredWidth, measuredHeight) - padding * 2
            if (targetSize <= 0) return
            val dest = Rect(
                padding.toInt(), padding.toInt(),
                (padding + targetSize).toInt(), (padding + targetSize).toInt()
            )
            canvas.drawBitmap(bm, null, dest, paint)
        }
    }
}
