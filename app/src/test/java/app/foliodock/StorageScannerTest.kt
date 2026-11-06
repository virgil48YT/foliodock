package app.foliodock

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class StorageScannerTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun discoversNestedDocumentsAndIgnoresOtherFiles() {
        val root = temporary.newFolder("shared")
        File(root, "Download/deep").mkdirs()
        File(root, "Download/deep/DECK.PPTX").writeText("fixture")
        File(root, "book.pdf").writeText("fixture")
        File(root, "photo.jpg").writeText("fixture")
        val result = StorageScanner.scan(listOf(root))
        assertEquals(setOf("DECK.PPTX", "book.pdf"), result.documents.map { it.name }.toSet())
        assertEquals(3, result.inspected.toInt())
        assertFalse(result.cancelled)
    }
    @Test fun skipsProtectedAndroidFoldersButIncludesMedia() {
        val root = temporary.newFolder("storage")
        listOf("Android/data", "Android/obb", "Android/media").forEach {
            File(root, it).mkdirs()
            File(root, "$it/test.docx").writeText("fixture")
        }
        val result = StorageScanner.scan(listOf(root))
        assertEquals(1, result.documents.size)
        assertTrue(result.documents.single().path.contains("Android/media/"))
    }
    @Test fun doesNotFollowLinksOrDuplicateOverlappingRoots() {
        val root = temporary.newFolder("root")
        val outside = temporary.newFolder("outside")
        File(root, "nested").mkdirs()
        File(root, "nested/sheet.xlsx").writeText("fixture")
        File(outside, "secret.pdf").writeText("fixture")
        Files.createSymbolicLink(File(root, "escape").toPath(), outside.toPath())
        Files.createSymbolicLink(File(root, "cycle").toPath(), root.toPath())
        val result = StorageScanner.scan(listOf(root, File(root, "nested"), root))
        assertEquals(listOf("sheet.xlsx"), result.documents.map { it.name })
    }
    @Test fun cancellationStopsTraversal() {
        val root = temporary.newFolder("cancel")
        File(root, "one.docx").writeText("fixture")
        assertTrue(StorageScanner.scan(listOf(root)) { true }.cancelled)
    }
    @Test fun neverChangesOriginalContent() {
        val root = temporary.newFolder("original")
        val source = File(root, "file.doc")
        source.writeText("untouched")
        val before = source.lastModified()
        StorageScanner.scan(listOf(root))
        assertEquals("untouched", source.readText())
        assertEquals(before, source.lastModified())
    }
    @Test fun indexRoundTripPreservesUnicodeAndNewlines() {
        val file = temporary.newFile("index")
        val entries = listOf(DeviceDocument("/storage/文档/a\nb.pdf", 42, 123))
        DeviceIndex.write(file, entries)
        assertEquals(entries, DeviceIndex.read(file))
        DeviceIndex.write(file, emptyList())
        assertTrue(DeviceIndex.read(file).isEmpty())
    }
    @Test fun malformedIndexIsRejected() {
        val file = temporary.newFile("bad")
        file.writeText("invalid")
        assertThrows(Exception::class.java) { DeviceIndex.read(file) }
    }
}
