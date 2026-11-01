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
    }

    /** By package name; a pinned input's key holds slashes, so it goes by a hash of the key. */
    private fun fileFor(pkg: String) = if (isValidKey(pkg)) File(dir, "$pkg.png") else File(dir, "input-${hash(pkg)}.png")

    /** The picture this one replaced, kept once so the phone can put it back. */
    private fun prevFor(pkg: String) = if (isValidKey(pkg)) File(dir, "$pkg.prev.png") else File(dir, "input-${hash(pkg)}.prev.png")

    private fun hash(key: String): String =
        java.security.MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }

    fun has(pkg: String): Boolean = ::dir.isInitialized && fileFor(pkg).exists()

    /** Whether a replaced picture is kept for [pkg], for the phone page's 换回上一张. */
    fun hasPrev(pkg: String): Boolean = ::dir.isInitialized && prevFor(pkg).exists()

    /** When the app's picture last changed; 0 without one. Part of the tile cache's key. */
    fun stamp(pkg: String): Long = if (::dir.isInitialized) fileFor(pkg).lastModified() else 0L

    /** The app's picture at tile size, or null. Off the main thread. */
    fun load(pkg: String): Bitmap? = if (has(pkg)) BitmapFactory.decodeFile(fileFor(pkg).path) else null

    /** Stores [input] as the app's picture; the one it replaces is kept for [undo]. Off the main thread; false if it is not an image. */
    fun save(pkg: String, input: InputStream, cacheDir: File): Boolean {
        val tmp = File.createTempFile("icon", ".bin", cacheDir)
        return try {
            tmp.outputStream().use { input.copyTo(it) }
            val bmp = Images.decodeCover(tmp, W, H) ?: return false
            val dst = fileFor(pkg)
            if (dst.exists()) dst.copyTo(prevFor(pkg), overwrite = true)
            dst.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            true
        } finally {
            tmp.delete()
        }
    }

    /**
     * Puts the replaced picture back, and keeps the current one in its place — so undo undoes
     * itself, and the owner can walk back and forth between the two. False without one.
     */
    fun undo(pkg: String): Boolean {
        if (!::dir.isInitialized) return false
        val cur = fileFor(pkg); val prev = prevFor(pkg)
        if (!prev.exists()) return false
        if (cur.exists()) {
            val swap = File(dir, "swap.tmp")
            cur.copyTo(swap, overwrite = true)
            prev.copyTo(cur, overwrite = true)
            swap.copyTo(prev, overwrite = true)
            swap.delete()
        } else {
            prev.renameTo(cur)
        }
        return true
    }

    fun clear(pkg: String) {
        fileFor(pkg).delete()
        prevFor(pkg).delete()
    }

    /** Called on the main thread after a save or clear. */
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
