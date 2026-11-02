package com.barcode.factory.feature

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class ImageBarcodeReaderTest {
    @Test fun readsLongImageAndMultipleImagesWithoutRepeatingOverlap() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val files = mutableListOf<File>()
        fun qr(value: String): Bitmap {
            val bits = MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, 300, 300)
            val pixels = IntArray(300 * 300) { i -> if (bits[i % 300, i / 300]) Color.BLACK else Color.WHITE }
            return Bitmap.createBitmap(pixels, 300, 300, Bitmap.Config.ARGB_8888)
        }
        fun save(bitmap: Bitmap): Uri {
            val file = File.createTempFile("import-test", ".png", context.cacheDir)
            files.add(file)
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
            return Uri.fromFile(file)
        }
        try {
            val long = Bitmap.createBitmap(600, 4000, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(long); canvas.drawColor(Color.WHITE)
            listOf("ONE", "TWO", "ONE").forEachIndexed { i, value ->
                val code = qr(value)
                canvas.drawBitmap(code, 100f, (500 + i * 1200).toFloat(), null)
                code.recycle()
            }
            val image1 = save(long)
            val image2 = save(qr("THREE"))
            val result = ImageBarcodeReader.read(context, listOf(image1, image2), AtomicBoolean(false)) { _, _ -> }
            assertTrue(result.failures.toString(), result.failures.isEmpty())
            assertEquals(listOf("ONE", "TWO", "ONE", "THREE"), result.values)
        } finally { files.forEach { it.delete() } }
    }
}
