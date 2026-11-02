package com.barcode.factory.feature

import org.junit.Assert.*
import org.junit.Test

class ImportGeometryTest {
    @Test fun longImageTilesOverlapAndCoverTheWholeImage() {
        val (xs, ys) = ImportGeometry.tiles(1080, 12000)
        assertEquals(listOf(0), xs)
        assertEquals(0, ys.first())
        assertEquals(12000 - 1600, ys.last())
        ys.zipWithNext().forEach { (a, b) -> assertTrue(b > a && b - a <= 1200) }
    }
    @Test fun hugeImageFailsExplicitlyRatherThanTruncating() {
        try { ImportGeometry.tiles(1_400_000, 500); fail("expected limit") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun exifRotationsHaveCorrectGlobalTileOrigin() {
        val expected = listOf(10 to 20, 700 to 20, 700 to 1600, 10 to 1600,
            20 to 10, 1600 to 10, 1600 to 700, 20 to 700)
        for (o in 1..8) assertEquals(expected[o - 1], ImportGeometry.offset(o, 10, 20, 300, 400, 1000, 2000))
    }
    @Test fun overlapDoesNotDeleteDistinctOccurrences() {
        val a = ImportGeometry.Box(10f, 10f, 110f, 50f)
        val overlapping = ImportGeometry.Box(12f, 12f, 112f, 52f)
        val other = ImportGeometry.Box(10f, 300f, 110f, 340f)
        assertTrue(ImportGeometry.samePhysicalCode(a, overlapping))
        assertFalse(ImportGeometry.samePhysicalCode(a, other))
    }
    @Test fun gridIsOrderedByRowsThenColumns() {
        fun hit(value: String, x: Float, y: Float) = ImportGeometry.Hit(value, ImportGeometry.Box(x,y,x+50,y+30))
        val input = listOf(hit("D",100f,80f), hit("B",100f,3f), hit("C",0f,80f), hit("A",0f,0f))
        assertEquals(listOf("A", "B", "C", "D"), ImportGeometry.readingOrder(input).map { it.value })
    }
    @Test fun textLinesHandleCommonLineEndingsAndKeepRepeatedValues() {
        assertEquals(listOf("a", "b", "a", "c"), ImportGeometry.lines(" a\r\nb\ra\n \n c "))
    }
}
