package app.orchard.tv.system

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Window
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference

/**
 * Saves what this launcher is drawing, for screenshots and bug reports.
 *
 * Some firmware refuses to hand the framebuffer out: on MiTV_MFTP0 (Android 9) `adb shell
 * screencap`, `screenrecord` and even the remote's screenshot key all come back as one solid
 * black frame. The app can still photograph its own window, which is what this does — PixelCopy
 * reads the window's surface, not the framebuffer, so the same block does not apply.
 *
 *     adb shell am broadcast -a app.orchard.tv.CONFIG -n app.orchard.tv/.guard.ConfigReceiver --es shot home
 *     adb pull /sdcard/Android/data/app.orchard.tv/files/shots/home.png
 *
 * A video wallpaper plays in its own surface, so it comes out black; everything the app draws
 * itself (tiles, clock, weather, panels) is in the picture.
 */
object Shots {
    @Volatile private var window: WeakReference<Window>? = null

    fun attach(window: Window) {
        this.window = WeakReference(window)
    }

    fun capture(context: Context, name: String) {
        val win = window?.get()
        if (win == null) {
            DiagLog.i("shot: no window (the launcher is not on screen)")
            return
        }
        if (Build.VERSION.SDK_INT < 26) {
            DiagLog.i("shot: PixelCopy on a window needs Android 8")
            return
        }
        val view = win.decorView
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) {
            DiagLog.i("shot: window has no size yet ($w×$h)")
            return
        }
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        PixelCopy.request(win, bitmap, { result ->
            if (result == PixelCopy.SUCCESS) {
                val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "shots")
                dir.mkdirs()
                val file = File(dir, "$name.png")
                runCatching { FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                    .onSuccess { DiagLog.i("shot: $name ${w}×$h ${file.length()} B -> ${file.absolutePath}") }
                    .onFailure { DiagLog.i("shot: could not write $file — $it") }
            } else {
                DiagLog.i("shot: PixelCopy failed ($result)")
            }
            bitmap.recycle()
        }, Handler(Looper.getMainLooper()))
    }
}
