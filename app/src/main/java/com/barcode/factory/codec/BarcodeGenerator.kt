package com.barcode.factory.codec

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import kotlin.math.max

/**
 * Native barcode encoder/renderer.
 *
 * Standard symbologies are produced with ZXing. Pharmacode and MSI are ported from
 * JsBarcode 3.11.5 so the rendered output matches the original web application.
 */
object BarcodeGenerator {

    data class RenderOptions(
        val moduleWidth: Int = 2,
        val barHeight: Int = 70,
        val color: Int = Color.BLACK,
        val showText: Boolean = true,
        val textSizePx: Float = 24f,
        val indexLabel: Int? = null,
        val paddingModules: Int = 8
    )

    /** Returns the module bitmap (true = bar) or null when the value is invalid for the format. */
    fun encode(value: String, format: BarcodeFormatSpec): BooleanArray? {
        val v = value.trim()
        if (v.isEmpty()) return null
        return when (format) {
            BarcodeFormatSpec.PHARMACODE -> encodePharmacode(v)
            BarcodeFormatSpec.MSI -> encodeMsi(v)
            else -> encodeZxing(v, format)
        }
    }

    private fun encodeZxing(value: String, format: BarcodeFormatSpec): BooleanArray? {
        val zxingFormat = when (format) {
            BarcodeFormatSpec.EAN13 -> BarcodeFormat.EAN_13
            BarcodeFormatSpec.CODE128 -> BarcodeFormat.CODE_128
            BarcodeFormatSpec.CODE39 -> BarcodeFormat.CODE_39
            BarcodeFormatSpec.UPC -> BarcodeFormat.UPC_A
            BarcodeFormatSpec.ITF14 -> BarcodeFormat.ITF
            else -> return null
        }
        return try {
            val matrix = MultiFormatWriter().encode(value, zxingFormat, 1, 1)
            BooleanArray(matrix.width) { x -> matrix.get(x, 0) }
        } catch (t: Throwable) {
            null
        }
    }

    /** Port of JsBarcode src/barcodes/pharmacode/index.js */
    private fun encodePharmacode(value: String): BooleanArray? {
        val number = value.toIntOrNull() ?: return null
        if (number < 3 || number > 131070) return null
        var z = number
        val sb = StringBuilder()
        while (z != 0) {
            if (z % 2 == 0) {
                sb.insert(0, "11100")
                z = (z - 2) / 2
            } else {
                sb.insert(0, "100")
                z = (z - 1) / 2
            }
        }
        val bits = if (sb.length > 2) sb.substring(0, sb.length - 2) else ""
        return bits.map { it == '1' }.toBooleanArray()
    }

    /** Port of JsBarcode src/barcodes/MSI/MSI.js */
    private fun encodeMsi(value: String): BooleanArray? {
        if (!value.matches(Regex("^[0-9]+$"))) return null
        val sb = StringBuilder("110")
        for (c in value) {
            val digit = c - '0'
            val bin = digit.toString(2).padStart(4, '0')
            for (b in bin) {
                sb.append(if (b == '0') "100" else "110")
            }
        }
        sb.append("1001")
        return sb.map { it == '1' }.toBooleanArray()
    }

    fun render(value: String, format: BarcodeFormatSpec, options: RenderOptions): Bitmap? {
        val modules = encode(value, format) ?: return null
        if (modules.isEmpty()) return null

        val mw = max(1, options.moduleWidth)
        val pad = max(0, options.paddingModules) * mw
        val barWidth = modules.size * mw
        val textHeight = if (options.showText) (options.textSizePx * 1.8f).toInt() else 0
        val width = barWidth + pad * 2
        val height = pad + options.barHeight + textHeight + pad

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val barPaint = Paint().apply {
            color = options.color
            isAntiAlias = false
        }
        var x = pad.toFloat()
        val top = pad.toFloat()
        val bottom = (pad + options.barHeight).toFloat()
        for (module in modules) {
            if (module) canvas.drawRect(x, top, x + mw, bottom, barPaint)
            x += mw
        }

        if (options.showText) {
            val textPaint = Paint().apply {
                color = options.color
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
                textSize = options.textSizePx
            }
            val baseline = bottom + options.textSizePx * 1.25f
            canvas.drawText(value.trim(), (width / 2f), baseline, textPaint)
        }

        options.indexLabel?.let { index ->
            val label = "#$index"
            val labelPaint = Paint().apply {
                color = Color.parseColor("#FF3B30")
                isAntiAlias = true
                textSize = options.textSizePx * 0.95f
                typeface = Typeface.DEFAULT_BOLD
            }
            val textWidth = labelPaint.measureText(label)
            val box = RectF(
                top + 2f,
                top + 2f,
                top + textWidth + 14f,
                top + labelPaint.textSize * 1.5f
            )
            val boxPaint = Paint().apply {
                color = Color.argb(230, 255, 255, 255)
                isAntiAlias = true
            }
            canvas.drawRoundRect(box, 6f, 6f, boxPaint)
            canvas.drawText(label, top + 8f, top + labelPaint.textSize * 1.15f, labelPaint)
        }

        return bitmap
    }
}
