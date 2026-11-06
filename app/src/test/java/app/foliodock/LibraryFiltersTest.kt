package app.foliodock

import org.junit.Assert.*
import org.junit.Test

class LibraryFiltersTest {
    @Test fun directFormatsDoNotConflateFamilies() {
        assertTrue(LibraryFilters.matches("DOCX", "REPORT.DOCX"))
        assertFalse(LibraryFilters.matches("DOCX", "report.doc"))
        assertTrue(LibraryFilters.matches("PPTX", "slides.pptx"))
        assertFalse(LibraryFilters.matches("PDF", "slides.pptx"))
    }
    @Test fun wordExcelPowerPointIncludeRelatedFormats() {
        assertTrue(LibraryFilters.matches("Word", "letter.odt"))
        assertTrue(LibraryFilters.matches("Excel", "budget.xlsb"))
        assertTrue(LibraryFilters.matches("PowerPoint", "deck.ppsx"))
        assertFalse(LibraryFilters.matches("Word", "deck.pptx"))
    }
    @Test fun allAndTextExtensionsWork() {
        assertTrue(LibraryFilters.matches("All", "any.file"))
        assertTrue(LibraryFilters.matches("CSV", "table.CSV"))
        assertFalse(LibraryFilters.matches("CSV", "table.tsv"))
        assertFalse(LibraryFilters.labels.contains("Office"))
    }
}
