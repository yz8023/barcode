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
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.barcode.factory.R
import com.barcode.factory.codec.BarcodeFormatSpec
import com.barcode.factory.codec.BarcodeGenerator
import com.barcode.factory.data.AppPrefs
import com.barcode.factory.data.Archive
import com.barcode.factory.data.ColorOption
import com.barcode.factory.feature.ImportGeometry
import com.barcode.factory.databinding.ActivityMainBinding
import com.barcode.factory.util.FileExporter
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.mlkit.vision.barcode.common.Barcode
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
    private var scanLaunchPending = false
    private val clipboardExecutor = Executors.newSingleThreadExecutor()

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
        scanLaunchPending = false
        if (result.resultCode == Activity.RESULT_OK) {
            val values = result.data?.getStringArrayListExtra(ScanActivity.EXTRA_RESULT_LIST)
            if (!values.isNullOrEmpty()) {
                appendValues(values)
                toast("已从图片添加 ${values.size} 条数据；请检查当前条码格式")
                return@registerForActivityResult
            }
            val contents = result.data?.getStringExtra(ScanActivity.EXTRA_RESULT)
            if (contents.isNullOrBlank()) return@registerForActivityResult
            val format = result.data?.getIntExtra(ScanActivity.EXTRA_RESULT_FORMAT, -1) ?: -1
            appendScanned(contents, format)
        }
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
        adapter = BarcodeAdapter(
            onCopy = { value -> copySingle(value) },
            onSaveSingle = { index -> saveSingleBitmap(index) }
        )
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
        setupFormatChips()
        setupColumnChips()

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
    }

    private fun setupFormatChips() {
        val formats = BarcodeFormatSpec.values()
        val selected = BarcodeFormatSpec.fromKey(prefs.lastFormat)
        formats.forEach { format ->
            val chip = Chip(this).apply {
                text = format.label
                isCheckable = true
                isChecked = format == selected
                tag = format
            }
            binding.chipGroupFormat.addView(chip)
        }
        binding.chipGroupFormat.setOnCheckedStateChangeListener { group, checkedIds ->
            val chip = checkedIds.firstOrNull()
                ?.let { group.findViewById<Chip>(it) }
            val format = chip?.tag as? BarcodeFormatSpec ?: return@setOnCheckedStateChangeListener
            prefs.lastFormat = format.key
            scheduleGenerate(0)
        }
    }

    private fun setupColumnChips() {
        val options = listOf(1, 2, 3, 4, 6)
        val selected = options.indexOf(prefs.columns).takeIf { it >= 0 } ?: 0
        options.forEachIndexed { index, columns ->
            val chip = Chip(this).apply {
                text = columns.toString()
                isCheckable = true
                isChecked = index == selected
                tag = columns
            }
            binding.chipGroupColumns.addView(chip)
        }
        binding.chipGroupColumns.setOnCheckedStateChangeListener { group, checkedIds ->
            val chip = checkedIds.firstOrNull()
                ?.let { group.findViewById<Chip>(it) }
            val columns = chip?.tag as? Int ?: return@setOnCheckedStateChangeListener
            prefs.columns = columns
            scheduleGenerate(0)
        }
    }

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
        binding.btnManualAdd.setOnClickListener { readClipboard() }
        binding.btnDedupe.setOnClickListener { deduplicate() }
        binding.btnCopyAll.setOnClickListener { copyAll() }
        binding.btnMakeHd.setOnClickListener { withStoragePermission { makeHdImage() } }
        binding.btnZip.setOnClickListener { withStoragePermission { exportZip() } }
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
        get() {
            val id = binding.chipGroupFormat.checkedChipId
            return if (id == View.NO_ID) {
                BarcodeFormatSpec.fromKey(prefs.lastFormat)
            } else {
                binding.chipGroupFormat.findViewById<Chip>(id)?.tag as? BarcodeFormatSpec
                    ?: BarcodeFormatSpec.fromKey(prefs.lastFormat)
            }
        }

    private fun selectFormat(format: BarcodeFormatSpec) {
        for (i in 0 until binding.chipGroupFormat.childCount) {
            val chip = binding.chipGroupFormat.getChildAt(i) as? Chip ?: continue
            if (chip.tag == format) {
                chip.isChecked = true
                break
            }
        }
    }

    private val currentColor: Int
        get() = Color.parseColor(
            colorOptions.getOrElse(binding.spinnerColor.selectedItemPosition) { colorOptions[0] }.hex
        )

    private val currentColumns: Int
        get() {
            val id = binding.chipGroupColumns.checkedChipId
            return if (id == View.NO_ID) {
                prefs.columns
            } else {
                binding.chipGroupColumns.findViewById<Chip>(id)?.tag as? Int ?: prefs.columns
            }
        }

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
                adapter.renderInto(binding.previewGrid)
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
        if (scanLaunchPending) return
        scanLaunchPending = true
        launchScan()
    }

    private fun launchScan() {
        try {
            scanLauncher.launch(android.content.Intent(this, ScanActivity::class.java))
        } catch (t: Throwable) {
            scanLaunchPending = false
            toast("无法启动相机：${t.message}")
        }
    }

    private fun appendScanned(value: String, mlKitFormat: Int = -1) {
        val wasEmpty = currentValues().isEmpty()
        appendValues(listOf(value))
        val detected = matchFormatForScan(mlKitFormat)
        // A scan must not change the meaning of every existing entry.
        if (wasEmpty && detected != null) {
            prefs.lastFormat = detected.key
            selectFormat(detected)
            scheduleGenerate(0)
        }
        if (detected == null && mlKitFormat != -1)
            toast("已记录；此码制不支持生成，可复制数据使用")
        else if (!wasEmpty && detected != currentFormat)
            toast("已记录；扫描格式与当前格式不同，请检查格式")
        else toast(getString(R.string.scanned_added, value))
    }

    private fun matchFormatForScan(mlKitFormat: Int): BarcodeFormatSpec? = when (mlKitFormat) {
        Barcode.FORMAT_EAN_13 -> BarcodeFormatSpec.EAN13
        Barcode.FORMAT_CODE_128 -> BarcodeFormatSpec.CODE128
        Barcode.FORMAT_CODE_39 -> BarcodeFormatSpec.CODE39
        Barcode.FORMAT_UPC_A -> BarcodeFormatSpec.UPC
        Barcode.FORMAT_ITF -> BarcodeFormatSpec.ITF14
        else -> null
    }

    private fun appendValues(values: List<String>) {
        if (values.isEmpty()) return
        val current = binding.inputData.text?.toString().orEmpty()
        val incoming = values.joinToString("\n")
        val merged = if (current.isBlank()) incoming else current.trimEnd('\n', '\r') + "\n" + incoming
        binding.inputData.setText(merged)
        binding.inputData.setSelection(merged.length)
    }

    private fun readClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip == null || clip.itemCount == 0) { toast("剪贴板为空"); return }
        clipboardExecutor.execute {
            val text = try { clip.getItemAt(0).coerceToText(applicationContext)?.toString().orEmpty() }
                catch (_: Exception) { "" }
            val values = ImportGeometry.lines(text)
            handler.post {
                if (isFinishing || isDestroyed) return@post
                if (values.isEmpty()) toast("剪贴板中没有可读取的文本")
                else { appendValues(values); toast("已从剪贴板添加 ${values.size} 条数据") }
            }
        }
    }

    private fun deduplicate() {
        val original = binding.inputData.text?.toString().orEmpty()
        val (text, removed) = ImportGeometry.deduplicateText(original)
        if (removed == 0) { toast("没有重复数据"); return }
        binding.inputData.setText(text)
        binding.inputData.setSelection(text.length)
        toast("已去除 $removed 条重复数据，保留首次出现的内容")
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

    private fun saveSingleBitmap(index: Int) {
        val values = currentValues()
        val value = values.getOrNull(index) ?: return
        withStoragePermission {
            showLoading(true)
            executor.execute {
                val bitmap = BarcodeGenerator.render(
                    value, currentFormat,
                    BarcodeGenerator.RenderOptions(
                        moduleWidth = 3,
                        barHeight = 110,
                        color = currentColor,
                        showText = true,
                        textSizePx = 32f,
                        indexLabel = if (binding.switchIndex.isChecked) index + 1 else null,
                        paddingModules = 10
                    )
                )
                val saved = bitmap?.let {
                    FileExporter.saveBitmapToGallery(
                        this, it, "barcode_${System.currentTimeMillis()}.png"
                    )
                }
                handler.post {
                    showLoading(false)
                    if (saved != null) {
                        toast(getString(R.string.saved_single, value))
                    } else {
                        toast(getString(R.string.save_failed, "未知错误"))
                    }
                }
            }
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
        selectFormat(spec)
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
        clipboardExecutor.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
    }
}
