package com.barcode.factory.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.barcode.factory.R
import com.barcode.factory.data.AppPrefs
import com.barcode.factory.databinding.ActivityScanBinding
import com.barcode.factory.util.TorchController
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full-screen barcode scanner backed by CameraX + ML Kit.
 *
 * The analyser crops a configurable central band (the same region the overlay
 * renders) before running ML Kit, so barcodes outside the recognition window
 * never reach the recogniser — preventing false reads when several codes are
 * on screen. The band fraction is stored per orientation (portrait / landscape).
 */
class ScanActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RESULT = "scan_result"
        private const val TARGET_WIDTH = 960
        private const val TARGET_HEIGHT = 540
        private const val ML_KIT_MAX_WIDTH = 720
        private val TARGET_FORMATS = intArrayOf(
            Barcode.FORMAT_QR_CODE,
            Barcode.FORMAT_DATA_MATRIX,
            Barcode.FORMAT_CODE_128,
            Barcode.FORMAT_CODE_39,
            Barcode.FORMAT_CODE_93,
            Barcode.FORMAT_CODABAR,
            Barcode.FORMAT_EAN_13,
            Barcode.FORMAT_EAN_8,
            Barcode.FORMAT_ITF,
            Barcode.FORMAT_UPC_A,
            Barcode.FORMAT_UPC_E
        )
    }

    private lateinit var binding: ActivityScanBinding
    private var areaFraction: Float = 0.5f

    private val scanning = AtomicBoolean(false)
    private var scanner: BarcodeScanner? = null
    private var torchHandler: TorchController? = null
    private var analysisExecutor: java.util.concurrent.ExecutorService? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else toast(getString(R.string.permission_camera_denied))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val prefs = AppPrefs(this)
        val landscape =
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        areaFraction = if (landscape) prefs.scanAreaLandscape.heightFraction
        else prefs.scanAreaPortrait.heightFraction
        binding.overlayView.heightFraction = areaFraction
        binding.tvScanHint.setText(
            if (landscape) R.string.scan_hint_landscape else R.string.scan_hint_portrait
        )

        binding.btnCloseScan.setOnClickListener { finish() }
        binding.btnTorch.setOnClickListener { toggleTorch() }

        scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(TARGET_FORMATS.first(), *TARGET_FORMATS.drop(1).toIntArray())
                .build()
        )
        analysisExecutor = Executors.newSingleThreadExecutor()

        requestCamera()
    }

    private fun requestCamera() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) startCamera() else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder()
                    .build()
                    .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }

                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(TARGET_WIDTH, TARGET_HEIGHT))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(analysisExecutor!!) { image ->
                        analyzeFrame(image)
                    } }

                provider.unbindAll()
                val camera = provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                )
                torchHandler = TorchController(camera)
            } catch (t: Throwable) {
                toast(getString(R.string.camera_bind_failed, t.message ?: ""))
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(image: ImageProxy) {
        if (scanning.get()) {
            image.close()
            return
        }
        val rotation = image.imageInfo.rotationDegrees
        val upright = runCatching { image.toBitmap() }
            .getOrNull()
            ?.let { rotateToUpright(it, rotation) }
        if (upright == null) {
            image.close()
            return
        }
        val band = cropCenterBand(upright)
        upright.recycle()
        if (band == null) {
            image.close()
            return
        }
        val scaled = scaleForMlKit(band)
        if (scaled !== band) band.recycle()
        image.close()

        if (!scanning.compareAndSet(false, true)) {
            scaled.recycle()
            return
        }

        val inputImage = InputImage.fromBitmap(scaled, 0)
        scanner?.process(inputImage)
            ?.addOnSuccessListener { barcodes ->
                val raw = barcodes.firstOrNull()?.rawValue
                if (!raw.isNullOrBlank()) finishWithResult(raw)
            }
            ?.addOnCompleteListener {
                scanning.set(false)
                scaled.recycle()
            }
    }

    private fun rotateToUpright(src: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return src
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        if (rotated !== src) src.recycle()
        return rotated
    }

    private fun cropCenterBand(src: Bitmap): Bitmap? {
        val bandHeight = (src.height * areaFraction).toInt().coerceAtLeast(1)
        if (bandHeight > src.height) return null
        val top = (src.height - bandHeight) / 2
        return runCatching {
            Bitmap.createBitmap(src, 0, top, src.width, bandHeight)
        }.getOrNull()
    }

    private fun scaleForMlKit(src: Bitmap): Bitmap {
        if (src.width <= ML_KIT_MAX_WIDTH) return src
        val ratio = ML_KIT_MAX_WIDTH.toFloat() / src.width
        return Bitmap.createScaledBitmap(src, ML_KIT_MAX_WIDTH, (src.height * ratio).toInt(), true)
    }

    private fun toggleTorch() {
        val handler = torchHandler ?: return
        torchOn = !torchOn
        binding.imgTorch.setImageResource(
            if (torchOn) R.drawable.ic_flash_off else R.drawable.ic_flash
        )
        binding.imgTorch.setColorFilter(
            if (torchOn) 0xFFFFC107.toInt() else android.graphics.Color.WHITE
        )
        handler.setTorch(torchOn)
    }

    private var torchOn = false

    private fun finishWithResult(raw: String) {
        playBeep()
        runOnUiThread {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT, raw))
            finish()
        }
    }

    private fun playBeep() {
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_SYSTEM, 90)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 120)
            Handler(Looper.getMainLooper()).postDelayed({ tone.release() }, 200)
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        torchHandler?.release()
        scanner?.close()
        scanner = null
        analysisExecutor?.shutdown()
        analysisExecutor = null
    }
}