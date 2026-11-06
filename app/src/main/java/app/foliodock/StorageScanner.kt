package app.foliodock

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.ArrayDeque

data class DeviceDocument(val path: String, val size: Long, val modified: Long) {
    val name: String get() = path.substringAfterLast(File.separatorChar)
}

/** Read-only traversal. Never follows a symlink outside a supplied shared-storage root. */
object StorageScanner {
    const val MAX_DOCUMENTS = 50_000
    data class Result(
        val documents: List<DeviceDocument>, val inspected: Long, val skippedDirectories: Int,
        val cancelled: Boolean, val limitReached: Boolean
    )
    fun supports(name: String) = OfficeFormats.supports(name) ||
        name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT) in setOf("pdf", "txt", "md", "csv", "tsv")

    fun scan(roots: List<File>, cancelled: () -> Boolean = { false }): Result {
        val found = ArrayList<DeviceDocument>()
        val seen = HashSet<String>()
        var inspected = 0L
        var skipped = 0
        for (root in roots) {
            val base = try { root.canonicalFile } catch (_: IOException) { skipped++; continue }
            val stack = ArrayDeque<File>()
            stack.add(base)
            while (stack.isNotEmpty()) {
                if (cancelled()) return Result(found, inspected, skipped, true, false)
                val folder = stack.removeLast()
                val canonical = try { folder.canonicalFile } catch (_: IOException) { skipped++; continue }
                if (canonical != base && !canonical.path.startsWith(base.path + File.separator)) continue
                if (!seen.add(canonical.path)) continue
                val relative = canonical.relativeTo(base).invariantSeparatorsPath
                if (relative == "Android/data" || relative.startsWith("Android/data/") ||
                    relative == "Android/obb" || relative.startsWith("Android/obb/")) continue
                try {
                    Files.newDirectoryStream(canonical.toPath()).use { entries ->
                        for (entry in entries) {
                            if (cancelled()) return Result(found, inspected, skipped, true, false)
                            // Do not follow directory/file links, even when their target is within a root.
                            if (Files.isSymbolicLink(entry)) continue
                            val file = entry.toFile()
                            if (file.isDirectory) stack.add(file)
                            else if (file.isFile) {
                                inspected++
                                if (supports(file.name) && file.canRead()) {
                                    found.add(DeviceDocument(file.absolutePath, file.length(), file.lastModified()))
                                    if (found.size >= MAX_DOCUMENTS)
                                        return Result(found, inspected, skipped, false, true)
                                }
                            }
                        }
                    }
                } catch (_: IOException) { skipped++ }
                catch (_: SecurityException) { skipped++ }
            }
        }
        return Result(found, inspected, skipped, false, false)
    }
}

/** Bounded, app-private persistent index; file contents are never duplicated by a scan. */
object DeviceIndex {
    private const val MAGIC = 0x46444931
    fun read(file: File): List<DeviceDocument> {
        if (!file.isFile) return emptyList()
        require(file.length() <= 64L * 1024 * 1024) { "Stored index is too large." }
        return DataInputStream(file.inputStream().buffered()).use { input ->
            require(input.readInt() == MAGIC) { "Unsupported device index." }
            val count = input.readInt()
            require(count in 0..StorageScanner.MAX_DOCUMENTS)
            List(count) {
                val path = input.readUTF()
                val size = input.readLong()
                val modified = input.readLong()
                require(File(path).isAbsolute && size >= 0 && modified >= 0)
                DeviceDocument(path, size, modified)
            }
        }
    }
    fun write(file: File, documents: List<DeviceDocument>) {
        require(documents.size <= StorageScanner.MAX_DOCUMENTS)
        val temporary = File(file.parentFile, file.name + ".part")
        try {
            DataOutputStream(temporary.outputStream().buffered()).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(documents.size)
                documents.forEach {
                    require(File(it.path).isAbsolute && it.size >= 0 && it.modified >= 0)
                    output.writeUTF(it.path)
                    output.writeLong(it.size)
                    output.writeLong(it.modified)
                }
            }
            check(temporary.renameTo(file)) { "Could not save the device index." }
        } finally { temporary.delete() }
    }
}
