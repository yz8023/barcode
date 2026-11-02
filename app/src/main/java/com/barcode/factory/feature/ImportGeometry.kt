package com.barcode.factory.feature

import kotlin.math.abs

/** Pure geometry used by image import, kept Android-free for unit testing. */
internal object ImportGeometry {
    const val TILE = 1600
    private const val STEP = 1200
    private const val MAX_TILES = 1024

    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width get() = right - left
        val height get() = bottom - top
        val centerY get() = (top + bottom) / 2f
    }
    data class Hit(val value: String, val bounds: Box)

    fun positions(length: Int): List<Int> {
        require(length > 0) { "图片尺寸无效" }
        if (length <= TILE) return listOf(0)
        val count = ((length.toLong() - TILE + STEP - 1) / STEP + 1).toInt()
        require(count <= MAX_TILES) { "图片过大，请分割后重试" }
        return (0 until count).map { minOf(it.toLong() * STEP, length.toLong() - TILE).toInt() }.distinct()
    }
    fun tiles(width: Int, height: Int): Pair<List<Int>, List<Int>> {
        val xs = positions(width); val ys = positions(height)
        require(xs.size.toLong() * ys.size <= MAX_TILES) { "图片过大，请分割后重试" }
        return xs to ys
    }
    // EXIF orientations 1..8. The origin of the transformed tile in the full oriented image.
    fun offset(o: Int, x: Int, y: Int, r: Int, b: Int, w: Int, h: Int): Pair<Int, Int> = when (o) {
        2 -> (w - r) to y
        3 -> (w - r) to (h - b)
        4 -> x to (h - b)
        5 -> y to x
        6 -> (h - b) to x
        7 -> (h - b) to (w - r)
        8 -> y to (w - r)
        else -> x to y
    }
    fun samePhysicalCode(a: Box, b: Box): Boolean {
        val iw = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0f)
        val ih = (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0f)
        val smaller = minOf(a.width * a.height, b.width * b.height)
        return smaller > 0f && iw * ih / smaller >= .5f
    }
    fun readingOrder(hits: List<Hit>): List<Hit> {
        val remaining = hits.sortedBy { it.bounds.centerY }.toMutableList()
        val out = mutableListOf<Hit>()
        while (remaining.isNotEmpty()) {
            val first = remaining.removeAt(0)
            val row = mutableListOf(first)
            val iterator = remaining.iterator()
            while (iterator.hasNext()) {
                val hit = iterator.next()
                val tolerance = minOf(first.bounds.height, hit.bounds.height) * .5f
                if (abs(hit.bounds.centerY - first.bounds.centerY) <= tolerance) {
                    row.add(hit); iterator.remove()
                }
            }
            out.addAll(row.sortedBy { it.bounds.left })
        }
        return out
    }
    /** Preserve first occurrence and original blank lines; comparison matches input trimming. */
    fun deduplicateText(text: String): Pair<String, Int> {
        val originalLines = text.split(Regex("\\r\\n|\\n|\\r"))
        val seen = mutableSetOf<String>()
        var removed = 0
        val kept = originalLines.filter { line ->
            val value = line.trim()
            if (value.isEmpty() || seen.add(value)) true else { removed++; false }
        }
        return if (removed == 0) text to 0 else kept.joinToString("\n") to removed
    }
    fun lines(text: String): List<String> = text.split(Regex("\\r\\n|\\n|\\r"))
        .map { it.trim() }.filter { it.isNotEmpty() }
}
