package app.foliodock

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import org.libreoffice.kit.Document
import org.libreoffice.kit.LibreOfficeKit
import org.libreoffice.kit.Office
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

data class OfficeInfo(val type: Int, val pages: List<OfficePage>, val names: List<String>) {
    val count: Int get() = if (type == Document.DOCTYPE_TEXT) pages.size else names.size
}

/**
 * Original FolioDock integration code. Native initialization, loading, painting, part changes,
 * and destruction are confined to one process-lifetime worker. Office.destroy() is never called:
 * LibreOfficeKit is a process singleton, while document handles are explicitly released.
 */
object OfficeRuntime {
    class Session internal constructor(val id: Long) {
        @Volatile internal var closed = false
        @Volatile internal var generation = 0
        @Volatile internal var wanted = emptySet<OfficeTileKey>()
        internal var document: Document? = null // worker thread only
        internal var info: OfficeInfo? = null
        internal var selectedPart = -1
    }
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "FolioDock-Office").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val main = Handler(Looper.getMainLooper())
    private val serial = AtomicLong()
    private var office: Office? = null
    private var active: Session? = null
    private var attempted = false
    private var initializationFailure: String? = null
    private var pixels: ByteBuffer? = null

    fun open(activity: Activity, file: File, callback: (Result<Pair<Session, OfficeInfo>>) -> Unit): Session {
        val session = Session(serial.incrementAndGet())
        executor.execute {
            try {
                if (session.closed) return@execute
                active?.let { destroy(it) }
                val engine = initialize(activity)
                if (session.closed) return@execute
                val document = engine.documentLoad(file.toURI().toASCIIString())
                    ?: error(engine.error?.takeIf { it.isNotBlank() } ?: "LibreOffice could not open this document. It may be encrypted or damaged.")
                session.document = document
                active = session
                if (session.closed) { destroy(session); return@execute }
                document.initializeForRendering()
                val type = document.documentType
                val pages = if (type == Document.DOCTYPE_TEXT) {
                    OfficeGeometry.parsePageRectangles(document.partPageRectangles ?: "").ifEmpty {
                        listOf(geometry(document, 0, "Document"))
                    }
                } else emptyList()
                val names = if (type == Document.DOCTYPE_TEXT) emptyList() else {
                    val count = document.parts
                    require(count in 1..10000) { "Document contains an unsupported number of sheets or slides." }
                    (0 until count).map { index ->
                        document.getPartName(index)?.takeIf { it.isNotBlank() }
                            ?: "${if (type == Document.DOCTYPE_SPREADSHEET) "Sheet" else "Slide"} ${index + 1}"
                    }
                }
                val info = OfficeInfo(type, pages, names)
                session.info = info
                main.post { if (!session.closed) callback(Result.success(session to info)) }
            } catch (failure: Throwable) {
                destroy(session)
                main.post { if (!session.closed) callback(Result.failure(readable(failure))) }
            }
        }
        return session
    }

    private fun initialize(activity: Activity): Office {
        office?.let { return it }
        if (attempted) error(initializationFailure ?: "The Office engine could not initialize. Restart FolioDock to try again.")
        attempted = true
        try {
            val data = File(activity.applicationInfo.dataDir)
            val assetVersion = "26.2.6.3-8221e31b3ac356a1623c672912a3d2b492f7e3d1"
            val marker = File(data, "lo-assets.version")
            if (!marker.isFile || marker.readText() != assetVersion) {
                require(!activity.assets.list("unpack").isNullOrEmpty()) { "The Office engine assets are missing. Reinstall FolioDock." }
                fun unpack(path: String, destination: File) {
                    val children = activity.assets.list(path) ?: emptyArray()
                    if (children.isNotEmpty()) {
                        require(destination.isDirectory || destination.mkdirs()) { "Could not prepare Office engine storage." }
                        children.forEach { unpack("$path/$it", File(destination, it)) }
                    } else {
                        destination.parentFile?.mkdirs()
                        val temp = File(destination.parentFile, "${destination.name}.lo-install")
                        activity.assets.open(path).use { input -> temp.outputStream().use { input.copyTo(it) } }
                        require(temp.renameTo(destination)) { "Could not install Office engine assets." }
                    }
                }
                unpack("unpack", data)
                marker.writeText(assetVersion)
            }
            val profile = File(data, "lo-profile")
            val user = File(profile, "user").apply { mkdirs() }
            // No trusted macro locations are configured. VeryHigh refuses untrusted document macros.
            File(user, "registrymodifications.xcu").writeText(
                """<?xml version="1.0" encoding="UTF-8"?>
                    <oor:items xmlns:oor="http://openoffice.org/2001/registry">
                      <item oor:path="/org.openoffice.Office.Common/Security/Scripting">
                        <prop oor:name="MacroSecurityLevel" oor:op="fuse"><value>3</value></prop>
                        <prop oor:name="DisableMacrosExecution" oor:op="fuse"><value>true</value></prop>
                      </item>
                    </oor:items>""".trimIndent()
            )
            val program = File(data, "program").apply { mkdirs() }
            val bootstrap = File(program, "sofficerc")
            val original = if (bootstrap.isFile) bootstrap.readText() else "[Bootstrap]\nURE_BOOTSTRAP=file:///assets/program/fundamentalrc\n"
            bootstrap.writeText(original.lineSequence().filterNot { it.trimStart().startsWith("UserInstallation=") }
                .joinToString("\n") + "\nUserInstallation=${profile.toURI().toASCIIString()}\n")
            LibreOfficeKit.init(activity)
            val handle = LibreOfficeKit.getLibreOfficeKitHandle() ?: error("The Office engine did not return a valid handle.")
            return Office(handle).also {
                // Password callbacks are blocking without a response. Do not request them.
                it.setOptionalFeatures(0L)
                office = it
            }
        } catch (failure: Throwable) {
            initializationFailure = failure.message ?: failure.javaClass.simpleName
            throw failure
        }
    }

    fun page(session: Session, index: Int, callback: (Result<OfficePage>) -> Unit) {
        executor.execute {
            try {
                if (!usable(session)) return@execute
                val doc = requireNotNull(session.document)
                val info = requireNotNull(session.info)
                require(index in 0 until info.count)
                val page = if (info.type == Document.DOCTYPE_TEXT) {
                    if (session.selectedPart != 0) { doc.setPart(0); session.selectedPart = 0 }
                    info.pages[index]
                } else {
                    doc.setPart(index)
                    session.selectedPart = index
                    geometry(doc, index, info.names[index])
                }
                main.post { if (!session.closed) callback(Result.success(page)) }
            } catch (failure: Throwable) {
                main.post { if (!session.closed) callback(Result.failure(readable(failure))) }
            }
        }
    }

    fun render(session: Session, page: OfficePage, tile: OfficeTile, generation: Int, callback: (Result<Bitmap?>) -> Unit) {
        executor.execute {
            var bitmap: Bitmap? = null
            try {
                if (!usable(session) || session.generation != generation || tile.key !in session.wanted) {
                    main.post { callback(Result.success(null)) }
                    return@execute
                }
                require(tile.pixelsWide.toLong() * tile.pixelsHigh <= 4_000_000) { "Tile exceeds the pixel safety limit." }
                val doc = requireNotNull(session.document)
                if (session.selectedPart != page.part) { doc.setPart(page.part); session.selectedPart = page.part }
                val bytes = tile.pixelsWide * tile.pixelsHigh * 4
                var buffer = pixels
                if (buffer == null || buffer.capacity() < bytes) {
                    buffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
                    pixels = buffer
                }
                buffer.clear()
                buffer.limit(bytes)
                doc.setClientZoom(tile.pixelsWide, tile.pixelsHigh, tile.width, tile.height)
                doc.paintTile(buffer, tile.pixelsWide, tile.pixelsHigh,
                    (page.x + tile.x).toInt(), (page.y + tile.y).toInt(), tile.width, tile.height)
                buffer.position(0)
                bitmap = Bitmap.createBitmap(tile.pixelsWide, tile.pixelsHigh, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(buffer)
                val result = bitmap
                bitmap = null
                main.post {
                    if (session.closed || session.generation != generation) {
                        result.recycle()
                        callback(Result.success(null))
                    } else callback(Result.success(result))
                }
            } catch (failure: Throwable) {
                bitmap?.recycle()
                main.post { if (!session.closed) callback(Result.failure(readable(failure))) else callback(Result.success(null)) }
            }
        }
    }

    fun close(session: Session) {
        session.closed = true
        session.wanted = emptySet()
        executor.execute { destroy(session) }
    }
    private fun usable(session: Session) = !session.closed && active === session && session.document != null
    private fun destroy(session: Session) {
        val doc = session.document
        session.document = null
        session.info = null
        try { doc?.setMessageCallback(null); doc?.destroy() } catch (_: Throwable) { /* never reuse a released document */ }
        if (active === session) active = null
    }
    private fun geometry(doc: Document, part: Int, label: String) =
        OfficePage(0, 0, doc.documentWidth.coerceAtLeast(1).coerceAtMost(Int.MAX_VALUE.toLong()),
            doc.documentHeight.coerceAtLeast(1).coerceAtMost(Int.MAX_VALUE.toLong()), part, label)
    private fun readable(failure: Throwable): Exception = when (failure) {
        is UnsatisfiedLinkError -> IllegalStateException("The Office engine is unavailable for this device architecture.", failure)
        is OutOfMemoryError -> IllegalStateException("Not enough memory to render this document. Close other apps and try again.", failure)
        is Exception -> failure
        else -> IllegalStateException(failure.message ?: "The native Office engine failed.", failure)
    }
}
