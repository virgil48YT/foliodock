package app.foliodock

import org.junit.Assert.*
import org.junit.Test

class OfficeGeometryTest {
    @Test fun parsesWordPagesAndOffsets() {
        val pages = OfficeGeometry.parsePageRectangles(" 100, 200, 12000, 16000;100,16500,12000,16000; ")
        assertEquals(2, pages.size)
        assertEquals(16500L, pages[1].y)
        assertEquals("Page 2", pages[1].label)
    }
    @Test fun emptyRectanglesHaveNoPages() {
        assertTrue(OfficeGeometry.parsePageRectangles("").isEmpty())
    }
    @Test fun rejectsInvalidGeometry() {
        assertThrows(IllegalArgumentException::class.java) { OfficeGeometry.parsePageRectangles("0,0,0,15") }
        assertThrows(IllegalArgumentException::class.java) { OfficePage(Int.MAX_VALUE.toLong(), 0, 100, 100) }
        assertThrows(IllegalArgumentException::class.java) { OfficeGeometry.parsePageRectangles("1,2,3") }
    }
    @Test fun hugeSpreadsheetAllocatesOnlyViewportTiles() {
        val sheet = OfficePage(0, 0, 1_000_000_000, 1_000_000_000)
        val tiles = OfficeGeometry.visibleTiles(sheet, 3, 0.1, -500000.0, -900000.0, 1080, 1920, 0)
        assertTrue(tiles.size in 1..24)
        assertTrue(tiles.all { it.pixelsWide <= 513 && it.pixelsHigh <= 513 })
        assertTrue(tiles.all { it.x >= 5_000_000 - 5120 && it.y >= 9_000_000 - 5120 })
        assertTrue(tiles.all { it.key.page == 3 })
    }
    @Test fun edgeTilesDoNotExtendPastPage() {
        val page = OfficePage(0, 0, 900, 700)
        val tiles = OfficeGeometry.visibleTiles(page, 0, 1.0, 0.0, 0.0, 1000, 1000, 1)
        assertEquals(4, tiles.size)
        assertTrue(tiles.all { it.x + it.width <= page.width && it.y + it.height <= page.height })
    }
    @Test fun rasterScaleIsIndependentFromPanAndPinchScale() {
        val page = OfficePage(0, 0, 10000, 20000)
        val before = OfficeGeometry.visibleTiles(page, 0, 0.1, 0.0, 0.0, 500, 500, 2, 0.1).first()
        val after = OfficeGeometry.visibleTiles(page, 0, 0.11, 0.0, 0.0, 500, 500, 2, 0.1).first()
        assertEquals(before, after)
    }
    @Test fun viewportOutsideDocumentRequestsNoTiles() {
        assertTrue(OfficeGeometry.visibleTiles(OfficePage(0, 0, 500, 500), 0, 1.0, 1000.0, 1000.0, 300, 300, 0).isEmpty())
    }
    @Test fun tileCountHasHardCap() {
        assertEquals(64, OfficeGeometry.visibleTiles(OfficePage(0, 0, 1000000, 1000000), 0, 1.0, 0.0, 0.0, 100000, 100000, 0).size)
    }
    @Test fun offsetsClampAndCenter() {
        assertEquals(40.0, OfficeGeometry.clampOffset(99.0, 20.0, 100, 6.0), 0.001)
        assertEquals(6.0, OfficeGeometry.clampOffset(100.0, 200.0, 100, 6.0), 0.001)
        assertEquals(-106.0, OfficeGeometry.clampOffset(-500.0, 200.0, 100, 6.0), 0.001)
    }
}
