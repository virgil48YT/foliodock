package app.foliodock

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

data class Section(val title: String, val text: String = "", val rows: List<List<String>> = emptyList())
data class ParsedDocument(val kind: String, val sections: List<Section>, val notice: String)

/**
 * Read-only, text-first parser. Never extracts ZIP paths to disk or evaluates formulas/macros.
 * Hard limits are part of the security boundary, not just UI pagination.
 */
object DocumentParser {
    const val MAX_FILE = 80L * 1024 * 1024
    private const val MAX_XML = 12 * 1024 * 1024
    private const val MAX_TEXT = 2_000_000
    private const val MAX_ROWS = 2000
    private const val MAX_COLS = 100
    val supported = setOf("pdf", "docx", "xlsx", "pptx", "odt", "ods", "odp", "txt", "md", "csv", "tsv", "rtf")
    private const val OFFICE_NOTICE = "Text-first preview. Original layout, images, charts, comments, and embedded objects are not rendered."
    fun extension(name: String) = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    fun parse(file: File, originalName: String): ParsedDocument {
        require(file.length() <= MAX_FILE) { "This file exceeds the 80 MB safety limit." }
        return when (val ext = extension(originalName)) {
            "docx", "xlsx", "pptx", "odt", "ods", "odp" -> Archive(file).use { archive ->
                when (ext) {
                    "docx" -> word(archive)
                    "xlsx" -> workbook(archive)
                    "pptx" -> slides(archive)
                    else -> openDocument(archive, ext)
                }
            }
            "csv", "tsv" -> ParsedDocument(ext.uppercase(), listOf(Section("Table", rows = parseDelimited(readText(file), if (ext == "csv") ',' else '\t'))),
                "Table preview · UTF-8 · up to 2,000 rows and 100 columns.")
            "txt", "md" -> ParsedDocument(ext.uppercase(), chunks(readText(file)), "Plain-text preview · UTF-8. Markdown is shown as source text.")
            "rtf" -> ParsedDocument("RTF", chunks(rtfText(readText(file))), "Text-only RTF preview. Formatting and embedded objects are omitted.")
            else -> throw IOException("This format needs another app. Use “Open externally” for the original file.")
        }
    }
    private fun readText(file: File): String {
        require(file.length() <= MAX_TEXT) { "Text files are limited to 2 MB for a responsive preview." }
        return file.readText(Charsets.UTF_8).removePrefix("\uFEFF")
    }
    private fun chunks(text: String): List<Section> {
        if (text.isBlank()) return listOf(Section("Document", "No readable text was found."))
        return text.chunked(24_000).mapIndexed { i, s -> Section(if (i == 0) "Document" else "Continued · ${i + 1}", s) }
    }
    private class Archive(file: File) : AutoCloseable {
        val zip = ZipFile(file)
        private var readBytes = 0L
        init {
            try {
                require(zip.size() <= 5000) { "This archive has too many entries." }
                var declared = 0L
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.size > 0) declared += entry.size
                    require(declared <= 250L * 1024 * 1024) { "Expanded document exceeds the safety limit." }
                }
            } catch (e: Exception) { zip.close(); throw e }
        }
        fun xml(name: String): Element {
            val entry = zip.getEntry(name) ?: throw IOException("Missing document component: $name")
            require(entry.size <= MAX_XML) { "A document component exceeds the 12 MB limit." }
            val bytes = zip.getInputStream(entry).use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= MAX_XML) {
                    val n = input.read(buffer, 0, minOf(buffer.size, MAX_XML + 1 - output.size()))
                    if (n < 0) break
                    output.write(buffer, 0, n)
                }
                output.toByteArray()
            }
            readBytes += bytes.size
            require(bytes.size <= MAX_XML && readBytes <= 60L * 1024 * 1024) { "Document XML exceeds the safety limit." }
            require(bytes.none { it == 0.toByte() }) { "Only UTF-8 Office XML is supported." }
            val probe = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
            require(!probe.contains("<!DOCTYPE", true) && !probe.contains("<!ENTITY", true)) { "External entities and DTDs are not supported." }
            val encoding = Regex("""<\?xml[^?]*encoding\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
                .find(probe.take(500))?.groupValues?.get(1)
            require(encoding == null || encoding.equals("UTF-8", true) || encoding.equals("US-ASCII", true)) { "Only UTF-8 Office XML is supported." }
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            // Android's bundled DOM implementation lacks some Xerces feature switches.
            // The UTF-8-only, DTD-rejecting preflight above is mandatory on every runtime.
            for (feature in listOf("http://xml.org/sax/features/external-general-entities", "http://xml.org/sax/features/external-parameter-entities")) {
                try { factory.setFeature(feature, false) } catch (_: javax.xml.parsers.ParserConfigurationException) { /* preflight still enforced */ }
            }
            factory.isExpandEntityReferences = false
            val builder = factory.newDocumentBuilder()
            builder.setEntityResolver { _, _ -> throw IOException("External entities are forbidden.") }
            return builder.parse(InputSource(StringReader(probe))).documentElement
        }
        fun has(name: String) = zip.getEntry(name) != null
        override fun close() = zip.close()
    }
    private fun Element.all(local: String): List<Element> {
        val nodes = getElementsByTagNameNS("*", local)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }
    private fun Element.children(local: String? = null): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>()
            .filter { local == null || it.localName == local }
    private fun Element.attr(local: String): String {
        for (i in 0 until attributes.length) if (attributes.item(i).localName == local || attributes.item(i).nodeName == local) return attributes.item(i).nodeValue
        return ""
    }
    private fun bounded(text: String): String {
        require(text.length <= MAX_TEXT) { "The document contains too much text to preview safely." }
        return text
    }
    private fun paragraph(node: Node, out: StringBuilder, depth: Int = 0) {
        require(depth < 128) { "Document nesting is too deep." }
        if (node is Element) when (node.localName) {
            "t" -> { out.append(node.textContent); return }
            "tab" -> { out.append('\t'); return }
            "br", "cr" -> { out.append('\n'); return }
        }
        for (i in 0 until node.childNodes.length) paragraph(node.childNodes.item(i), out, depth + 1)
    }
    private fun word(a: Archive): ParsedDocument {
        val root = a.xml("word/document.xml")
        val text = root.all("p").joinToString("\n\n") { p -> StringBuilder().also { paragraph(p, it) }.toString() }
        return ParsedDocument("DOCX", chunks(bounded(text)), OFFICE_NOTICE + " Body text and table-cell text only.")
    }
    private fun relationships(a: Archive, path: String): Map<String, String> =
        if (!a.has(path)) emptyMap() else a.xml(path).all("Relationship")
            .filter { it.attr("TargetMode") != "External" }
            .associate { it.attr("Id") to it.attr("Target") }
    private fun resolve(base: String, target: String): String {
        val parts = mutableListOf<String>()
        val path = if (target.startsWith("/")) target.drop(1) else base.substringBeforeLast('/') + "/" + target
        path.split('/').forEach {
            when (it) {
                "", "." -> Unit
                ".." -> { require(parts.isNotEmpty()) { "Invalid package relationship." }; parts.removeAt(parts.lastIndex) }
                else -> parts.add(it)
            }
        }
        return parts.joinToString("/")
    }
    private fun workbook(a: Archive): ParsedDocument {
        var totalChars = 0L
        var totalCells = 0
        val strings = if (a.has("xl/sharedStrings.xml")) a.xml("xl/sharedStrings.xml").all("si").map {
            bounded(it.all("t").joinToString("") { t -> t.textContent })
        } else emptyList()
        val rel = relationships(a, "xl/_rels/workbook.xml.rels")
        val sections = a.xml("xl/workbook.xml").all("sheet").take(100).map { sheet ->
            val target = rel[sheet.attr("id")] ?: throw IOException("Missing worksheet relationship.")
            val root = a.xml(resolve("xl/workbook.xml", target))
            val rows = root.all("row").take(MAX_ROWS).map { row ->
                val cells = mutableMapOf<Int, String>()
                row.children("c").take(MAX_COLS).forEach { c ->
                    val col = column(c.attr("r"))
                    if (col in 0 until MAX_COLS) {
                        val value = c.children("v").firstOrNull()?.textContent ?: ""
                        cells[col] = when (c.attr("t")) {
                            "s" -> strings.getOrNull(value.toIntOrNull() ?: -1) ?: ""
                            "inlineStr" -> c.all("t").joinToString("") { it.textContent }
                            "b" -> if (value == "1") "TRUE" else "FALSE"
                            else -> value.ifEmpty { c.children("f").firstOrNull()?.textContent?.let { "=$it" } ?: "" }
                        }
                        totalChars += cells[col]?.length ?: 0
                        totalCells++
                        require(totalChars <= MAX_TEXT && totalCells <= 200_000) { "Workbook exceeds the 200,000-cell or 2 MB text preview limit." }
                    }
                }
                List((cells.keys.maxOrNull() ?: -1) + 1) { cells[it] ?: "" }
            }
            Section(sheet.attr("name").ifEmpty { "Sheet" }, rows = rows)
        }
        return ParsedDocument("XLSX", sections, "Values-only preview · first 100 sheets, 2,000 existing rows and 100 columns per sheet. Dates may appear as serial numbers. Formulas use stored values and are never calculated.")
    }
    internal fun column(reference: String): Int {
        var result = 0
        for (c in reference.takeWhile { it.isLetter() }.take(4)) result = result * 26 + (c.uppercaseChar() - 'A' + 1)
        return result - 1
    }
    private fun slides(a: Archive): ParsedDocument {
        val rel = relationships(a, "ppt/_rels/presentation.xml.rels")
        val slides = a.xml("ppt/presentation.xml").all("sldId").take(500).mapIndexed { i, slide ->
            val relationshipId = slide.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id")
            val target = rel[relationshipId] ?: throw IOException("Missing slide relationship.")
            val root = a.xml(resolve("ppt/presentation.xml", target))
            val text = root.all("p").joinToString("\n") { it.all("t").joinToString("") { t -> t.textContent } }
            Section("Slide ${i + 1}", bounded(text).ifBlank { "No text on this slide. Images and graphics are not shown." })
        }
        return ParsedDocument("PPTX", slides, "$OFFICE_NOTICE First 500 slides, in presentation order. Animations and speaker notes are omitted.")
    }
    private fun openDocument(a: Archive, ext: String): ParsedDocument {
        val root = a.xml("content.xml")
        var totalCells = 0
        var totalChars = 0L
        val sections = when (ext) {
            "ods" -> root.all("table").take(100).map { table ->
                val rows = mutableListOf<List<String>>()
                for (row in table.children("table-row")) {
                    if (rows.size >= MAX_ROWS) break
                    val cells = mutableListOf<String>()
                    for (cell in row.children()) {
                        if (cell.localName !in setOf("table-cell", "covered-table-cell")) continue
                        val value = cell.all("p").joinToString("\n") { it.textContent }.ifEmpty { cell.attr("value") }
                        val repeat = (cell.attr("number-columns-repeated").toIntOrNull() ?: 1).coerceIn(1, MAX_COLS - cells.size)
                        repeat(repeat) { cells.add(value) }
                        if (cells.size >= MAX_COLS) break
                    }
                    val repeat = (row.attr("number-rows-repeated").toIntOrNull() ?: 1).coerceIn(1, MAX_ROWS - rows.size)
                    totalCells += cells.size * repeat
                    totalChars += cells.sumOf { it.length.toLong() } * repeat
                    require(totalCells <= 200_000 && totalChars <= MAX_TEXT) { "Workbook exceeds the 200,000-cell or 2 MB text preview limit." }
                    repeat(repeat) { rows.add(cells.toList()) }
                }
                Section(table.attr("name").ifBlank { "Sheet" }, rows = rows)
            }
            "odp" -> root.all("page").take(500).mapIndexed { i, page -> Section("Slide ${i + 1}", page.all("p").joinToString("\n") { it.textContent }) }
            else -> chunks(bounded(root.all("p").joinToString("\n\n") { it.textContent }))
        }
        return ParsedDocument(ext.uppercase(), sections, OFFICE_NOTICE + " Spreadsheets are limited to 100 sheets, 2,000 rows and 100 columns; presentations to 500 slides.")
    }
    internal fun parseDelimited(text: String, delimiter: Char): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val value = StringBuilder()
        var quoted = false
        var i = 0
        fun field() { if (row.size < MAX_COLS) row.add(value.toString()); value.setLength(0) }
        fun line() { field(); rows.add(row); row = mutableListOf() }
        while (i < text.length && rows.size < MAX_ROWS) {
            val c = text[i]
            when {
                c == '"' && quoted && i + 1 < text.length && text[i + 1] == '"' -> { value.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == delimiter && !quoted -> field()
                (c == '\n' || c == '\r') && !quoted -> { line(); if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++ }
                else -> value.append(c)
            }
            i++
        }
        if (rows.size < MAX_ROWS && (value.isNotEmpty() || row.isNotEmpty())) line()
        return rows
    }
    internal fun rtfText(input: String): String {
        data class State(val skip: Boolean, val uc: Int)
        val stack = java.util.ArrayDeque<State>()
        var skip = false
        var uc = 1
        var fallback = 0
        val out = StringBuilder()
        var i = 0
        while (i < input.length) {
            when (val ch = input[i++]) {
                '{' -> { require(stack.size < 200) { "RTF nesting limit exceeded." }; stack.push(State(skip, uc)) }
                '}' -> if (stack.isNotEmpty()) { val s = stack.pop(); skip = s.skip; uc = s.uc }
                '\\' -> {
                    if (i >= input.length) break
                    val c = input[i++]
                    if (c in "\\{}") { if (fallback > 0) fallback-- else if (!skip) out.append(c) }
                    else if (c == '*') skip = true
                    else if (c == '\'' && i + 1 < input.length) {
                        val n = input.substring(i, i + 2).toIntOrNull(16)
                        if (fallback > 0) fallback-- else if (!skip && n != null) out.append(byteArrayOf(n.toByte()).toString(charset("windows-1252")))
                        i += 2
                    } else if (c.isLetter()) {
                        val start = i - 1
                        while (i < input.length && input[i].isLetter()) i++
                        val word = input.substring(start, i)
                        val numberStart = i
                        if (i < input.length && input[i] == '-') i++
                        while (i < input.length && input[i].isDigit()) i++
                        val n = input.substring(numberStart, i).toIntOrNull()
                        if (i < input.length && input[i] == ' ') i++
                        when (word) {
                            "fonttbl", "colortbl", "stylesheet", "info", "pict", "object", "header", "footer", "fldinst" -> skip = true
                            "uc" -> uc = (n ?: 1).coerceIn(0, 10)
                            "u" -> if (!skip && n != null) { out.append(n.toChar()); fallback = uc }
                            "par", "line" -> if (!skip) out.append('\n')
                            "tab" -> if (!skip) out.append('\t')
                            "bin" -> { i = (i.toLong() + (n ?: 0).coerceAtLeast(0)).coerceAtMost(input.length.toLong()).toInt() }
                        }
                    } else if (c == '~' && !skip) out.append(' ')
                }
                '\r', '\n' -> Unit
                else -> if (fallback > 0) fallback-- else if (!skip) out.append(ch)
            }
        }
        return bounded(out.toString())
    }
}
