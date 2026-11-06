package app.foliodock

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.TextUtils
import android.text.style.BackgroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.*
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.min

class MainActivity : Activity() {
    private val ink = Color.rgb(241, 241, 244)
    private val muted = Color.rgb(155, 155, 165)
    private val purple = Color.rgb(185, 168, 255)
    private val paper = Color.BLACK
    private val worker = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("library", MODE_PRIVATE) }
    private val directory by lazy { File(filesDir, "documents").apply { mkdirs() } }
    private lateinit var root: LinearLayout
    private var pdf: PdfReaderView? = null
    private var office: OfficeReaderView? = null
    private var nightPages = false
    private var currentFile: File? = null
    private var currentName = ""
    private var document: ParsedDocument? = null
    private var sectionIndex = 0
    private var rowStart = 0
    private var query = ""
    private var searchResult = -1
    private var searchKey = ""
    private data class Match(val section: Int, val row: Int = 0)
    private var matches = emptyList<Match>()
    private lateinit var body: LinearLayout
    private lateinit var resultLabel: TextView
    private var textSizeSp = 17f
    private var token = 0
    private var libraryFilter = "All"
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nightPages = prefs.getBoolean("night_pages", false)
        showLibrary()
        val uri = intent.takeIf { it.action == Intent.ACTION_VIEW }?.data
        if (uri != null) importDocument(uri)
        else savedInstanceState?.getString("file")?.let { file ->
            val f = File(directory, file)
            if (f.isFile && f.canonicalFile.parentFile == directory.canonicalFile) open(f)
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Intent.ACTION_VIEW) intent.data?.let { importDocument(it) }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        currentFile?.let { outState.putString("file", it.name) }
        super.onSaveInstanceState(outState)
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun round(color: Int, radius: Int = 16) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun outlined() = round(Color.BLACK, 10).apply { setStroke(dp(1), Color.rgb(38, 38, 43)) }
    private fun label(value: String, size: Float = 16f, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun button(value: String, primary: Boolean = false, action: () -> Unit) = Button(this).apply {
        text = value; isAllCaps = false; textSize = 14f
        setTextColor(android.content.res.ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(Color.rgb(80, 80, 88), if (primary) purple else ink)
        ))
        background = if (primary) outlined() else round(Color.BLACK, 8)
        minHeight = dp(48); minimumHeight = dp(48)
        minWidth = 0; minimumWidth = 0
        setPadding(dp(8), 0, dp(8), 0)
        setSingleLine()
        setOnClickListener { action() }
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun LinearLayout.add(view: View, width: Int = LinearLayout.LayoutParams.MATCH_PARENT, height: Int = LinearLayout.LayoutParams.WRAP_CONTENT, margin: Int = 0) {
        addView(view, LinearLayout.LayoutParams(width, height).apply { setMargins(dp(margin), dp(margin), dp(margin), dp(margin)) })
    }
    private fun newScreen() {
        pdf?.close(); pdf = null
        office?.close(); office = null
        root = column().apply { setBackgroundColor(paper) }
        setContentView(root)
        // Target 35 uses edge-to-edge. Keep all interactive content outside system bars.
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = if (android.os.Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.systemBars())
                .let { intArrayOf(it.left, it.top, it.right, it.bottom) }
            else @Suppress("DEPRECATION") intArrayOf(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            v.setPadding(bars[0], bars[1], bars[2], bars[3])
            insets
        }
        root.requestApplyInsets()
    }
    private fun showLibrary() {
        token++
        currentFile = null; document = null
        newScreen()
        val header = row().apply { setPadding(dp(16), dp(4), dp(4), dp(4)); setBackgroundColor(Color.BLACK) }
        header.addView(label("FolioDock", 20f, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        header.add(button("＋ Open", true) { pick() }, dp(88), dp(48))
        header.add(button("⋮") { about() }.apply { contentDescription = "About FolioDock" }, dp(48), dp(48))
        root.add(header, height = dp(56))
        val scroll = ScrollView(this)
        val content = column().apply { setPadding(dp(12), 0, dp(12), dp(16)) }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val search = EditText(this).apply {
            hint = "Search files"; textSize = 14f; setSingleLine(); contentDescription = "Search library"
            setTextColor(ink); setHintTextColor(muted)
            setPadding(dp(12), 0, dp(12), 0); background = outlined()
        }
        content.add(search, height = dp(48))
        val filters = row()
        listOf("All", "PDF", "Office", "Text").forEach { filter ->
            filters.addView(button(filter, libraryFilter == filter) {
                libraryFilter = filter
                showLibrary()
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
        }
        content.add(filters)
        val list = column()
        content.add(list)
        fun refresh() {
            list.removeAllViews()
            val files = directory.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }
                ?.sortedByDescending { prefs.getLong("time_${it.name}", it.lastModified()) } ?: emptyList()
            val filtered = files.filter { f ->
                val name = prefs.getString("name_${f.name}", f.name) ?: f.name
                val ext = DocumentParser.extension(name)
                name.contains(search.text.toString(), true) && when (libraryFilter) {
                    "PDF" -> ext == "pdf"
                    "Office" -> OfficeFormats.supports(name)
                    "Text" -> ext in setOf("txt", "md", "csv", "tsv", "rtf")
                    else -> true
                }
            }
            if (filtered.isEmpty()) {
                val empty = column().apply { background = outlined(); setPadding(dp(18), dp(22), dp(18), dp(22)) }
                empty.add(label(if (files.isEmpty()) "No documents yet" else "No matching documents", 18f, ink, true))
                empty.add(label("Tap Open to choose a file.\nPrivate, offline, and saved on this device.", 14f, muted))
                list.add(empty, margin = 2)
            }
            filtered.forEach { f ->
                val name = prefs.getString("name_${f.name}", f.name) ?: f.name
                val card = row().apply { background = outlined(); setPadding(dp(12), dp(10), dp(10), dp(10)); isClickable = true; isFocusable = true }
                val ext = DocumentParser.extension(name).uppercase().ifBlank { "FILE" }
                card.add(label(ext.take(5), 10f, purple, true).apply { gravity = Gravity.CENTER; background = outlined(); contentDescription = "$ext file" }, dp(42), dp(42))
                val info = column().apply { setPadding(dp(12), 0, dp(6), 0) }
                info.add(label(name, 15f, ink, true).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END })
                info.add(label("${formatSize(f.length())}  ·  Saved on device", 12f, muted))
                card.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
                card.add(label("›", 29f, muted), dp(22))
                card.setOnClickListener { open(f) }
                card.setOnLongClickListener { libraryMenu(f, name); true }
                list.add(card, margin = 3)
            }
        }
        search.addTextChangedListener(watcher { refresh() })
        refresh()
        content.add(label("Long-press a document to share or remove its local copy.", 12f, muted), margin = 10)
    }
    private fun pick() {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }, 100)
    }
    @Deprecated("Uses platform activity result for zero runtime dependencies")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == RESULT_OK) data?.data?.let { importDocument(it) }
    }
    private fun importDocument(uri: Uri) {
        if (uri.scheme != "content") { toast("Choose a document through Android’s document picker."); return }
        token++
        val expected = token
        newScreen()
        root.add(label("Opening your document…", 22f, ink, true).apply { setPadding(dp(24), dp(40), dp(24), dp(20)) })
        root.add(ProgressBar(this))
        worker.execute {
            var partial: File? = null
            try {
                var name = "Document"
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (n >= 0 && !c.isNull(n)) name = c.getString(n).take(180)
                        val s = c.getColumnIndex(OpenableColumns.SIZE)
                        require(s < 0 || c.isNull(s) || c.getLong(s) <= DocumentParser.MAX_FILE) { "Files larger than 80 MB are not supported." }
                    }
                }
                var ext = DocumentParser.extension(name)
                if (ext.isEmpty()) {
                    ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(contentResolver.getType(uri)) ?: ""
                    if (ext.isNotEmpty()) name += ".$ext"
                }
                val safeExt = ext.filter { it.isLetterOrDigit() }.take(10)
                val file = File(directory, "${UUID.randomUUID()}${if (safeExt.isEmpty()) "" else ".$safeExt"}")
                partial = File(directory, "${file.name}.part")
                contentResolver.openInputStream(uri)?.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            require(total <= DocumentParser.MAX_FILE) { "Files larger than 80 MB are not supported." }
                            output.write(buffer, 0, n)
                        }
                    }
                } ?: error("The selected document could not be read.")
                require(partial.renameTo(file)) { "Could not save the document." }
                prefs.edit().putString("name_${file.name}", name).putLong("time_${file.name}", System.currentTimeMillis()).apply()
                runOnUiThread { if (!isDestroyed && expected == token) open(file) }
            } catch (e: Exception) {
                partial?.delete()
                runOnUiThread { if (!isDestroyed && expected == token) { showLibrary(); error("Could not import document", e.message ?: "Please try another file.") } }
            }
        }
    }
    private fun open(file: File, textOnly: Boolean = false, searchAfter: Boolean = false) {
        token++
        val expected = token
        currentFile = file
        currentName = prefs.getString("name_${file.name}", file.name) ?: file.name
        val originalName = currentName
        prefs.edit().putLong("time_${file.name}", System.currentTimeMillis()).apply()
        sectionIndex = 0; rowStart = 0; query = ""; searchKey = ""; matches = emptyList(); searchResult = -1
        document = null
        newScreen()
        readerHeader()
        if (DocumentParser.extension(currentName) == "pdf") {
            pdf = PdfReaderView(this, file) { message -> error("PDF preview", message) }
            pdf?.setNightMode(nightPages)
            root.addView(pdf, LinearLayout.LayoutParams(-1, 0, 1f))
            return
        }
        if (!textOnly && OfficeFormats.supports(currentName)) {
            office = OfficeReaderView(this, file) { message ->
                if (expected == token && !isFinishing && !isDestroyed) {
                    val dialog = AlertDialog.Builder(this).setTitle("Layout preview unavailable")
                        .setMessage("$message\n\nThe original document has not been changed.")
                        .setNegativeButton("Close", null)
                        .setNeutralButton("Open externally") { _, _ -> external(file) }
                    if (DocumentParser.extension(originalName) in DocumentParser.supported)
                        dialog.setPositiveButton("Text preview") { _, _ -> open(file, textOnly = true) }
                    dialog.show()
                }
            }
            office?.setNightMode(nightPages)
            root.addView(office, LinearLayout.LayoutParams(-1, 0, 1f))
            return
        }
        if (DocumentParser.extension(currentName) !in DocumentParser.supported) {
            root.add(label("This format needs a dedicated app.", 23f, ink, true).apply { setPadding(dp(24), dp(34), dp(24), dp(16)) })
            root.add(label("This file has no text preview, or requires an unsupported importer. Encrypted or damaged documents may need another app. Your original file is unchanged.", 16f, muted).apply { setPadding(dp(24), 0, dp(24), dp(24)) })
            root.add(button("Open externally") { external(file) }, margin = 20)
            return
        }
        val loading = ProgressBar(this)
        root.add(loading)
        worker.execute {
            try {
                val parsed = DocumentParser.parse(file, originalName)
                runOnUiThread {
                    if (!isDestroyed && expected == token) {
                        root.removeView(loading); document = parsed; createReader()
                        if (searchAfter) showFindDialog()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isDestroyed && expected == token) {
                        root.removeView(loading)
                        root.add(label("Preview unavailable", 23f, ink, true).apply { setPadding(dp(24), dp(32), dp(24), dp(8)) })
                        root.add(label(e.message ?: "This file may be encrypted, damaged, or unsupported.", 16f, muted).apply { setPadding(dp(24), dp(8), dp(24), dp(20)) })
                        root.add(button("Open externally") { external(file) }, margin = 20)
                    }
                }
            }
        }
    }
    private fun readerHeader() {
        val header = row().apply { setBackgroundColor(Color.BLACK); setPadding(dp(4), dp(4), dp(4), dp(4)) }
        header.add(button("‹") { showLibrary() }.apply { textSize = 30f; contentDescription = "Back to library" }, dp(48), dp(48))
        header.addView(label(currentName, 15f, ink, true).apply {
            setSingleLine(); ellipsize = TextUtils.TruncateAt.MIDDLE; setPadding(dp(4), 0, dp(4), 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.add(button("⌕") { showFindDialog() }.apply { textSize = 26f; contentDescription = "Find in document" }, dp(48), dp(48))
        header.add(button("⋮") { readerMenu() }.apply { textSize = 23f; contentDescription = "Reader options" }, dp(48), dp(48))
        root.add(header, height = dp(56))
    }
    private fun readerMenu() {
        val file = currentFile ?: return
        val items = mutableListOf<Pair<String, () -> Unit>>()
        if (office != null || pdf != null) {
            items += (if (nightPages) "Original page colors" else "Night page colors") to {
                nightPages = !nightPages
                prefs.edit().putBoolean("night_pages", nightPages).apply()
                office?.setNightMode(nightPages)
                pdf?.setNightMode(nightPages)
            }
        }
        if (office != null) {
            items += "Go to page / slide / sheet" to { office?.showPageChooser(); Unit }
            items += "Previous page / part" to { office?.previousPage(); Unit }
            items += "Next page / part" to { office?.nextPage(); Unit }
            items += "Fit page width" to { office?.fitWidth(); Unit }
            items += "Zoom in" to { office?.zoomIn(); Unit }
            items += "Zoom out" to { office?.zoomOut(); Unit }
            if (DocumentParser.extension(currentName) in DocumentParser.supported)
                items += "Text preview / search" to { open(file, textOnly = true) }
        } else if (document != null && OfficeFormats.supports(currentName)) {
            items += "Full layout" to { open(file) }
        }
        if (pdf != null) {
            items += "Previous page" to { pdf?.previousPage(); Unit }
            items += "Next page" to { pdf?.nextPage(); Unit }
        }
        val sections = document?.sections.orEmpty()
        if (sections.size > 1) {
            items += "Go to section · ${sectionIndex + 1} / ${sections.size}" to {
                AlertDialog.Builder(this).setTitle("Go to section")
                    .setItems(sections.map { it.title }.toTypedArray()) { _, index ->
                        sectionIndex = index; rowStart = 0; renderSection()
                    }.show()
                Unit
            }
            if (sectionIndex > 0) items += "Previous section" to { sectionIndex--; rowStart = 0; renderSection() }
            if (sectionIndex < sections.lastIndex) items += "Next section" to { sectionIndex++; rowStart = 0; renderSection() }
        }
        if (document != null) {
            items += "Text size" to {
                val sizes = arrayOf("Small · 14", "Default · 17", "Large · 20", "Extra large · 24")
                val values = floatArrayOf(14f, 17f, 20f, 24f)
                AlertDialog.Builder(this).setTitle("Text size").setSingleChoiceItems(sizes, values.indexOfFirst { it == textSizeSp }) { dialog, index ->
                    textSizeSp = values[index]; renderSection(); dialog.dismiss()
                }.setNegativeButton("Close", null).show()
                Unit
            }
        }
        items += "Preview details" to {
            error("Preview details", when {
                office != null -> "Full layout is rendered offline by LibreOffice 26.2.6.3, including page geometry, styles, images, tables, and slide/sheet layouts. Pinch to zoom and drag to pan. Fonts and advanced features can differ from Microsoft Office. This is a read-only viewer; macros are not enabled. Use Text preview for searchable extracted text."
                document != null -> document!!.notice
                else -> "PDF pages retain their original colors. PDF text search and text selection are not available in this viewer."
            })
        }
        items += "Share original" to { share(file) }
        items += "Open externally" to { external(file) }
        AlertDialog.Builder(this).setItems(items.map { it.first }.toTypedArray()) { _, index -> items[index].second() }.show()
    }
    private fun showFindDialog() {
        if (office != null) {
            val file = currentFile ?: return
            if (DocumentParser.extension(currentName) in DocumentParser.supported) {
                open(file, textOnly = true, searchAfter = true)
            } else toast("Text search is unavailable for this format. You can use page navigation or open it externally.")
            return
        }
        if (document == null) {
            toast(if (pdf != null) "PDF text search is not available in this viewer." else "Wait for the document to open.")
            return
        }
        val panel = column().apply { setPadding(dp(20), dp(8), dp(20), dp(8)) }
        val input = EditText(this).apply {
            hint = "Find text"; setSingleLine(); setText(query); textSize = 16f
            setTextColor(ink); setHintTextColor(muted); contentDescription = "Find text in document"
        }
        panel.add(input, height = dp(48))
        resultLabel = label("", 12f, muted)
        panel.add(resultLabel)
        val dialog = AlertDialog.Builder(this).setTitle("Find in document").setView(panel)
            .setNegativeButton("Close", null).setNeutralButton("Clear", null).setPositiveButton("Find next", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { performSearch(input.text.toString()) }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { input.setText(""); performSearch("") }
        }
        input.setOnEditorActionListener { _, _, _ -> performSearch(input.text.toString()); true }
        dialog.show()
    }
    private fun createReader() {
        if (document == null) return
        resultLabel = label("", 12f, muted)
        body = column()
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        renderSection()
    }
    private fun renderSection() {
        if (!::body.isInitialized) return
        body.removeAllViews()
        val sections = document?.sections.orEmpty()
        if (sections.isEmpty()) { body.add(label("No readable content was found.", 17f).apply { setPadding(dp(24), dp(24), dp(24), dp(24)) }); return }
        sectionIndex = sectionIndex.coerceIn(0, sections.lastIndex)
        val section = sections[sectionIndex]
        val scroll = ScrollView(this)
        body.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        if (section.rows.isNotEmpty()) {
            val horizontal = HorizontalScrollView(this)
            val table = column().apply { setPadding(dp(12), dp(8), dp(12), dp(24)) }
            horizontal.addView(table)
            scroll.addView(horizontal)
            val cols = section.rows.maxOfOrNull { it.size } ?: 0
            val heading = row()
            for (col in -1 until cols) heading.add(cell(if (col < 0) "#" else columnLabel(col), true), dp(if (col < 0) 48 else 150), dp(42))
            table.add(heading)
            for (r in rowStart until min(rowStart + 40, section.rows.size)) {
                val line = row()
                line.add(cell("${r + 1}", true), dp(48))
                for (c in 0 until cols) line.add(cell(section.rows[r].getOrNull(c) ?: "", r % 2 == 1), dp(150))
                table.add(line)
            }
            val pages = row().apply { setPadding(dp(8), 0, dp(8), 0) }
            pages.add(button("‹") { rowStart = (rowStart - 40).coerceAtLeast(0); renderSection() }.apply { isEnabled = rowStart > 0; contentDescription = "Earlier rows" }, dp(48), dp(48))
            pages.addView(label("${rowStart + 1}–${min(rowStart + 40, section.rows.size)} / ${section.rows.size}", 12f).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f))
            pages.add(button("›") { rowStart += 40; renderSection() }.apply { isEnabled = rowStart + 40 < section.rows.size; contentDescription = "More rows" }, dp(48), dp(48))
            if (section.rows.size > 40) body.add(pages, height = dp(48))
        } else {
            val text = label("", textSizeSp).apply {
                text = highlighted(section.text.ifBlank { "No readable text in this section." })
                setTextIsSelectable(true)
                setPadding(dp(18), dp(16), dp(18), dp(28))
                setLineSpacing(dp(4).toFloat(), 1.08f)
                setBackgroundColor(Color.BLACK)
            }
            scroll.addView(text)
        }
    }
    private fun cell(value: String, shaded: Boolean) = label("", 14f).apply {
        text = highlighted(value.take(10_000))
        setTextIsSelectable(true)
        setPadding(dp(10), dp(12), dp(10), dp(12))
        background = round(Color.BLACK, 0).apply { setStroke(dp(1), if (shaded) Color.rgb(48, 48, 56) else Color.rgb(28, 28, 32)) }
        minHeight = dp(46)
    }
    private fun columnLabel(index: Int): String {
        var n = index + 1; var s = ""
        while (n > 0) { n--; s = ('A' + n % 26) + s; n /= 26 }
        return s
    }
    private fun highlighted(text: String): CharSequence {
        if (query.isBlank()) return text
        return SpannableString(text).apply {
            var from = 0
            var count = 0
            while (from < text.length && count++ < 2000) {
                val found = text.indexOf(query, from, true)
                if (found < 0) break
                setSpan(BackgroundColorSpan(Color.rgb(91, 67, 12)), found, found + query.length, 0)
                from = found + query.length
            }
        }
    }
    private fun performSearch(value: String) {
        query = value.trim().take(200)
        if (query.isBlank()) { matches = emptyList(); searchResult = -1; searchKey = ""; resultLabel.text = ""; renderSection(); return }
        if (query != searchKey) {
            searchKey = query
            val results = mutableListOf<Match>()
            document?.sections?.forEachIndexed { index, section ->
                if (section.rows.isNotEmpty()) section.rows.forEachIndexed { r, cells -> if (cells.any { it.contains(query, true) }) results.add(Match(index, r)) }
                else if (section.text.contains(query, true)) results.add(Match(index))
            }
            matches = results
            searchResult = -1
        }
        if (matches.isEmpty()) { resultLabel.text = "No matches for “$query”"; renderSection(); return }
        searchResult = (searchResult + 1) % matches.size
        val match = matches[searchResult]
        sectionIndex = match.section
        rowStart = match.row / 40 * 40
        resultLabel.text = "Matching section / row ${searchResult + 1} of ${matches.size} · tap Find for next"
        renderSection()
    }
    private fun watcher(action: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { action() }
        override fun afterTextChanged(s: Editable?) {}
    }
    private fun sharingUri(file: File) = Uri.Builder().scheme("content").authority("$packageName.documents").appendPath(file.name).build()
    private fun share(file: File) {
        val uri = sharingUri(file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = contentResolver.getType(uri)
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("Document", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivity(Intent.createChooser(send, "Share original document")) } catch (_: Exception) { toast("No sharing app is available.") }
    }
    private fun external(file: File) {
        val uri = sharingUri(file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, contentResolver.getType(uri))
            clipData = ClipData.newRawUri("Document", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // Exclude FolioDock to avoid a chooser loop when another app is requested.
        val options = packageManager.queryIntentActivities(intent, 0).filter { it.activityInfo.packageName != packageName }
        if (options.isEmpty()) { toast("Install an app that supports this file format to open it externally."); return }
        AlertDialog.Builder(this).setTitle("Open original with")
            .setItems(options.map { it.loadLabel(packageManager).toString() }.toTypedArray()) { _, index ->
                val selected = options[index].activityInfo
                try { startActivity(Intent(intent).setClassName(selected.packageName, selected.name)) }
                catch (_: Exception) { toast("That app could not open the document.") }
            }.show()
    }
    private fun libraryMenu(file: File, name: String) {
        AlertDialog.Builder(this).setTitle(name).setItems(arrayOf("Open", "Share original", "Remove local copy")) { _, which ->
            when (which) {
                0 -> open(file)
                1 -> share(file)
                2 -> AlertDialog.Builder(this).setTitle("Remove local copy?")
                    .setMessage("Only FolioDock’s copy is removed. The source document is not changed.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Remove") { _, _ ->
                        if (file.delete()) {
                            prefs.edit().remove("name_${file.name}").remove("time_${file.name}").apply()
                            showLibrary()
                        } else toast("Could not remove this copy.")
                    }.show()
            }
        }.show()
    }
    private fun about() {
        AlertDialog.Builder(this).setTitle("FolioDock · 2.0")
            .setMessage("An offline document reader with AMOLED-black chrome and a compact toolbar.\n\nOffice layout: LibreOffice 26.2.6.3 renders DOC / DOCX, XLS / XLSX, PPT / PPTX, OpenDocument, RTF, and related template formats, including formatting and images. Fonts, advanced charts, and complex documents may differ from Microsoft Office. Read-only; macros are disabled.\n\nPDF: native page rendering. Text / Markdown / CSV / TSV: searchable text and table previews. Optional text preview is available for modern Office files.\n\nNo network permission, tracking, ads, or cloud upload. Android backups are disabled. Imported copies stay on this device; originals remain unchanged.\n\nLibreOffice is MPL-2.0 licensed software. Corresponding engine source, notices, and original build provenance are documented in the repository's THIRD_PARTY_NOTICES.md. FolioDock is not affiliated with The Document Foundation.\n\nLimits: 80 MB import. Only 64-bit ARM and x86 Android devices are supported. Password-protected files require an external app.")
            .setNeutralButton("Licenses") { _, _ -> showLicenses() }
            .setPositiveButton("Close", null).show()
    }
    private fun showLicenses() {
        val choices = arrayOf("Notices and corresponding source", "Mozilla Public License 2.0", "GNU Lesser GPL", "GNU GPL", "Component licenses")
        val files = arrayOf("NOTICE.txt", "LibreOffice-COPYING.MPL", "LibreOffice-COPYING.LGPL", "LibreOffice-COPYING", "LibreOffice-components.xml")
        AlertDialog.Builder(this).setTitle("Open-source licenses").setItems(choices) { _, index ->
            val text = assets.open("licenses/${files[index]}").bufferedReader().use { it.readText() }
            val scroll = ScrollView(this).apply {
                addView(label("", 12f).apply {
                    this.text = if (index == 4) android.text.Html.fromHtml(text, android.text.Html.FROM_HTML_MODE_LEGACY) else text
                    setPadding(dp(20), dp(8), dp(20), dp(8)); setTextIsSelectable(true)
                })
            }
            AlertDialog.Builder(this).setTitle(choices[index]).setView(scroll).setPositiveButton("Close", null).show()
        }.setNegativeButton("Close", null).show()
    }
    private fun formatSize(bytes: Long) = if (bytes < 1024 * 1024) "${(bytes / 1024).coerceAtLeast(1)} KB" else String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1048576.0)
    private fun error(title: String, message: String) { if (!isFinishing) AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show() }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    @Deprecated("Platform back callback keeps support for Android 8+")
    override fun onBackPressed() { if (currentFile != null) showLibrary() else super.onBackPressed() }
    override fun onDestroy() {
        token++
        pdf?.close()
        office?.close()
        worker.shutdown()
        super.onDestroy()
    }
}
