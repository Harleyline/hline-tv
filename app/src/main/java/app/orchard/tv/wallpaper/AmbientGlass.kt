package app.orchard.tv.wallpaper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * An instant stand-in for the sheet's blurred picture while a new aerial film loads (the owner,
 * 1.92): a soft vertical gradient in the colours of the chosen kind — space the deep sea blue it
 * usually plays in, cities the dark warm grey of night streets, and so on. A blurred frame takes
 * about a second to arrive; this takes none, so the sheet never shows the old film's picture.
 */
object AmbientGlass {
    /** Two stops per kind: a deep shade at the top, its warmer/brighter partner below. */
    private val PALETTES = mapOf(
        "space" to longArrayOf(0xFF0A1B33, 0xFF123463),
        "cities" to longArrayOf(0xFF0E1626, 0xFF272235),
        "landscapes" to longArrayOf(0xFF123024, 0xFF6E5B36),
        "underwater" to longArrayOf(0xFF05303C, 0xFF106070),
    )

    private val cache = HashMap<String, ImageBitmap>()

    fun of(kind: String): ImageBitmap {
        val key = if (kind in Aerials.KINDS) kind else "space"
        cache[key]?.let { return it }
        val (top, bottom) = PALETTES[key] ?: PALETTES.getValue("space")
        val b = Bitmap.createBitmap(24, 14, Bitmap.Config.ARGB_8888)
        Canvas(b).drawRect(
            0f, 0f, 24f, 14f,
            Paint().apply {
                shader = LinearGradient(
                    0f, 0f, 0f, 14f,
                    top.toInt(), bottom.toInt(), Shader.TileMode.CLAMP,
                )
            },
        )
        val image = b.asImageBitmap()
        cache[key] = image
        return image
    }
}
