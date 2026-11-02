package com.barcode.factory.feature

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.media.ExifInterface
import android.net.Uri
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Reads each image sequentially in overlapping tiles, preserving distinct printed codes. */
object ImageBarcodeReader {
    data class Outcome(val values: List<String>, val failures: List<String>, val emptyImages: Int, val cancelled: Boolean)

    fun read(context: Context, uris: List<Uri>, cancelled: AtomicBoolean, progress: (Int, Int) -> Unit): Outcome {
        val scanner = BarcodeScanning.getClient()
        val values = mutableListOf<String>()
        val failures = mutableListOf<String>()
        var empty = 0
        try {
            for ((i, uri) in uris.withIndex()) {
                if (cancelled.get() || Thread.currentThread().isInterrupted) break
                progress(i + 1, uris.size)
                try {
                    val hits = readOne(context, uri, scanner, cancelled)
                    if (hits.isEmpty()) empty++ else values.addAll(ImportGeometry.readingOrder(hits).map { it.value })
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    cancelled.set(true)
                    break
                } catch (e: Exception) {
                    if (!cancelled.get()) failures.add("第 ${i + 1} 张：${e.message ?: "读取失败"}")
                } catch (_: OutOfMemoryError) {
                    failures.add("第 ${i + 1} 张：图片太大，内存不足")
                }
            }
        } finally { scanner.close() }
        return Outcome(values, failures, empty, cancelled.get())
    }

    private fun readOne(context: Context, uri: Uri, scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
                        cancelled: AtomicBoolean): List<ImportGeometry.Hit> {
        val orientation = try {
            context.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: error("无法打开图片")
        } catch (_: java.io.IOException) {
            // Some Android versions do not expose EXIF for every decodable image format.
            ExifInterface.ORIENTATION_NORMAL
        }
        val input = context.contentResolver.openInputStream(uri) ?: error("无法打开图片")
        input.use {
            val decoder = BitmapRegionDecoder.newInstance(it, false) ?: error("不支持此图片格式")
            try {
                val width = decoder.width; val height = decoder.height
                require(width > 0 && height > 0) { "图片尺寸无效" }
                val (xs, ys) = ImportGeometry.tiles(width, height)
                val hits = mutableListOf<ImportGeometry.Hit>()
                // An overview can recover a large barcode that happens to cross a tile seam.
                if (width > ImportGeometry.TILE || height > ImportGeometry.TILE) {
                    var sample = 1
                    while (maxOf(width, height) / sample > 2048 && sample < 32768) sample *= 2
                    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                    val overview = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                    if (overview != null) {
                        var oriented = overview
                        var handedOff = false
                        try {
                            val matrix = orientationMatrix(orientation)
                            if (matrix != null) oriented = Bitmap.createBitmap(overview, 0, 0, overview.width, overview.height, matrix, false)
                            if (oriented !== overview) overview.recycle()
                            val rotated = orientation in 5..8
                            val fullWidth = if (rotated) height else width
                            val fullHeight = if (rotated) width else height
                            val scaleX = fullWidth.toFloat() / oriented.width
                            val scaleY = fullHeight.toFloat() / oriented.height
                            handedOff = true
                            for (code in detect(scanner, oriented, cancelled)) {
                                val value = code.rawValue ?: continue
                                val box = code.boundingBox ?: continue
                                if (value.isBlank()) continue
                                hits.add(ImportGeometry.Hit(value, ImportGeometry.Box(
                                    box.left * scaleX, box.top * scaleY, box.right * scaleX, box.bottom * scaleY)))
                            }
                        } finally {
                            // detect() owns and releases oriented, including on timeout or cancellation.
                            if (!handedOff && !oriented.isRecycled) oriented.recycle()
                            if (oriented !== overview && !overview.isRecycled) overview.recycle()
                        }
                    }
                }
                for (y in ys) for (x in xs) {
                    if (cancelled.get() || Thread.currentThread().isInterrupted) throw InterruptedException()
                    val right = minOf(x + ImportGeometry.TILE, width); val bottom = minOf(y + ImportGeometry.TILE, height)
                    val raw = decoder.decodeRegion(Rect(x, y, right, bottom), BitmapFactory.Options())
                        ?: error("无法解码图片区域")
                    var bitmap = raw
                    var handedOff = false
                    try {
                        val matrix = orientationMatrix(orientation)
                        if (matrix != null) bitmap = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, false)
                        if (bitmap !== raw) raw.recycle()
                        handedOff = true
                        val barcodes = detect(scanner, bitmap, cancelled)
                        for (code in barcodes) {
                            val value = code.rawValue ?: continue
                            if (value.isBlank()) continue
                            val box = code.boundingBox ?: continue
                            val offset = ImportGeometry.offset(orientation, x, y, right, bottom, width, height)
                            val bounds = ImportGeometry.Box(box.left + offset.first.toFloat(), box.top + offset.second.toFloat(),
                                box.right + offset.first.toFloat(), box.bottom + offset.second.toFloat())
                            val duplicate = hits.any { it.value == value && ImportGeometry.samePhysicalCode(it.bounds, bounds) }
                            if (!duplicate) hits.add(ImportGeometry.Hit(value, bounds))
                        }
                    } finally {
                        if (!handedOff && !bitmap.isRecycled) bitmap.recycle()
                        if (bitmap !== raw && !raw.isRecycled) raw.recycle()
                    }
                }
                return hits
            } finally { decoder.recycle() }
        }
    }

    /** Takes ownership of the bitmap, including when the wait is interrupted. */
    private fun detect(scanner: com.google.mlkit.vision.barcode.BarcodeScanner, bitmap: Bitmap,
                       cancelled: AtomicBoolean): List<Barcode> {
        var task: Task<List<Barcode>>? = null
        try {
            val pending = scanner.process(InputImage.fromBitmap(bitmap, 0))
            task = pending
            val deadline = android.os.SystemClock.elapsedRealtime() + 30_000
            while (true) {
                if (cancelled.get() || Thread.currentThread().isInterrupted) throw InterruptedException()
                try { return Tasks.await(pending, 250, TimeUnit.MILLISECONDS) }
                catch (e: TimeoutException) {
                    if (android.os.SystemClock.elapsedRealtime() >= deadline) throw java.io.IOException("识别超时，请重试")
                }
            }
        } finally {
            val pending = task
            if (pending == null || pending.isComplete) bitmap.recycle()
            else pending.addOnCompleteListener { bitmap.recycle() }
        }
    }

    private fun orientationMatrix(o: Int): Matrix? {
        val m = Matrix()
        when (o) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> m.setValues(floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f))
            ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> m.setValues(floatArrayOf(0f, -1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f))
            ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(270f)
            else -> return null
        }
        return m
    }

}
