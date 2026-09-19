package com.barcode.factory.data

/**
 * Fixed presets for the scan recognition region height fraction.
 * Lower fraction = smaller recognition window = fewer false positives when multiple
 * barcodes are present in the frame.
 */
enum class ScanAreaOption(val key: String, val label: String, val heightFraction: Float) {
    COMPACT("compact", "紧凑（约 1/3）", 0.33f),
    STANDARD("standard", "标准（约 1/2）", 0.5f),
    WIDE("wide", "开阔（约 2/3）", 0.66f),
    MAXIMUM("maximum", "全屏", 1.0f);

    companion object {
        fun fromKey(key: String?): ScanAreaOption =
            entries.firstOrNull { it.key == key } ?: STANDARD
    }
}