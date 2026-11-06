package app.foliodock

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Twip geometry stays independent of Android and the native engine so it can be tested. */
data class OfficePage(
    val x: Long,
    val y: Long,
    val width: Long,
    val height: Long,
    val part: Int = 0,
    val label: String = "Page"
) {
    init {
        require(x >= 0 && y >= 0 && width > 0 && height > 0) { "Invalid page geometry." }
        require(x <= Int.MAX_VALUE && y <= Int.MAX_VALUE &&
            width <= Int.MAX_VALUE.toLong() - x && height <= Int.MAX_VALUE.toLong() - y) {
            "Document coordinates exceed the renderer limit."
        }
    }
}

data class OfficeTileKey(val page: Int, val level: Int, val column: Int, val row: Int)
data class OfficeTile(val key: OfficeTileKey, val x: Long, val y: Long, val width: Int, val height: Int, val pixelsWide: Int, val pixelsHigh: Int)

object OfficeGeometry {
    const val TILE_PIXELS = 512
    const val MAX_VISIBLE_TILES = 64
    fun parsePageRectangles(value: String): List<OfficePage> {
        if (value.isBlank()) return emptyList()
        return value.split(';').filter { it.isNotBlank() }.take(10000).mapIndexed { index, rectangle ->
            val n = rectangle.trim().split(',').map { it.trim().toLong() }
            require(n.size == 4) { "Unexpected native page geometry." }
            OfficePage(n[0], n[1], n[2], n[3], label = "Page ${index + 1}")
        }
    }
    /**
     * Enumerate only intersecting tiles. pixel offsets are the location of the page's top-left,
     * not scroll coordinates; scale is pixels per twip. Tile keys include a quantized zoom level.
     */
    fun visibleTiles(page: OfficePage, pageIndex: Int, scale: Double, offsetX: Double, offsetY: Double,
                     viewportWidth: Int, viewportHeight: Int, level: Int, rasterScale: Double = scale): List<OfficeTile> {
        if (viewportWidth <= 0 || viewportHeight <= 0 || !scale.isFinite() || scale <= 0) return emptyList()
        if (!rasterScale.isFinite() || rasterScale <= 0) return emptyList()
        val span = max(1L, floor(TILE_PIXELS / rasterScale).toLong())
        val left = max(0.0, -offsetX / scale)
        val top = max(0.0, -offsetY / scale)
        val right = min(page.width.toDouble(), (viewportWidth - offsetX) / scale)
        val bottom = min(page.height.toDouble(), (viewportHeight - offsetY) / scale)
        if (right <= left || bottom <= top) return emptyList()
        val firstColumn = floor(left / span).toInt().coerceAtLeast(0)
        val firstRow = floor(top / span).toInt().coerceAtLeast(0)
        val lastColumn = floor((right - 0.001) / span).toInt()
        val lastRow = floor((bottom - 0.001) / span).toInt()
        val result = mutableListOf<OfficeTile>()
        loop@ for (r in firstRow..lastRow) for (c in firstColumn..lastColumn) {
            if (result.size >= MAX_VISIBLE_TILES) break@loop
            val x = c * span
            val y = r * span
            val tw = min(span, page.width - x).toInt()
            val th = min(span, page.height - y).toInt()
            if (tw > 0 && th > 0) result += OfficeTile(
                OfficeTileKey(pageIndex, level, c, r), x, y, tw, th,
                ceil(tw * rasterScale).toInt().coerceIn(1, TILE_PIXELS + 1),
                ceil(th * rasterScale).toInt().coerceIn(1, TILE_PIXELS + 1)
            )
        }
        return result
    }
    fun clampOffset(offset: Double, pagePixels: Double, viewport: Int, margin: Double): Double =
        if (pagePixels + margin * 2 <= viewport) (viewport - pagePixels) / 2
        else offset.coerceIn(viewport - pagePixels - margin, margin)
}
