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
    private val navy = Color.rgb(23, 34, 53)
    private val ink = Color.rgb(39, 47, 62)
    private val muted = Color.rgb(110, 118, 133)
    private val purple = Color.rgb(103, 85, 206)
    private val paper = Color.rgb(245, 244, 240)
    private val worker = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("library", MODE_PRIVATE) }
    private val directory by lazy { File(filesDir, "documents").apply { mkdirs() } }
    private lateinit var root: LinearLayout
    private var pdf: PdfReaderView? = null
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
    private fun label(value: String, size: Float = 16f, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun button(value: String, primary: Boolean = false, action: () -> Unit) = Button(this).apply {
        text = value; isAllCaps = false; textSize = 14f
        setTextColor(if (primary) Color.WHITE else purple)
        background = round(if (primary) purple else Color.rgb(235, 231, 251), 12)
        minHeight = dp(46)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        setOnClickListener { action() }
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun LinearLayout.add(view: View, width: Int = LinearLayout.LayoutParams.MATCH_PARENT, height: Int = LinearLayout.LayoutParams.WRAP_CONTENT, margin: Int = 0) {
        addView(view, LinearLayout.LayoutParams(width, height).apply { setMargins(dp(margin), dp(margin), dp(margin), dp(margin)) })
    }
    private fun newScreen() {
        pdf?.close(); pdf = null
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
        val header = column().apply { setPadding(dp(24), dp(27), dp(24), dp(26)); setBackgroundColor(navy) }
        header.add(label("FOLIODOCK", 13f, Color.rgb(188, 177, 244), true))
        header.add(label("Your files.\nOne quiet place.", 31f, Color.WHITE, true), margin = 0)
        header.add(label("Your documents stay on this device.", 14f, Color.rgb(194, 201, 213)))
        header.add(button("＋  Open a document", true) { pick() }, margin = 0)
        root.add(header)
        val scroll = ScrollView(this)
        val content = column().apply { setPadding(dp(20), dp(22), dp(20), dp(24)) }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val top = row()
        top.addView(label("Your library", 23f, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        top.add(button("About") { about() }, width = dp(82))
        content.add(top)
        val search = EditText(this).apply { hint = "Find a document"; textSize = 15f; setSingleLine(); contentDescription = "Search library" }
        content.add(search)
        val filters = row()
        listOf("All", "PDF", "Office", "Text").forEach { filter ->
            filters.addView(button(filter, libraryFilter == filter) {
                libraryFilter = filter
                showLibrary()
            }, LinearLayout.LayoutParams(0, dp(45), 1f).apply { setMargins(dp(2), dp(8), dp(2), dp(8)) })
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
                    "Office" -> ext in setOf("docx", "xlsx", "pptx", "odt", "ods", "odp", "doc", "xls", "ppt")
                    "Text" -> ext in setOf("txt", "md", "csv", "tsv", "rtf")
                    else -> true
                }
            }
            if (filtered.isEmpty()) {
                val empty = column().apply { background = round(Color.WHITE); setPadding(dp(24), dp(30), dp(24), dp(30)) }
                empty.add(label(if (files.isEmpty()) "A fresh page starts here." else "No matching documents.", 20f, ink, true))
                empty.add(label("Open PDF, Word, Excel, PowerPoint,\nOpenDocument, or text files.", 15f, muted))
                empty.add(label("OFFLINE  ·  NO ACCOUNT  ·  NO ADS", 10f, purple, true), margin = 8)
                list.add(empty, margin = 2)
            }
            filtered.forEach { f ->
                val name = prefs.getString("name_${f.name}", f.name) ?: f.name
                val card = row().apply { background = round(Color.WHITE); setPadding(dp(14), dp(16), dp(14), dp(16)); isClickable = true; isFocusable = true }
                val ext = DocumentParser.extension(name).uppercase().ifBlank { "FILE" }
                card.add(label(ext.take(5), 11f, purple, true).apply { gravity = Gravity.CENTER; background = round(Color.rgb(239, 235, 253), 10); contentDescription = "$ext file" }, dp(54), dp(54))
                val info = column().apply { setPadding(dp(12), 0, dp(6), 0) }
                info.add(label(name, 16f, ink, true).apply { maxLines = 2 })
                info.add(label("${formatSize(f.length())}  ·  Saved on device", 12f, muted))
                card.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
                card.add(label("›", 29f, muted), dp(22))
                card.setOnClickListener { open(f) }
                card.setOnLongClickListener { libraryMenu(f, name); true }
                list.add(card, margin = 4)
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
    private fun open(file: File) {
        token++
        val expected = token
        currentFile = file
        currentName = prefs.getString("name_${file.name}", file.name) ?: file.name
        val originalName = currentName
        prefs.edit().putLong("time_${file.name}", System.currentTimeMillis()).apply()
        sectionIndex = 0; rowStart = 0; query = ""; matches = emptyList(); searchResult = -1
        document = null
        newScreen()
        readerHeader()
        if (DocumentParser.extension(currentName) == "pdf") {
            root.add(label("PDF · rendered locally · text search and pinch zoom unavailable", 11f, muted).apply { setPadding(dp(16), dp(8), dp(16), dp(8)) })
            pdf = PdfReaderView(this, file) { message -> error("PDF preview", message) }
            root.addView(pdf, LinearLayout.LayoutParams(-1, 0, 1f))
            val nav = row()
            nav.addView(button("Previous page") { pdf?.previousPage() }, LinearLayout.LayoutParams(0, -2, 1f))
            nav.addView(button("Next page") { pdf?.nextPage() }, LinearLayout.LayoutParams(0, -2, 1f))
            root.add(nav)
            return
        }
        if (DocumentParser.extension(currentName) !in DocumentParser.supported) {
            root.add(label("This format needs a dedicated app.", 23f, ink, true).apply { setPadding(dp(24), dp(34), dp(24), dp(16)) })
            root.add(label("FolioDock does not render legacy DOC, XLS, PPT, encrypted Office files, or arbitrary file types. Your original file is unchanged.", 16f, muted).apply { setPadding(dp(24), 0, dp(24), dp(24)) })
            root.add(button("Open externally") { external(file) }, margin = 20)
            return
        }
        val loading = ProgressBar(this)
        root.add(loading)
        worker.execute {
            try {
                val parsed = DocumentParser.parse(file, originalName)
                runOnUiThread { if (!isDestroyed && expected == token) { root.removeView(loading); document = parsed; createReader() } }
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
        val header = row().apply { setBackgroundColor(navy); setPadding(dp(10), dp(12), dp(10), dp(12)) }
        header.add(button("‹ Library") { showLibrary() }, dp(100))
        header.addView(label(currentName, 16f, Color.WHITE, true).apply { maxLines = 2; setPadding(dp(12), 0, dp(8), 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        header.add(button("⋮") {
            val f = currentFile ?: return@button
            AlertDialog.Builder(this).setTitle(currentName).setItems(arrayOf("Share original", "Open externally")) { _, which -> if (which == 0) share(f) else external(f) }.show()
        }, dp(48))
        root.add(header)
    }
    private fun createReader() {
        val parsed = document ?: return
        val notice = label(parsed.notice, 11f, muted).apply { setPadding(dp(16), dp(10), dp(16), dp(10)) }
        root.add(notice)
        val controls = row().apply { setPadding(dp(12), 0, dp(12), dp(6)) }
        val search = EditText(this).apply { hint = "Find in document"; textSize = 14f; setSingleLine(); contentDescription = "Find text in document" }
        controls.addView(search, LinearLayout.LayoutParams(0, dp(48), 1f))
        controls.add(button("Find") { performSearch(search.text.toString()) }, dp(62))
        controls.add(button("A−") { textSizeSp = (textSizeSp - 1).coerceAtLeast(12f); renderSection() }, dp(48))
        controls.add(button("A+") { textSizeSp = (textSizeSp + 1).coerceAtMost(28f); renderSection() }, dp(48))
        root.add(controls)
        search.setOnEditorActionListener { _, _, _ -> performSearch(search.text.toString()); true }
        resultLabel = label("", 12f, purple).apply { setPadding(dp(18), 0, dp(18), dp(4)) }
        root.add(resultLabel)
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
        val navigation = row().apply { setPadding(dp(12), dp(6), dp(12), dp(6)) }
        navigation.add(button("‹") { sectionIndex = (sectionIndex - 1).coerceAtLeast(0); rowStart = 0; renderSection() }.apply { isEnabled = sectionIndex > 0 }, dp(44))
        navigation.addView(label("${section.title}\n${sectionIndex + 1} of ${sections.size}", 14f, ink, true).apply {
            gravity = Gravity.CENTER
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity).setTitle("Go to section")
                    .setItems(sections.map { it.title }.toTypedArray()) { _, index -> sectionIndex = index; rowStart = 0; renderSection() }.show()
            }
            contentDescription = "Choose section. ${section.title}, ${sectionIndex + 1} of ${sections.size}"
        }, LinearLayout.LayoutParams(0, -2, 1f))
        navigation.add(button("›") { sectionIndex = (sectionIndex + 1).coerceAtMost(sections.lastIndex); rowStart = 0; renderSection() }.apply { isEnabled = sectionIndex < sections.lastIndex }, dp(44))
        body.add(navigation)
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
            val pages = row().apply { setPadding(dp(12), dp(4), dp(12), dp(8)) }
            pages.add(button("Earlier rows") { rowStart = (rowStart - 40).coerceAtLeast(0); renderSection() }.apply { isEnabled = rowStart > 0 }, dp(120))
            pages.addView(label("${rowStart + 1}–${min(rowStart + 40, section.rows.size)} / ${section.rows.size}", 12f).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f))
            pages.add(button("More rows") { rowStart += 40; renderSection() }.apply { isEnabled = rowStart + 40 < section.rows.size }, dp(110))
            body.add(pages)
        } else {
            val text = label("", textSizeSp).apply {
                text = highlighted(section.text.ifBlank { "No readable text in this section." })
                setTextIsSelectable(true)
                setPadding(dp(24), dp(22), dp(24), dp(40))
                setLineSpacing(dp(6).toFloat(), 1.14f)
                background = round(Color.WHITE, 10)
            }
            scroll.addView(text)
        }
    }
    private fun cell(value: String, shaded: Boolean) = label("", 14f).apply {
        text = highlighted(value.take(10_000))
        setTextIsSelectable(true)
        setPadding(dp(10), dp(12), dp(10), dp(12))
        setBackgroundColor(if (shaded) Color.rgb(234, 233, 241) else Color.WHITE)
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
                setSpan(BackgroundColorSpan(Color.rgb(255, 227, 133)), found, found + query.length, 0)
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
        AlertDialog.Builder(this).setTitle("FolioDock · 1.0")
            .setMessage("A private, offline document dock.\n\nPDF: native page rendering.\nDOCX / PPTX / XLSX: text or values previews.\nODT / ODP / ODS: text or values previews.\nTXT / MD / CSV / TSV / RTF: text previews.\n\nNot a full-fidelity Office engine. Images, charts, advanced formatting, encrypted Office documents and legacy DOC / XLS / PPT are not rendered. Use Open externally for a dedicated viewer.\n\nFiles are copied into app-private storage. No network permission, tracking, ads, or cloud upload. Android backups are disabled. Removing this app removes its copies. Source files remain unchanged.\n\nLimits: 80 MB import, 2 MB text, 12 MB per XML component. Spreadsheet previews are bounded and paginated.")
            .setPositiveButton("Close", null).show()
    }
    private fun formatSize(bytes: Long) = if (bytes < 1024 * 1024) "${(bytes / 1024).coerceAtLeast(1)} KB" else String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1048576.0)
    private fun error(title: String, message: String) { if (!isFinishing) AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show() }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    @Deprecated("Platform back callback keeps support for Android 8+")
    override fun onBackPressed() { if (currentFile != null) showLibrary() else super.onBackPressed() }
    override fun onDestroy() {
        token++
        pdf?.close()
        worker.shutdown()
        super.onDestroy()
    }
}
