package com.barcode.factory.ui

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.barcode.factory.databinding.ItemBarcodeBinding

class BarcodeAdapter(
    private val onCopy: (String) -> Unit
) : RecyclerView.Adapter<BarcodeAdapter.BarcodeViewHolder>() {

    private val values = mutableListOf<String>()
    private val bitmaps = mutableListOf<Bitmap?>()

    fun submit(newValues: List<String>, newBitmaps: List<Bitmap?>) {
        values.clear()
        values.addAll(newValues)
        bitmaps.clear()
        bitmaps.addAll(newBitmaps)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BarcodeViewHolder {
        val binding = ItemBarcodeBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return BarcodeViewHolder(binding)
    }

    override fun getItemCount(): Int = values.size

    override fun onBindViewHolder(holder: BarcodeViewHolder, position: Int) {
        holder.bind(values[position], bitmaps.getOrNull(position), onCopy)
    }

    class BarcodeViewHolder(
        private val binding: ItemBarcodeBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(value: String, bitmap: Bitmap?, onCopy: (String) -> Unit) {
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
            binding.btnCopyItem.setOnClickListener { onCopy(value) }
        }
    }
}
