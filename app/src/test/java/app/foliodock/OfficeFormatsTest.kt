package app.foliodock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficeFormatsTest {
    @Test fun primaryOfficeFamiliesUseLayoutRenderer() {
        listOf("doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf").forEach {
            assertTrue("Expected native layout for $it", OfficeFormats.supports("example.$it"))
        }
    }

    @Test fun extensionMatchingIsCaseInsensitive() {
        assertTrue(OfficeFormats.supports("Document.DOCX"))
        assertTrue(OfficeFormats.supports("Slides.PPTX"))
    }

    @Test fun pdfAndPlainTextKeepTheirDedicatedReaders() {
        listOf("pdf", "txt", "csv", "md", "zip", "apk").forEach {
            assertFalse(OfficeFormats.supports("example.$it"))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun extremeNativeCoordinatesCannotOverflowValidation() {
        OfficePage(Long.MAX_VALUE, 0, Long.MAX_VALUE, 1)
    }
}
