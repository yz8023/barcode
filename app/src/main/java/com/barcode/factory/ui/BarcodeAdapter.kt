package com.barcode.factory.ui

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.barcode.factory.databinding.ItemBarcodeBinding

/**
 * Renders barcode preview items into a plain [LinearLayout] grid.
 *
 * The preview container lives inside a ScrollView, so we build the rows by hand
 * (2 columns) instead of a nested RecyclerView — nested RecyclerViews with
 * wrap_content height are not measured by the outer ScrollView and only lay out
 * the first screen-worth of items.
 */
class BarcodeAdapter(
    private val onCopy: (String) -> Unit,
    private val onSaveSingle: (Int) -> Unit
) {

    private var values: List<String> = emptyList()
    private var bitmaps: List<Bitmap?> = emptyList()

    val itemCount: Int
        get() = values.size

    fun submit(newValues: List<String>, newBitmaps: List<Bitmap?>) {
        values = newValues
        bitmaps = newBitmaps
    }

    /** Rebuilds the whole grid into [container]. Cheap enough for preview lists. */
    fun renderInto(container: LinearLayout) {
        container.removeAllViews()
        if (values.isEmpty()) return
        val inflater = LayoutInflater.from(container.context)
        val rows = (values.size + 1) / 2
        for (rowIndex in 0 until rows) {
            val row = LinearLayout(container.context).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            for (col in 0 until 2) {
                val index = rowIndex * 2 + col
                val binding = ItemBarcodeBinding.inflate(inflater, row, false)
                val cell = (binding.root.layoutParams as? LinearLayout.LayoutParams)
                    ?: LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                        binding.root.layoutParams = it
                    }
                cell.width = 0
                cell.weight = 1f
                row.addView(binding.root)

                if (index >= values.size) {
                    binding.root.visibility = View.INVISIBLE
                    continue
                }
                bindCell(binding, index)
            }
            container.addView(row)
        }
    }

    private fun bindCell(binding: ItemBarcodeBinding, index: Int) {
        val value = values[index]
        val bitmap = bitmaps.getOrNull(index)
        if (bitmap != null) {
            binding.barcodeImage.setImageBitmap(bitmap)
            binding.barcodeImage.visibility = View.VISIBLE
            binding.tvError.visibility = View.GONE
        } else {
            binding.barcodeImage.setImageDrawable(null)
            binding.barcodeImage.visibility = View.GONE
            binding.tvError.visibility = View.VISIBLE
        }
        binding.root.setOnClickListener { onCopy(value) }
        binding.root.setOnLongClickListener {
            onSaveSingle(index)
            true
        }
        binding.btnCopyItem.setOnClickListener { onCopy(value) }
    }
}