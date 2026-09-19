package com.barcode.factory.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.barcode.factory.R
import com.barcode.factory.codec.BarcodeFormatSpec
import com.barcode.factory.codec.BarcodeGenerator
import com.barcode.factory.data.AppPrefs
import com.barcode.factory.data.Archive
import com.barcode.factory.data.ColorOption
import com.barcode.factory.data.ScanAreaOption
import com.barcode.factory.databinding.ActivityMainBinding
import com.barcode.factory.util.FileExporter
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: AppPrefs
    private lateinit var adapter: BarcodeAdapter
    private lateinit var archiveAdapter: ArchiveAdapter
    private lateinit var archiveList: MutableList<Archive>

    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var generateToken = 0
    private var pendingStorageAction: (() -> Unit)? = null
    private var progressDialog: AlertDialog? = null

    private val colorOptions = listOf(
        ColorOption("黑色", "#000000"),
        ColorOption("红色", "#FF3B30"),
        ColorOption("蓝色", "#007AFF"),
        ColorOption("绿色", "#34C759"),
        ColorOption("紫色", "#AF52DE")
    )

    private val scanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val contents = result.data?.getStringExtra(ScanActivity.EXTRA_RESULT)
            if (!contents.isNullOrBlank()) appendScanned(contents)
        }
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchScan()
        else toast("未授予相机权限，无法扫码")
    }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val action = pendingStorageAction
        pendingStorageAction = null
        if (granted) action?.invoke()
        else toast("未授予存储权限，无法保存文件")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = AppPrefs(this)

        setupPreview()
        setupArchives()
        setupSettings()
        setupInput()
        setupActions()
        setupDrawer()
        setupBackHandling()

        val draft = prefs.draft
        if (draft.isNotEmpty()) {
            binding.inputData.setText(draft.joinToString("\n"))
            binding.inputData.setSelection(binding.inputData.text?.length ?: 0)
        }
        scheduleGenerate(0)
        updateEmptyState()
    }

    // ---------------------------------------------------------------- setup

    private fun setupPreview() {
        adapter = BarcodeAdapter { value -> copySingle(value) }
        binding.previewGrid.layoutManager = GridLayoutManager(this, 2)
        binding.previewGrid.adapter = adapter
        binding.previewGrid.isNestedScrollingEnabled = false
    }

    private fun setupArchives() {
        archiveList = prefs.loadArchives()
        archiveAdapter = ArchiveAdapter(
            onLoad = { loadArchive(it) },
            onDelete = { deleteArchive(it) }
        )
        binding.historyList.layoutManager = LinearLayoutManager(this)
        binding.historyList.adapter = archiveAdapter
        archiveAdapter.submit(archiveList)
        updateArchiveEmpty()
    }

    private fun setupSettings() {
        val formatLabels = BarcodeFormatSpec.values().map { it.label }
        binding.spinnerFormat.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, formatLabels
        )
        binding.spinnerFormat.setSelection(BarcodeFormatSpec.fromKey(prefs.lastFormat).ordinal)
        binding.spinnerFormat.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.lastFormat = currentFormat.key
                scheduleGenerate(0)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        val colorLabels = colorOptions.map { it.name }
        binding.spinnerColor.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, colorLabels
        )
        val colorIndex = colorOptions.indexOfFirst { it.hex.equals(prefs.lastColor, true) }
            .takeIf { it >= 0 } ?: 0
        binding.spinnerColor.setSelection(colorIndex)
        binding.spinnerColor.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.lastColor = colorOptions[position].hex
                scheduleGenerate(0)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        binding.switchIndex.isChecked = prefs.showIndex
        binding.switchIndex.setOnCheckedChangeListener { _, checked ->
            prefs.showIndex = checked
            scheduleGenerate(0)
        }

        binding.inputCols.setText(prefs.columns.toString())
        binding.inputCols.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                prefs.columns = currentColumns
            }
        })

        refreshScanAreaSummary()
    }

    private fun refreshScanAreaSummary() {
        binding.tvScanAreaSummary.text = getString(
            R.string.scan_area_summary,
            prefs.scanAreaPortrait.label,
            prefs.scanAreaLandscape.label
        )
    }

    private fun showScanAreaDialog() {
        val portrait = prefs.scanAreaPortrait
        val landscape = prefs.scanAreaLandscape

        val scroll = ScrollView(this).apply {
            isFillViewport = true
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(4))
        }

        fun addGroup(
            title: String,
            current: ScanAreaOption,
            onSelected: (ScanAreaOption) -> Unit
        ): RadioGroup {
            val label = TextView(this@MainActivity).apply {
                text = title
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(0, dp(12), 0, dp(8))
            }
            container.addView(label)
            val group = RadioGroup(this@MainActivity).apply {
                orientation = RadioGroup.VERTICAL
            }
            ScanAreaOption.entries.forEach { option ->
                val radio = RadioButton(this@MainActivity).apply {
                    text = option.label
                    isChecked = option == current
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                    setOnCheckedChangeListener { buttonView, isChecked ->
                        if (isChecked && buttonView.tag == option) onSelected(option)
                    }
                    tag = option
                }
                group.addView(radio, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(2) })
            }
            container.addView(group)
            return group
        }

        addGroup(getString(R.string.scan_area_portrait), portrait) {
            prefs.scanAreaPortrait = it
        }
        addGroup(getString(R.string.scan_area_landscape), landscape) {
            prefs.scanAreaLandscape = it
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.scan_area_title)
            .setMessage(R.string.scan_area_hint)
            .setView(scroll.also { it.addView(container) })
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("完成") { _, _ -> refreshScanAreaSummary() }
            .show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun setupInput() {
        binding.inputData.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                prefs.draft = currentValues()
                scheduleGenerate()
            }
        })
    }

    private fun setupActions() {
        binding.btnScan.setOnClickListener { startScan() }
        binding.btnManualAdd.setOnClickListener { showManualAddDialog() }
        binding.btnCopyAll.setOnClickListener { copyAll() }
        binding.btnMakeHd.setOnClickListener { withStoragePermission { makeHdImage() } }
        binding.btnZip.setOnClickListener { withStoragePermission { exportZip() } }
        binding.btnScanArea.setOnClickListener { showScanAreaDialog() }
    }

    private fun setupDrawer() {
        binding.btnHistory.setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }
        binding.btnCloseDrawer.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        }
        binding.btnSaveCurrent.setOnClickListener { saveCurrentArchive() }
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    // ------------------------------------------------------------ state

    private fun currentValues(): List<String> =
        binding.inputData.text?.toString()
            ?.split("\n")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    private val currentFormat: BarcodeFormatSpec
        get() = BarcodeFormatSpec.values().getOrElse(binding.spinnerFormat.selectedItemPosition) {
            BarcodeFormatSpec.EAN13
        }

    private val currentColor: Int
        get() = Color.parseColor(
            colorOptions.getOrElse(binding.spinnerColor.selectedItemPosition) { colorOptions[0] }.hex
        )

    private val currentColumns: Int
        get() = binding.inputCols.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 1

    // ---------------------------------------------------------- rendering

    private val generateRunnable = Runnable { generate() }

    private fun scheduleGenerate(delay: Long = 250) {
        handler.removeCallbacks(generateRunnable)
        handler.postDelayed(generateRunnable, delay)
    }

    private fun generate() {
        val values = currentValues()
        val format = currentFormat
        val color = currentColor
        val showIndex = binding.switchIndex.isChecked
        val token = ++generateToken
        executor.execute {
            val bitmaps = values.mapIndexed { index, value ->
                BarcodeGenerator.render(
                    value, format,
                    BarcodeGenerator.RenderOptions(
                        moduleWidth = 2,
                        barHeight = 60,
                        color = color,
                        showText = true,
                        textSizePx = 22f,
                        indexLabel = if (showIndex) index + 1 else null
                    )
                )
            }
            handler.post {
                if (token != generateToken) return@post
                adapter.submit(values, bitmaps)
                binding.badge.text = values.size.toString()
                updateEmptyState()
            }
        }
    }

    private fun updateEmptyState() {
        binding.tvEmpty.visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE
    }

    // -------------------------------------------------------------- scan

    private fun startScan() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) launchScan()
        else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun launchScan() {
        try {
            scanLauncher.launch(android.content.Intent(this, ScanActivity::class.java))
        } catch (t: Throwable) {
            toast("无法启动相机：${t.message}")
        }
    }

    private fun appendScanned(value: String) {
        val current = binding.inputData.text?.toString().orEmpty()
        val merged = if (current.isBlank()) value else current.trimEnd('\n') + "\n" + value
        binding.inputData.setText(merged)
        binding.inputData.setSelection(merged.length)
        toast(getString(R.string.scanned_added, value))
    }

    private fun showManualAddDialog() {
        val input = EditText(this).apply {
            hint = "输入条码内容"
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("手动添加")
            .setView(input)
            .setPositiveButton("添加") { _, _ ->
                val value = input.text.toString().trim()
                if (value.isNotEmpty()) appendScanned(value)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // -------------------------------------------------------------- copy

    private fun copySingle(value: String) {
        copyToClipboard(value, getString(R.string.copied_single, value))
    }

    private fun copyAll() {
        val values = currentValues()
        if (values.isEmpty()) {
            toast(getString(R.string.copy_empty))
            return
        }
        copyToClipboard(values.joinToString("\n"), getString(R.string.copied_all, values.size))
    }

    private fun copyToClipboard(text: String, message: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("barcode", text))
        toast(message)
    }

    // ------------------------------------------------------------ export

    private fun withStoragePermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            action()
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            action()
        } else {
            pendingStorageAction = action
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun makeHdImage() {
        val values = currentValues()
        if (values.isEmpty()) {
            toast(getString(R.string.copy_empty))
            return
        }
        val columns = currentColumns
        val format = currentFormat
        val color = currentColor
        val showIndex = binding.switchIndex.isChecked
        showLoading(true)
        executor.execute {
            val bitmaps = values.mapIndexed { index, value ->
                BarcodeGenerator.render(
                    value, format,
                    BarcodeGenerator.RenderOptions(
                        moduleWidth = 3,
                        barHeight = 110,
                        color = color,
                        showText = true,
                        textSizePx = 32f,
                        indexLabel = if (showIndex) index + 1 else null,
                        paddingModules = 10
                    )
                )
            }.filterNotNull()

            if (bitmaps.isEmpty()) {
                handler.post {
                    showLoading(false)
                    toast(getString(R.string.format_error))
                }
                return@execute
            }

            val cellWidth = bitmaps.maxOf { it.width }
            val cellHeight = bitmaps.maxOf { it.height }
            val rows = (bitmaps.size + columns - 1) / columns
            val output = Bitmap.createBitmap(
                cellWidth * columns, cellHeight * rows, Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(output)
            canvas.drawColor(Color.WHITE)
            bitmaps.forEachIndexed { index, bitmap ->
                val left = (index % columns) * cellWidth + (cellWidth - bitmap.width) / 2f
                val top = (index / columns) * cellHeight + (cellHeight - bitmap.height) / 2f
                canvas.drawBitmap(bitmap, left, top, null)
            }

            val saved = FileExporter.saveBitmapToGallery(
                this, output, "barcode_hd_${System.currentTimeMillis()}.png"
            )
            handler.post {
                showLoading(false)
                if (saved != null) {
                    binding.resImg.setImageBitmap(output)
                    binding.resultArea.visibility = View.VISIBLE
                    binding.scrollView.post {
                        binding.scrollView.smoothScrollTo(0, binding.resultArea.top)
                    }
                    toast(getString(R.string.hd_ready))
                } else {
                    toast(getString(R.string.save_failed, "未知错误"))
                }
            }
        }
    }

    private fun exportZip() {
        val values = currentValues()
        if (values.isEmpty()) {
            toast(getString(R.string.copy_empty))
            return
        }
        val format = currentFormat
        val color = currentColor
        val showIndex = binding.switchIndex.isChecked
        showLoading(true)
        executor.execute {
            val buffer = ByteArrayOutputStream()
            var count = 0
            try {
                ZipOutputStream(buffer).use { zip ->
                    values.forEachIndexed { index, value ->
                        val bitmap = BarcodeGenerator.render(
                            value, format,
                            BarcodeGenerator.RenderOptions(
                                moduleWidth = 2,
                                barHeight = 90,
                                color = color,
                                showText = true,
                                textSizePx = 26f,
                                indexLabel = if (showIndex) index + 1 else null
                            )
                        ) ?: return@forEachIndexed
                        val entry = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, entry)
                        zip.putNextEntry(ZipEntry("bc_${index + 1}.png"))
                        zip.write(entry.toByteArray())
                        zip.closeEntry()
                        count++
                    }
                }
            } catch (ignore: Throwable) {
                // handled by the count check below
            }

            val saved = if (count > 0) {
                FileExporter.saveBytesToDownloads(
                    this,
                    buffer.toByteArray(),
                    "Batch_Barcodes_${System.currentTimeMillis()}.zip",
                    "application/zip"
                )
            } else {
                null
            }
            handler.post {
                showLoading(false)
                if (saved != null) {
                    toast(getString(R.string.zip_saved, saved))
                } else {
                    toast(getString(R.string.save_failed, "未知错误"))
                }
            }
        }
    }

    // ----------------------------------------------------------- archives

    private fun saveCurrentArchive() {
        val values = currentValues()
        if (values.isEmpty()) {
            toast(getString(R.string.copy_empty))
            return
        }
        val now = System.currentTimeMillis()
        archiveList.add(0, Archive(now, now, currentFormat.key, values))
        while (archiveList.size > 30) archiveList.removeAt(archiveList.size - 1)
        prefs.saveArchives(archiveList)
        archiveAdapter.submit(archiveList)
        updateArchiveEmpty()
        toast(getString(R.string.saved_archive))
    }

    private fun loadArchive(archive: Archive) {
        binding.inputData.setText(archive.values.joinToString("\n"))
        binding.inputData.setSelection(binding.inputData.text?.length ?: 0)
        val spec = BarcodeFormatSpec.fromKey(archive.format)
        binding.spinnerFormat.setSelection(spec.ordinal)
        prefs.lastFormat = spec.key
        binding.drawerLayout.closeDrawer(GravityCompat.START)
        scheduleGenerate(0)
    }

    private fun deleteArchive(archive: Archive) {
        archiveList.removeAll { it.id == archive.id }
        prefs.saveArchives(archiveList)
        archiveAdapter.submit(archiveList)
        updateArchiveEmpty()
    }

    private fun updateArchiveEmpty() {
        binding.tvEmptyArchive.visibility =
            if (archiveList.isEmpty()) View.VISIBLE else View.GONE
    }

    // -------------------------------------------------------------- misc

    private fun showLoading(show: Boolean) {
        if (show) {
            if (progressDialog == null) {
                val container = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(64, 64, 64, 64)
                    addView(ProgressBar(this@MainActivity))
                    addView(TextView(this@MainActivity).apply {
                        text = getString(R.string.generating)
                        gravity = Gravity.CENTER
                        setPadding(0, 32, 0, 0)
                    })
                }
                progressDialog = AlertDialog.Builder(this)
                    .setView(container)
                    .setCancelable(false)
                    .create()
            }
            progressDialog?.show()
        } else {
            progressDialog?.dismiss()
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
    }
}
