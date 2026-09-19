package com.barcode.factory.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Draws a dark scrim with a centered transparent recognition window plus white
 * corner brackets. The window height is a fraction of the view height, so the
 * visible window always matches the analyser's crop region.
 */
class ScanOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var heightFraction: Float = 0.5f
        set(value) {
            field = value.coerceIn(0.1f, 1f)
            invalidate()
        }

    private val scrimPaint = Paint().apply {
        color = Color.parseColor("#B3000000")
        style = Paint.Style.FILL
    }

    private val bracketPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dp(4f)
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
        isDither = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val windowW = width.toFloat() - dp(56f)
        val windowH = height.toFloat() * heightFraction
        val left = (width - windowW) / 2f
        val top = (height - windowH) / 2f
        val right = left + windowW
        val bottom = top + windowH

        val windowRect = android.graphics.RectF(left, top, right, bottom)
        val radius = dp(16f)

        // Scrim outside the window.
        val scrimPath = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            addRoundRect(windowRect, radius, radius, Path.Direction.CCW)
        }
        canvas.drawPath(scrimPath, scrimPaint)

        // Corner brackets.
        val len = min(windowW, windowH) * 0.16f + dp(8f)
        val inset = dp(2f)
        val c = bracketPaint

        // top-left
        canvas.drawLine(left + inset, top + inset + len, left + inset, top + inset, c)
        canvas.drawLine(left + inset, top + inset, left + inset + len, top + inset, c)
        // top-right
        canvas.drawLine(right - inset - len, top + inset, right - inset, top + inset, c)
        canvas.drawLine(right - inset, top + inset, right - inset, top + inset + len, c)
        // bottom-left
        canvas.drawLine(left + inset, bottom - inset - len, left + inset, bottom - inset, c)
        canvas.drawLine(left + inset, bottom - inset, left + inset + len, bottom - inset, c)
        // bottom-right
        canvas.drawLine(right - inset - len, bottom - inset, right - inset, bottom - inset, c)
        canvas.drawLine(right - inset, bottom - inset, right - inset, bottom - inset - len, c)
    }

    private fun dp(value: Float): Float {
        return value * resources.displayMetrics.density
    }
}