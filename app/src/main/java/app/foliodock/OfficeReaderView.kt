package app.foliodock

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.content.res.ColorStateList
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.libreoffice.kit.Document
import java.io.File
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Original FolioDock read-only tiled LibreOfficeKit reader.
 *
 * Touch gestures transform the viewport only. No mouse events, keyboard input, editing UNO
 * commands, script commands, or save operations are ever delivered to the document.
 */
class OfficeReaderView(
    private val activity: Activity,
    file: File,
    private val onFailure: (String) -> Unit
) : FrameLayout(activity), AutoCloseable {
    private val density = resources.displayMetrics.density
    private val baseScale = density.toDouble() / 15.0 // 96 logical dpi / 1,440 twips per inch
    private val margin = 6.0 * density
    private var session: OfficeRuntime.Session? = null
    private var info: OfficeInfo? = null
    private var page: OfficePage? = null
    private var pageIndex = 0
    private var scale = baseScale
    private var offsetX = margin
    private var offsetY = margin
    private var positioned = false
    private var generation = 0
    private var rasterLevel: Int? = null
    private var closed = false
    private var failed = false
    private var ready = false
    private var nightMode = false
    private var pageLoading = true
    private val pending = mutableSetOf<OfficeTileKey>()
    private data class Cached(val tile: OfficeTile, val bitmap: Bitmap)
    private val cache = LinkedHashMap<OfficeTileKey, Cached>(32, 0.75f, true)
    private val surface = PageSurface()
    private val previousSlide = slideControl("‹", "Previous slide") { previousPage() }
    private val slideCounter = slideControl("", "Choose slide") { showPageChooser() }.apply { textSize = 14f }
    private val nextSlide = slideControl("›", "Next slide") { nextPage() }
    private val slideNavigation = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Color.BLACK)
        visibility = View.GONE
        addView(previousSlide, LinearLayout.LayoutParams(dp(56), dp(48)))
        addView(slideCounter, LinearLayout.LayoutParams(0, dp(48), 1f))
        addView(nextSlide, LinearLayout.LayoutParams(dp(56), dp(48)))
    }
    private val loading = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(Color.BLACK)
        addView(ProgressBar(activity), LinearLayout.LayoutParams(dp(40), dp(40)))
        addView(TextView(activity).apply {
            text = "Opening document…"
            textSize = 14f
            setTextColor(Color.rgb(165, 165, 175))
            setPadding(dp(16), dp(14), dp(16), 0)
        })
    }
    var onReady: ((String) -> Unit)? = null
        set(value) {
            field = value
            if (ready && !closed) value?.invoke(status)
        }
    val status: String
        get() = page?.let { "${it.label} · ${pageIndex + 1} / ${info?.count ?: 1}" } ?: "Opening document"
    val pageCount: Int get() = info?.count ?: 0
    val currentPage: Int get() = pageIndex + 1

    init {
        setBackgroundColor(Color.BLACK)
        addView(surface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(loading, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(slideNavigation, LayoutParams(LayoutParams.MATCH_PARENT, dp(48), Gravity.BOTTOM))
        session = OfficeRuntime.open(activity, file) { result ->
            if (!closed) result.fold(
                onSuccess = { (_, documentInfo) ->
                    info = documentInfo
                    configureSlideNavigation()
                    selectPage(0)
                },
                onFailure = { fail(it.message ?: "Unable to open the document.") }
            )
        }
    }
    private fun dp(value: Int) = (value * density).roundToInt()
    private fun slideControl(label: String, description: String, action: () -> Unit) = TextView(activity).apply {
        text = label
        textSize = 28f
        gravity = Gravity.CENTER
        setTextColor(ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(Color.rgb(80, 80, 80), Color.WHITE)
        ))
        contentDescription = description
        isClickable = true
        isFocusable = true
        minHeight = dp(48)
        setOnClickListener { action() }
        val attributes = activity.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        foreground = attributes.getDrawable(0)
        attributes.recycle()
    }
    private fun configureSlideNavigation() {
        val visible = info?.type == Document.DOCTYPE_PRESENTATION && pageCount > 1
        slideNavigation.visibility = if (visible) View.VISIBLE else View.GONE
        val bottom = if (visible) dp(48) else 0
        (surface.layoutParams as LayoutParams).apply { bottomMargin = bottom; surface.layoutParams = this }
        (loading.layoutParams as LayoutParams).apply { bottomMargin = bottom; loading.layoutParams = this }
        updateSlideNavigation()
    }
    private fun updateSlideNavigation() {
        previousSlide.isEnabled = !closed && !failed && !pageLoading && pageIndex > 0
        nextSlide.isEnabled = !closed && !failed && !pageLoading && pageIndex + 1 < pageCount
        slideCounter.isEnabled = !closed && !failed && !pageLoading
        slideCounter.text = "Slide ${pageIndex + 1} of $pageCount  ▾"
        slideCounter.contentDescription = "Slide ${pageIndex + 1} of $pageCount. Choose a slide."
    }

    fun nextPage() { if (!pageLoading && pageIndex + 1 < pageCount) selectPage(pageIndex + 1) }
    fun previousPage() { if (!pageLoading && pageIndex > 0) selectPage(pageIndex - 1) }
    fun showPageChooser() {
        val data = info ?: return
        if (closed || pageLoading || activity.isFinishing) return
        val names = if (data.type == 0) data.pages.map { it.label } else data.names
        AlertDialog.Builder(activity).setTitle(when (data.type) {
            Document.DOCTYPE_SPREADSHEET -> "Choose sheet"
            Document.DOCTYPE_PRESENTATION -> "Choose slide"
            else -> "Go to page"
        })
            .setSingleChoiceItems(names.toTypedArray(), pageIndex) { dialog, index ->
                selectPage(index); dialog.dismiss()
            }.setNegativeButton("Close", null).show()
    }
    fun zoomIn() = zoom(1.41421356237, width / 2f, height / 2f)
    fun zoomOut() = zoom(1.0 / 1.41421356237, width / 2f, height / 2f)
    fun setNightMode(enabled: Boolean) {
        nightMode = enabled
        surface.updateNightMode()
    }
    fun fitWidth() {
        val geometry = page ?: return
        if (width <= 0) return
        scale = ((width - margin * 2).coerceAtLeast(1.0) / geometry.width)
        offsetX = margin
        offsetY = margin
        positioned = true
        clamp()
        surface.invalidate()
    }
    private fun selectPage(index: Int) {
        val handle = session ?: return
        if (closed || index !in 0 until pageCount) return
        pageIndex = index
        pageLoading = true
        updateSlideNavigation()
        page = null
        positioned = false
        clearTiles()
        handle.generation = ++generation
        handle.wanted = emptySet()
        loading.visibility = View.VISIBLE
        surface.invalidate()
        val expected = generation
        OfficeRuntime.page(handle, index) { result ->
            if (!closed && generation == expected) result.fold(
                onSuccess = {
                    page = it
                    pageLoading = false
                    updateSlideNavigation()
                    loading.visibility = View.GONE
                    positionInitially()
                    ready = true
                    surface.contentDescription = "$status. Pinch to zoom and drag to pan. " +
                        if (slideNavigation.visibility == View.VISIBLE) "Slide controls are below the document." else "Page navigation is in reader options."
                    onReady?.invoke(status)
                    surface.invalidate()
                },
                onFailure = { fail(it.message ?: "Unable to render this page.") }
            )
        }
    }
    private fun positionInitially() {
        val geometry = page ?: return
        if (width <= 0 || height <= 0) return
        scale = if (info?.type == 1) baseScale else ((width - margin * 2).coerceAtLeast(1.0) / geometry.width)
        offsetX = margin
        offsetY = margin
        positioned = true
        clamp()
    }
    private fun zoom(factor: Double, focusX: Float, focusY: Float) {
        val geometry = page ?: return
        if (closed || !factor.isFinite() || factor <= 0) return
        val fit = ((width - margin * 2).coerceAtLeast(1.0) / geometry.width)
        val next = (scale * factor).coerceIn(min(fit / 4.0, baseScale / 8.0).coerceAtLeast(1e-9), max(baseScale * 16, fit * 8))
        val ratio = next / scale
        offsetX = focusX - (focusX - offsetX) * ratio
        offsetY = focusY - (focusY - offsetY) * ratio
        scale = next
        clamp()
        surface.invalidate()
    }
    private fun clamp() {
        val geometry = page ?: return
        val viewportHeight = surface.height.takeIf { it > 0 } ?: height
        offsetX = OfficeGeometry.clampOffset(offsetX, geometry.width * scale, width, margin)
        // Long text pages start at the top; a shorter page is vertically centered only after panning.
        offsetY = if (geometry.height * scale + margin * 2 <= viewportHeight) margin
            else OfficeGeometry.clampOffset(offsetY, geometry.height * scale, viewportHeight, margin)
    }
    private fun clearTiles() {
        // Canvas drawing and these references live on the UI thread. Do not recycle bitmaps that
        // Android may still have referenced in a queued hardware display list.
        cache.clear()
        pending.clear()
        rasterLevel = null
    }
    private fun fail(message: String) {
        if (closed || failed) return
        failed = true
        pageLoading = false
        updateSlideNavigation()
        loading.visibility = View.GONE
        val label = TextView(activity).apply {
            text = "Unable to display this document.\n\n$message"
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(Color.BLACK)
        }
        addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        onFailure(message)
    }
    override fun onDetachedFromWindow() {
        close()
        super.onDetachedFromWindow()
    }
    override fun close() {
        if (closed) return
        closed = true
        updateSlideNavigation()
        generation++
        session?.let { OfficeRuntime.close(it) }
        session = null
        clearTiles()
    }

    private inner class PageSurface : View(activity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(207, 207, 216)
            textSize = 11f * density
        }
        private val pinch = ScaleGestureDetector(activity, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoom(detector.scaleFactor.toDouble(), detector.focusX, detector.focusY)
                return true
            }
        })
        private val gestures = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (!pinch.isInProgress) {
                    offsetX -= distanceX
                    offsetY -= distanceY
                    clamp()
                    invalidate()
                }
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val geometry = page ?: return true
                val fit = (width - margin * 2).coerceAtLeast(1.0) / geometry.width
                if (info?.type != 1 && scale > fit * 1.6) fitWidth()
                else zoom(2.0, e.x, e.y)
                return true
            }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { performClick(); return true }
        })
        init { isFocusable = true; isClickable = true; setBackgroundColor(Color.BLACK) }
        fun updateNightMode() {
            bitmapPaint.colorFilter = if (nightMode) ColorMatrixColorFilter(floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            )) else null
            invalidate()
        }
        override fun performClick(): Boolean { super.performClick(); return true }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (closed || failed) return false
            parent?.requestDisallowInterceptTouchEvent(event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL)
            pinch.onTouchEvent(event)
            gestures.onTouchEvent(event)
            return true
        }
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (!positioned) positionInitially() else clamp()
            invalidate()
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val geometry = page ?: return
            val handle = session ?: return
            if (closed || failed || width <= 0 || height <= 0) return
            if (!positioned) positionInitially()
            paint.color = if (nightMode) Color.BLACK else Color.WHITE
            canvas.drawRect(offsetX.toFloat(), offsetY.toFloat(),
                (offsetX + geometry.width * scale).toFloat(), (offsetY + geometry.height * scale).toFloat(), paint)
            var level = (ln(scale / baseScale) / ln(2.0) * 2).roundToInt().coerceIn(-80, 32)
            var rasterScale = baseScale * 2.0.pow(level / 2.0)
            // Keep a high-density tablet viewport within the same bounded cache as a phone.
            while ((ceil(width * rasterScale / scale / 512) + 1) * (ceil(height * rasterScale / scale / 512) + 1) > 24 && level > -80) {
                level--
                rasterScale = baseScale * 2.0.pow(level / 2.0)
            }
            if (rasterLevel != level) {
                clearTiles()
                rasterLevel = level
                handle.generation = ++generation
            }
            val visible = OfficeGeometry.visibleTiles(geometry, pageIndex, scale, offsetX, offsetY, width, height, level, rasterScale)
            handle.wanted = visible.mapTo(mutableSetOf()) { it.key }
            visible.forEach { tile ->
                val found = cache[tile.key]
                if (found != null) {
                    val destination = RectF(
                        (offsetX + tile.x * scale).toFloat(), (offsetY + tile.y * scale).toFloat(),
                        (offsetX + (tile.x + tile.width) * scale).toFloat(),
                        (offsetY + (tile.y + tile.height) * scale).toFloat()
                    )
                    canvas.drawBitmap(found.bitmap, null, destination, bitmapPaint)
                } else if (pending.size < 3 && pending.add(tile.key)) {
                    val expected = generation
                    OfficeRuntime.render(handle, geometry, tile, expected) { result ->
                        if (closed || generation != expected) { result.getOrNull()?.recycle(); return@render }
                        pending.remove(tile.key)
                        result.fold(onSuccess = { bitmap ->
                            if (bitmap != null) {
                                cache[tile.key] = Cached(tile, bitmap)
                                while (cache.size > 32) {
                                    val iterator = cache.entries.iterator()
                                    iterator.next(); iterator.remove()
                                }
                            }
                            invalidate()
                        }, onFailure = { fail(it.message ?: "The Office engine could not render this tile.") })
                    }
                }
            }
            val zoom = (scale / baseScale * 100).roundToInt()
            val label = "${pageIndex + 1} / ${info?.count ?: 1}  ·  $zoom%"
            val textWidth = textPaint.measureText(label)
            val right = width - dp(8).toFloat()
            val bottom = height - dp(8).toFloat()
            paint.color = Color.BLACK
            canvas.drawRoundRect(RectF(right - textWidth - dp(16), bottom - dp(25), right, bottom), dp(6).toFloat(), dp(6).toFloat(), paint)
            canvas.drawText(label, right - textWidth - dp(8), bottom - dp(8), textPaint)
        }
    }
}
