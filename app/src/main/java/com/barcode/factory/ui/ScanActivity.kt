package com.barcode.factory.ui

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.RectF
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.util.TypedValue
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.TorchState
import androidx.camera.view.PreviewView
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import com.barcode.factory.R
import com.barcode.factory.data.AppPrefs
import com.barcode.factory.data.ScanAreaOption
import com.barcode.factory.databinding.ActivityScanBinding
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Full-screen barcode scanner backed by CameraX + ML Kit.
 *
 * ML Kit reads camera frames directly. Detected bounding boxes are transformed
 * to PreviewView coordinates and filtered against the visible scan window.
 */
class ScanActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RESULT = "scan_result"
        const val EXTRA_RESULT_FORMAT = "scan_result_format"
        private const val TARGET_WIDTH = 1280
        private const val TARGET_HEIGHT = 720
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
    private lateinit var prefs: AppPrefs
    private var areaFraction: Float = 0.5f
    private var isLandscape: Boolean = false

    private val scanning = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)
    private val frameFailures = AtomicInteger(0)
    private var scanner: BarcodeScanner? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisExecutor: java.util.concurrent.ExecutorService? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) binding.previewView.doOnLayout { startCamera() }
        else { toast(getString(R.string.permission_camera_denied)); finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // CameraX requires COMPATIBLE mode for accurate OutputTransform mapping.
        binding.previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = AppPrefs(this)
        isLandscape =
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        applyScanArea()

        binding.tvScanHint.setText(
            if (isLandscape) R.string.scan_hint_landscape else R.string.scan_hint_portrait
        )

        binding.btnCloseScan.setOnClickListener { finish() }
        binding.btnTorch.setOnClickListener { toggleTorch() }
        binding.btnAreaConfig.setOnClickListener { showAreaConfigDialog() }

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
        if (granted) binding.previewView.doOnLayout { startCamera() } else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                if (isFinishing || isDestroyed || finished.get()) return@addListener
                val provider = future.get()
                cameraProvider = provider
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
                val viewport = binding.previewView.viewPort ?: error("相机预览尚未就绪")
                val group = UseCaseGroup.Builder().setViewPort(viewport)
                    .addUseCase(preview).addUseCase(analysis).build()
                val boundCamera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, group)
                camera = boundCamera
                binding.btnTorch.isEnabled = boundCamera.cameraInfo.hasFlashUnit()
                binding.btnTorch.alpha = if (boundCamera.cameraInfo.hasFlashUnit()) 1f else 0.4f
                boundCamera.cameraInfo.torchState.observe(this) { state ->
                    torchOn = state == TorchState.ON
                    binding.imgTorch.setImageResource(if (torchOn) R.drawable.ic_flash_off else R.drawable.ic_flash)
                    binding.imgTorch.setColorFilter(if (torchOn) 0xFFFFC107.toInt() else android.graphics.Color.WHITE)
                }
            } catch (t: Throwable) {
                toast(getString(R.string.camera_bind_failed, t.message ?: ""))
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.camera.core.ExperimentalGetImage
    private fun analyzeFrame(image: ImageProxy) {
        if (finished.get() || !scanning.compareAndSet(false, true)) { image.close(); return }
        try {
            val media = image.image ?: throw IllegalStateException("相机帧不可用")
            val client = scanner ?: throw IllegalStateException("识别器不可用")
            val source = ImageProxyTransformFactory().apply {
                // ML Kit's coordinates are in the rotated full image, not crop-local coordinates.
                isUsingCropRect = false
                isUsingRotationDegrees = true
            }.getOutputTransform(image)
            val input = InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees)
            client.process(input).addOnSuccessListener { barcodes ->
                if (finished.get() || isFinishing || isDestroyed) return@addOnSuccessListener
                val target = binding.previewView.outputTransform ?: return@addOnSuccessListener
                try {
                    val transform = CoordinateTransform(source, target)
                    val overlay = binding.overlayView
                    val left = 28f * resources.displayMetrics.density
                    val top = overlay.height * (1f - overlay.heightFraction) / 2f
                    val window = RectF(left, top, overlay.width - left, overlay.height - top)
                    val chosen = barcodes.mapNotNull { barcode ->
                        val bounds = barcode.boundingBox ?: return@mapNotNull null
                        if (barcode.rawValue.isNullOrBlank()) return@mapNotNull null
                        val rect = RectF(bounds)
                        transform.mapRect(rect)
                        if (!window.contains(rect.centerX(), rect.centerY())) return@mapNotNull null
                        val dx = rect.centerX() - window.centerX(); val dy = rect.centerY() - window.centerY()
                        barcode to (dx * dx + dy * dy)
                    }.minByOrNull { it.second }?.first
                    if (frameFailures.getAndSet(0) >= 3) {
                        binding.tvScanHint.setText(if (isLandscape) R.string.scan_hint_landscape else R.string.scan_hint_portrait)
                    }
                    if (chosen != null && finished.compareAndSet(false, true)) {
                        try { cameraProvider?.unbindAll() } catch (_: Exception) { /* finish still returns the result */ }
                        finishWithResult(chosen.rawValue!!, chosen.format)
                    }
                } catch (_: Exception) {
                    // Preview transforms may not be ready during startup or rotation; retry the next frame.
                    reportScanFailure()
                }
            }.addOnFailureListener { reportScanFailure() }.addOnCompleteListener {
                image.close()
                scanning.set(false)
            }
        } catch (_: Exception) {
            image.close()
            scanning.set(false)
            reportScanFailure()
        }
    }

    private fun reportScanFailure() {
        if (frameFailures.incrementAndGet() == 3) runOnUiThread {
            if (!isFinishing && !isDestroyed) binding.tvScanHint.text = "识别暂时失败，正在重试；也可退出后重新扫码"
        }
    }

    private fun applyScanArea() {
        areaFraction = if (isLandscape) prefs.scanAreaLandscape.heightFraction
        else prefs.scanAreaPortrait.heightFraction
        binding.overlayView.heightFraction = areaFraction
    }

    private fun showAreaConfigDialog() {
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(4))
        }

        fun addGroup(
            title: String,
            current: ScanAreaOption,
            onSelected: (ScanAreaOption) -> Unit
        ) {
            container.addView(TextView(this).apply {
                text = title
                setTextColor(ContextCompat.getColor(this@ScanActivity, R.color.text_primary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(0, dp(12), 0, dp(8))
            })
            val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
            ScanAreaOption.entries.forEach { option ->
                val radio = RadioButton(this).apply {
                    text = option.label
                    isChecked = option == current
                    setTextColor(ContextCompat.getColor(this@ScanActivity, R.color.text_primary))
                    setOnCheckedChangeListener { buttonView, checked ->
                        if (checked && buttonView.tag == option) onSelected(option)
                    }
                    tag = option
                }
                group.addView(radio, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(2) })
            }
            container.addView(group)
        }

        addGroup(
            getString(R.string.scan_area_portrait),
            prefs.scanAreaPortrait
        ) {
            prefs.scanAreaPortrait = it
            applyScanArea()
        }
        addGroup(
            getString(R.string.scan_area_landscape),
            prefs.scanAreaLandscape
        ) {
            prefs.scanAreaLandscape = it
            applyScanArea()
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.scan_area_title)
            .setMessage(R.string.scan_area_hint)
            .setView(scroll.also { it.addView(container) })
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private var torchOn = false
    private fun toggleTorch() {
        val boundCamera = camera ?: return
        if (!boundCamera.cameraInfo.hasFlashUnit()) { toast("当前相机没有闪光灯"); return }
        binding.btnTorch.isEnabled = false
        val future = boundCamera.cameraControl.enableTorch(!torchOn)
        future.addListener({
            if (!isDestroyed) {
                binding.btnTorch.isEnabled = true
                try { future.get() } catch (_: Exception) { toast("闪光灯切换失败") }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun finishWithResult(raw: String, format: Int) {
        playBeep()
        runOnUiThread {
            setResult(
                RESULT_OK,
                Intent()
                    .putExtra(EXTRA_RESULT, raw)
                    .putExtra(EXTRA_RESULT_FORMAT, format)
            )
            finish()
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

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
        finished.set(true)
        cameraProvider?.unbindAll()
        scanner?.close()
        scanner = null
        analysisExecutor?.shutdown()
        analysisExecutor = null
    }
}