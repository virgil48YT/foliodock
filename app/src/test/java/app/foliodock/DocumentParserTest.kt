package app.foliodock

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocumentParserTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun archive(vararg entries: Pair<String, String>): File {
        val file = temporary.newFile()
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, value) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(value.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }
    private fun word(text: String) = archive("word/document.xml" to """<w:document xmlns:w="urn:word"><w:body>$text</w:body></w:document>""")
    private fun parse(file: File, name: String) = DocumentParser.parse(file, name)
    @Test fun docxParagraphsTablesAndEscapedCharacters() {
        val result = parse(word("""<w:p><w:r><w:t>Hello &amp; welcome</w:t><w:tab/><w:t>friend</w:t></w:r></w:p><w:tbl><w:tr><w:tc><w:p><w:r><w:t>Cell</w:t></w:r></w:p></w:tc></w:tr></w:tbl>"""), "hello.DOCX")
        assertEquals("DOCX", result.kind)
        assertEquals("Hello & welcome\tfriend\n\nCell", result.sections.single().text)
    }
    @Test fun docxEmptyTextProducesHelpfulMessage() {
        assertTrue(parse(word("<w:p/>"), "empty.docx").sections.single().text.contains("No readable text"))
    }
    @Test fun rejectsDoctypeAndEntities() {
        val file = archive("word/document.xml" to """<!DOCTYPE root [<!ENTITY x SYSTEM "file:///etc/passwd">]><root>&x;</root>""")
        val e = assertThrows(IllegalArgumentException::class.java) { parse(file, "bad.docx") }
        assertTrue(e.message!!.contains("entities"))
    }
    @Test fun rejectsUtf16XmlBeforeParser() {
        val file = temporary.newFile()
        ZipOutputStream(file.outputStream()).use {
            it.putNextEntry(ZipEntry("word/document.xml"))
            it.write("""<?xml version="1.0" encoding="UTF-16"?><root/>""".toByteArray(Charsets.UTF_16))
            it.closeEntry()
        }
        assertThrows(IllegalArgumentException::class.java) { parse(file, "utf16.docx") }
    }
    @Test fun rejectsDifferentDeclaredEncoding() {
        val file = archive("word/document.xml" to """<?xml version="1.0" encoding="ISO-8859-1"?><root/>""")
        assertThrows(IllegalArgumentException::class.java) { parse(file, "latin.docx") }
    }
    @Test fun rejectsOversizedXmlEntry() {
        val file = archive("word/document.xml" to "x".repeat(12 * 1024 * 1024 + 1))
        assertThrows(IllegalArgumentException::class.java) { parse(file, "large.docx") }
    }
    @Test fun rejectsDeepParagraphNesting() {
        val file = word("<w:p>" + "<w:r>".repeat(140) + "<w:t>Hidden</w:t>" + "</w:r>".repeat(140) + "</w:p>")
        assertThrows(IllegalArgumentException::class.java) { parse(file, "deep.docx") }
    }
    @Test fun missingComponentIsAnError() {
        assertThrows(java.io.IOException::class.java) { parse(archive("random.xml" to "<x/>"), "missing.docx") }
    }
    @Test fun csvQuotesNewlinesEscapedQuotesAndEmptyCells() {
        val rows = DocumentParser.parseDelimited("name,note,tail\r\nAda,\"line 1\nline \"\"2\"\"\",\r\n", ',')
        assertEquals(listOf("name", "note", "tail"), rows[0])
        assertEquals(listOf("Ada", "line 1\nline \"2\"", ""), rows[1])
        assertEquals(2, rows.size)
    }
    @Test fun csvCapsRowsAndColumns() {
        val line = (1..150).joinToString(",")
        val rows = DocumentParser.parseDelimited((1..2100).joinToString("\n") { line }, ',')
        assertEquals(2000, rows.size)
        assertEquals(100, rows.first().size)
    }
    @Test fun tsvSupportsQuotedTab() {
        assertEquals(listOf(listOf("x", "a\tb")), DocumentParser.parseDelimited("x\t\"a\tb\"", '\t'))
    }
    @Test fun columnReferences() {
        assertEquals(0, DocumentParser.column("A1"))
        assertEquals(25, DocumentParser.column("Z9"))
        assertEquals(26, DocumentParser.column("AA10"))
        assertEquals(99, DocumentParser.column("CV12"))
    }
    @Test fun xlsxSharedStringsSparseCellsAndFormulaCache() {
        val file = archive(
            "xl/workbook.xml" to """<workbook xmlns:r="urn:relationships"><sheets><sheet name="Budget" r:id="r1"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<Relationships><Relationship Id="r1" Target="worksheets/sheet1.xml"/></Relationships>""",
            "xl/sharedStrings.xml" to """<sst><si><t>Rent</t></si></sst>""",
            "xl/worksheets/sheet1.xml" to """<worksheet><sheetData><row r="1"><c r="A1" t="s"><v>0</v></c><c r="C1"><f>1+2</f><v>3</v></c><c r="D1" t="b"><v>1</v></c></row></sheetData></worksheet>"""
        )
        val section = parse(file, "budget.xlsx").sections.single()
        assertEquals("Budget", section.title)
        assertEquals(listOf("Rent", "", "3", "TRUE"), section.rows.single())
    }
    @Test fun presentationUsesRelationshipOrderNotFileNamesOrNumericId() {
        val file = archive(
            "ppt/presentation.xml" to """<p:presentation xmlns:p="urn:p" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><p:sldIdLst><p:sldId id="256" r:id="r2"/><p:sldId id="257" r:id="r1"/></p:sldIdLst></p:presentation>""",
            "ppt/_rels/presentation.xml.rels" to """<Relationships><Relationship Id="r1" Target="slides/slide1.xml"/><Relationship Id="r2" Target="slides/slide2.xml"/></Relationships>""",
            "ppt/slides/slide1.xml" to """<slide><p><t>Second</t></p></slide>""",
            "ppt/slides/slide2.xml" to """<slide><p><t>First</t></p></slide>"""
        )
        assertEquals(listOf("First", "Second"), parse(file, "slides.pptx").sections.map { it.text })
    }
    @Test fun doesNotFollowExternalRelationship() {
        val file = archive(
            "xl/workbook.xml" to """<workbook xmlns:r="urn:r"><sheet name="Bad" r:id="r1"/></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<Relationships><Relationship Id="r1" Target="https://evil.invalid/" TargetMode="External"/></Relationships>"""
        )
        assertThrows(java.io.IOException::class.java) { parse(file, "external.xlsx") }
    }
    @Test fun odtReadsParagraphs() {
        val file = archive("content.xml" to """<office:document xmlns:office="urn:o" xmlns:text="urn:t"><text:p>A <text:span>small</text:span> document</text:p></office:document>""")
        assertEquals("A small document", parse(file, "letter.odt").sections.single().text)
    }
    @Test fun odsHandlesBoundedRepeatingCells() {
        val file = archive("content.xml" to """<root xmlns:table="urn:t" xmlns:text="urn:p"><table:table table:name="Data"><table:table-row table:number-rows-repeated="2"><table:table-cell table:number-columns-repeated="3"><text:p>A</text:p></table:table-cell></table:table-row></table:table></root>""")
        assertEquals(listOf(listOf("A", "A", "A"), listOf("A", "A", "A")), parse(file, "data.ods").sections.single().rows)
    }
    @Test fun odpHasSeparateSlides() {
        val file = archive("content.xml" to """<root><page><p>One</p></page><page><p>Two</p></page></root>""")
        assertEquals(listOf("One", "Two"), parse(file, "deck.odp").sections.map { it.text })
    }
    @Test fun rtfStripsFormattingDestinationsAndHandlesUnicode() {
        val text = DocumentParser.rtfText("""{\rtf1\ansi{\fonttbl{\f0 Arial;}}Hello \b world\b0\par Unicode \uc1\u233? {\pict 000ABC}end}""")
        assertEquals("Hello world\nUnicode é end", text)
    }
    @Test fun rtfEscapedBracesAndAnsiHex() {
        assertEquals("{café}", DocumentParser.rtfText("""{\rtf1 \{caf\'e9\}}"""))
    }
    @Test fun textPreservesLiteralMarkupAndUnicode() {
        val file = temporary.newFile().apply { writeText("\uFEFF<script>Literal</script>\nRésumé") }
        assertEquals("<script>Literal</script>\nRésumé", parse(file, "file.txt").sections.single().text)
    }
    @Test fun plainTextOverLimitRejected() {
        val file = temporary.newFile().apply { writeText("a".repeat(2_000_001)) }
        assertThrows(IllegalArgumentException::class.java) { parse(file, "huge.txt") }
    }
    @Test fun legacyFormatsDoNotPretendToBeSupported() {
        assertThrows(java.io.IOException::class.java) { parse(temporary.newFile(), "old.doc") }
    }
}
