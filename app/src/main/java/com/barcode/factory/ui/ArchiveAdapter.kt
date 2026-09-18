package com.barcode.factory.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.barcode.factory.codec.BarcodeFormatSpec
import com.barcode.factory.data.Archive
import com.barcode.factory.databinding.ItemHistoryBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ArchiveAdapter(
    private val onLoad: (Archive) -> Unit,
    private val onDelete: (Archive) -> Unit
) : RecyclerView.Adapter<ArchiveAdapter.ArchiveViewHolder>() {

    private val items = mutableListOf<Archive>()
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    fun submit(newItems: List<Archive>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ArchiveViewHolder {
        val binding = ItemHistoryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ArchiveViewHolder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ArchiveViewHolder, position: Int) {
        holder.bind(items[position], timeFormat, onLoad, onDelete)
    }

    class ArchiveViewHolder(
        private val binding: ItemHistoryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(
            archive: Archive,
            timeFormat: SimpleDateFormat,
            onLoad: (Archive) -> Unit,
            onDelete: (Archive) -> Unit
        ) {
            binding.tvTime.text = timeFormat.format(Date(archive.time))
            binding.tvMeta.text = "${archive.count} 条 | ${BarcodeFormatSpec.fromKey(archive.format).label}"
            binding.root.setOnClickListener { onLoad(archive) }
            binding.btnDeleteArchive.setOnClickListener { onDelete(archive) }
        }
    }
}
