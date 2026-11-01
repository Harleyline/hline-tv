package app.orchard.tv.apps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import app.orchard.tv.wallpaper.Images
import java.io.File
import java.io.InputStream

/**
 * Pictures the owner chose for app tiles, sent from the phone page. Each is cropped to the tile's
 * 16:9 and kept by package name in files/icons; a tile with one draws it instead of the app's own
 * artwork (AppCatalog). Only the owner's own uploads live here, and removing one only ever deletes
 * that app's file.
 *
 * 换回上一张 works on the tile's whole picture, not only on sent files: before anything changes
 * the picture — an upload, a pick of the library's or the app's own, a reset — [snapshot] records
 * what stood there (which source, and the sent file's bytes when one exists), and [undo] puts
 * exactly that back, once: the record is spent on use, and the next change makes a new one.
 */
object CustomIcons {
    /** 1280×720: sharp on a 4K UI's 704 px tile, small enough to decode on every app reload. */
    private const val W = 1280
    private const val H = 720

    /** Bumped on every change, so Home reloads its tiles. */
    var version by mutableIntStateOf(0); private set

    private lateinit var dir: File

    fun init(context: Context) {
        if (::dir.isInitialized) return
        dir = File(context.filesDir, "icons").apply { mkdirs() }
        // The 换回上一张 record belongs to the change it backs out of, not to the tile forever:
        // records left over from a previous run (an update restarts the process) would show the
        // button on tiles the owner never touched this time. Fresh run, no history.
        dir.listFiles { f -> f.isFile && (f.name.endsWith(".prev.json") || f.name.endsWith(".prev.png")) }
            ?.forEach { it.delete() }
    }

    /** By package name; a pinned input's key holds slashes, so it goes by a hash of the key. */
    private fun fileFor(pkg: String) = if (isValidKey(pkg)) File(dir, "$pkg.png") else File(dir, "input-${hash(pkg)}.png")

    private fun prevPngFor(pkg: String) = if (isValidKey(pkg)) File(dir, "$pkg.prev.png") else File(dir, "input-${hash(pkg)}.prev.png")

    private fun prevMetaFor(pkg: String) = if (isValidKey(pkg)) File(dir, "$pkg.prev.json") else File(dir, "input-${hash(pkg)}.prev.json")

    private fun hash(key: String): String =
        java.security.MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }

    fun has(pkg: String): Boolean = ::dir.isInitialized && fileFor(pkg).exists()

    /** Whether a previous state is kept for [pkg], for the phone page's 换回上一张. */
    fun hasPrev(pkg: String): Boolean = ::dir.isInitialized && prevMetaFor(pkg).exists()

    /** When the app's picture last changed; 0 without one. Part of the tile cache's key. */
    fun stamp(pkg: String): Long = if (::dir.isInitialized) fileFor(pkg).lastModified() else 0L

    /** The app's picture at tile size, or null. Off the main thread. */
    fun load(pkg: String): Bitmap? = if (has(pkg)) BitmapFactory.decodeFile(fileFor(pkg).path) else null

    /**
     * Records the picture as it stands now — the source it comes from, and the sent file's bytes
     * whenever there is one (even hidden behind a pick) — before a change replaces it. Off the
     * main thread.
     */
    fun snapshot(pkg: String) {
        if (!::dir.isInitialized) return
        val pick = IconLibrary.pick(pkg)
        val sent = has(pkg)
        runCatching {
            prevMetaFor(pkg).writeText(org.json.JSONObject()
                .put("pick", pick?.name ?: "")
                .put("sent", sent).toString())
            val prev = prevPngFor(pkg); val cur = fileFor(pkg)
            if (sent && cur.exists()) cur.copyTo(prev, overwrite = true) else prev.delete()
        }
    }

    /** Stores [input] as the app's picture, keeping the state it replaces for [undo]. Off the main thread; false if it is not an image. */
    fun save(pkg: String, input: InputStream, cacheDir: File): Boolean {
        val tmp = File.createTempFile("icon", ".bin", cacheDir)
        return try {
            tmp.outputStream().use { input.copyTo(it) }
            val bmp = Images.decodeCover(tmp, W, H) ?: return false
            snapshot(pkg)
            fileFor(pkg).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            true
        } finally {
            tmp.delete()
        }
    }

    /**
     * Puts back the state [snapshot] recorded, and spends the record: 换回上一张 is a one-shot —
     * the button is there once, after a change, and gone once used, until the next change makes a
     * new one. False without a recorded state.
     */
    fun undo(pkg: String): Boolean {
        if (!::dir.isInitialized || !hasPrev(pkg)) return false
        val meta = runCatching { org.json.JSONObject(prevMetaFor(pkg).readText()) }.getOrNull()
        val pick = meta?.let {
            when (it.optString("pick")) {
                "Library" -> IconLibrary.Pick.Library
                "Own" -> IconLibrary.Pick.Own
                else -> null
            }
        }
        val sent = meta?.optBoolean("sent") == true
        val bytes = if (sent) prevPngFor(pkg).takeIf { it.exists() }?.readBytes() else null
        IconLibrary.choose(pkg, pick)
        val cur = fileFor(pkg)
        if (sent) {
            bytes?.let { cur.writeBytes(it) }
        } else {
            cur.delete()
        }
        prevMetaFor(pkg).delete()
        prevPngFor(pkg).delete()
        return true
    }

    /** The sent file only; the 换回上一张 record is kept, so even this can be undone. */
    fun clear(pkg: String) {
        fileFor(pkg).delete()
    }

    /** Called on the main thread after a save, an undo or a clear. */
    fun changed() {
        version++
    }

    /** A package name, kept as a plain file name. */
    fun isValidKey(pkg: String): Boolean = Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$").matches(pkg)

    /** How many pictures the owner has sent from the phone (2.04): none of them is ever cleared. */
    fun count(context: Context): Int {
        init(context)
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".png") && !f.name.endsWith(".prev.png") }?.size ?: 0
    }
}
