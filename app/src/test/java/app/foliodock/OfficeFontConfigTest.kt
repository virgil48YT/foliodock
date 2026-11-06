package app.foliodock

import org.junit.Assert.*
import org.junit.Test

class OfficeFontConfigTest {
    private val original = """<?xml version="1.0"?>
        <!DOCTYPE fontconfig SYSTEM "fonts.dtd">
        <fontconfig>
          <dir>/system/fonts</dir>
          <alias><family>serif</family><prefer><family>Droid Serif</family></prefer></alias>
          <cachedir>/data/data/org.documentfoundation.libreoffice/fontconfig</cachedir>
        </fontconfig>""".trimIndent()

    @Test fun replacesOriginalPackageCacheAndAddsBundledFonts() {
        val configured = OfficeFontConfig.configure(original, "/data/user/0/app.foliodock/fontconfig", "/data/user/0/app.foliodock/user/fonts")
        assertTrue(configured.contains("<cachedir>/data/user/0/app.foliodock/fontconfig</cachedir>"))
        assertTrue(configured.contains("<dir>/data/user/0/app.foliodock/user/fonts</dir>"))
        assertFalse(configured.contains("org.documentfoundation.libreoffice"))
        assertTrue(configured.contains("<dir>/system/fonts</dir>"))
        assertTrue(configured.contains("<family>Droid Serif</family>"))
        assertTrue(configured.contains("""<!DOCTYPE fontconfig SYSTEM "fonts.dtd">"""))
        assertTrue(original.contains("org.documentfoundation.libreoffice"))
    }
    @Test fun pathsAreXmlEscaped() {
        val configured = OfficeFontConfig.configure(original, "/cache/a&b", "/fonts/<test>\"'")
        assertTrue(configured.contains("<cachedir>/cache/a&amp;b</cachedir>"))
        assertTrue(configured.contains("<dir>/fonts/&lt;test&gt;&quot;&apos;</dir>"))
    }
    @Test fun configurationFromOriginalIsStableAcrossStarts() {
        assertEquals(OfficeFontConfig.configure(original, "/cache", "/fonts"), OfficeFontConfig.configure(original, "/cache", "/fonts"))
    }
    @Test fun rejectsMissingStructureAndRelativeDirectories() {
        assertThrows(IllegalArgumentException::class.java) { OfficeFontConfig.configure("<fontconfig/>", "/cache", "/fonts") }
        assertThrows(IllegalArgumentException::class.java) { OfficeFontConfig.configure(original, "cache", "/fonts") }
    }
}
