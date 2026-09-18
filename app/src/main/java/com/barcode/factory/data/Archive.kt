package com.barcode.factory.data

data class Archive(
    val id: Long,
    val time: Long,
    val format: String,
    val values: List<String>
) {
    val count: Int get() = values.size
}
