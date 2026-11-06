package app.foliodock

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/** A read-only sharing endpoint restricted to this app's imported documents. */
class DocumentProvider : ContentProvider() {
    override fun onCreate() = true
    private fun file(uri: Uri): File {
        val name = uri.lastPathSegment ?: throw FileNotFoundException()
        val root = File(requireNotNull(context).filesDir, "documents").canonicalFile
        val candidate = File(root, name).canonicalFile
        if (candidate.parentFile != root || !candidate.isFile || name.endsWith(".part")) throw FileNotFoundException()
        return candidate
    }
    override fun getType(uri: Uri): String = MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(DocumentParser.extension(file(uri).name)) ?: "application/octet-stream"
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Read-only document provider")
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = file(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val display = requireNotNull(context).getSharedPreferences("library", 0).getString("name_${f.name}", f.name) ?: f.name
        return MatrixCursor(columns).apply {
            addRow(columns.map { when (it) { OpenableColumns.DISPLAY_NAME -> display; OpenableColumns.SIZE -> f.length(); else -> null } }.toTypedArray())
        }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw SecurityException("Read only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw SecurityException("Read only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw SecurityException("Read only")
}
