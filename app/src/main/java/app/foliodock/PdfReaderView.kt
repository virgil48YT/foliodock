package app.foliodock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors

/**
 * Keeps at most three rasterized pages in memory. All renderer operations and close operations
 * run on one executor because PdfRenderer and its Page objects are not thread-safe.
 */
class PdfReaderView(context: Context, file: File, private val report: (String) -> Unit) : ScrollView(context), AutoCloseable {
    private val worker = Executors.newSingleThreadExecutor()
    private var renderer: PdfRenderer? = null
    private var descriptor: ParcelFileDescriptor? = null
    private val pages = mutableListOf<ImageView>()
    private val bitmaps = mutableMapOf<Int, Bitmap>()
    private val pending = mutableSetOf<Int>()
    @Volatile private var closed = false
    @Volatile private var visible = 0
    @Volatile private var generation = 0
    private val stack = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val density = resources.displayMetrics.density
    init {
        setBackgroundColor(Color.rgb(224, 226, 231))
        addView(stack, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        viewTreeObserver.addOnScrollChangedListener { updateVisible() }
        worker.execute {
            try {
                if (closed) return@execute
                descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                renderer = PdfRenderer(requireNotNull(descriptor))
                val count = requireNotNull(renderer).pageCount
                require(count in 1..2000) { "PDF previews support 1 to 2,000 pages." }
                val ratios = (0 until count).map { i -> requireNotNull(renderer).openPage(i).use { it.height.toFloat() / it.width.coerceAtLeast(1) } }
                post {
                    if (!closed) {
                        val pageWidth = (resources.displayMetrics.widthPixels - 28 * density).toInt().coerceAtLeast(200)
                        ratios.forEachIndexed { i, ratio ->
                            val label = TextView(context).apply {
                                text = "PAGE ${i + 1} / $count"
                                textSize = 10f
                                setTextColor(Color.DKGRAY)
                                setPadding((14 * density).toInt(), (14 * density).toInt(), 0, (6 * density).toInt())
                            }
                            stack.addView(label)
                            val image = ImageView(context).apply {
                                setBackgroundColor(Color.WHITE)
                                scaleType = ImageView.ScaleType.FIT_CENTER
                                contentDescription = "PDF page ${i + 1}"
                            }
                            pages.add(image)
                            stack.addView(image, LinearLayout.LayoutParams(pageWidth, (pageWidth * ratio).toInt().coerceIn(100, 18000)).apply {
                                setMargins((14 * density).toInt(), 0, (14 * density).toInt(), 0)
                            })
                        }
                        post { updateVisible() }
                    }
                }
            } catch (e: Exception) { post { if (!closed) report(e.message ?: "Unable to open PDF. It may be encrypted or damaged.") } }
        }
    }
    private fun updateVisible() {
        if (closed || pages.isEmpty()) return
        visible = pages.indexOfFirst { it.bottom >= scrollY }.coerceAtLeast(0)
        val keep = ((visible - 1).coerceAtLeast(0)..(visible + 1).coerceAtMost(pages.lastIndex)).toSet()
        bitmaps.keys.toList().filter { it !in keep }.forEach { i ->
            pages[i].setImageDrawable(null)
            // Android may still have queued drawing commands referencing the old bitmap.
            // Clearing references lets the framework/GC reclaim it safely.
            bitmaps.remove(i)
        }
        keep.forEach { i -> if (i !in bitmaps && pending.add(i)) render(i, generation) }
    }
    private fun render(index: Int, expectedGeneration: Int) {
        worker.execute {
            try {
                if (closed || expectedGeneration != generation || kotlin.math.abs(index - visible) > 1) {
                    post { pending.remove(index) }
                    return@execute
                }
                val r = renderer ?: return@execute
                val bitmap = r.openPage(index).use { page ->
                    val width = resources.displayMetrics.widthPixels.coerceIn(600, 1400)
                    val height = (width.toFloat() * page.height / page.width.coerceAtLeast(1)).toInt().coerceIn(1, 4000)
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(Color.WHITE)
                        page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
                post {
                    pending.remove(index)
                    if (!closed && expectedGeneration == generation && kotlin.math.abs(index - visible) <= 1) {
                        bitmaps[index] = bitmap
                        pages[index].setImageBitmap(bitmap)
                    } else bitmap.recycle()
                }
            } catch (e: Exception) {
                post { pending.remove(index); if (!closed) report("Cannot render page ${index + 1}: ${e.message ?: "invalid PDF"}") }
            }
        }
    }
    fun nextPage() { if (pages.isNotEmpty()) smoothScrollTo(0, pages[(visible + 1).coerceAtMost(pages.lastIndex)].top) }
    fun previousPage() { if (pages.isNotEmpty()) smoothScrollTo(0, pages[(visible - 1).coerceAtLeast(0)].top) }
    override fun close() {
        if (closed) return
        closed = true
        generation++
        pages.forEach { it.setImageDrawable(null) }
        bitmaps.clear()
        worker.execute {
            renderer?.close()
            descriptor?.close()
            renderer = null
            descriptor = null
        }
        worker.shutdown()
    }
}
