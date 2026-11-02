package app.orchard.tv.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import androidx.annotation.StringRes
import androidx.core.graphics.createBitmap
import app.orchard.tv.R

/**
 * Aerial's own wallpapers, in the manner of the Mac's: sand in side light, and water at its edge —
 * the owner's own photographs, shipped in assets/wallpapers and cropped to the screen like any
 * other picture (2.16). The presets Aerial drew before them — two seas, three fields of light, and
 * the drawn dunes and waves — went when the owner asked for these three alone (2.17).
 *
 * A preset is one key, one name, one entry in the picker, so the rest of the code (the baked
 * backdrop, the thumbnail, "Follow App"'s stand-in) never has to know what draws it.
 */
class GradientPreset(
    val key: String,
    /** The name as a resource, so the picker reads it in the TV's own language (2.17). */
    @StringRes val nameRes: Int,
    /** A picture in assets/wallpapers, drawn as it is; [draw] stands in only if it cannot be read. */
    internal val asset: String? = null,
    internal val draw: Canvas.(w: Float, h: Float) -> Unit = { w, h -> fillAll(w, h, 0xFF202020) },
)

/** One flat colour: what a photograph's preset shows where its asset cannot be read. */
private fun Canvas.fillAll(w: Float, h: Float, color: Long) {
    drawRect(android.graphics.RectF(0f, 0f, w, h), Paint().apply { this.color = color.toInt() })
}

object Gradients {
    private var context: Context? = null

    /** Called once by Wallpapers.init: the assets' photographs are decoded through it. */
    fun attach(context: Context) { this.context = context.applicationContext }

    /** The five photographs, in the picker's order: two dunes, the tide's marks, then the cities. */
    val presets = listOf(
        GradientPreset("dunes_gilded", R.string.wp_name_dunes_gilded, "dunes_gilded") { w, h -> fillAll(w, h, 0xFFB97A46) },
        GradientPreset("dunes_edge", R.string.wp_name_dunes_edge, "dunes_edge") { w, h -> fillAll(w, h, 0xFF9C7A63) },
        GradientPreset("waves_tide", R.string.wp_name_waves_tide, "waves_tide") { w, h -> fillAll(w, h, 0xFF123A5C) },
            GradientPreset("city_lights", R.string.wp_name_city_lights, "city_lights") { w, h -> fillAll(w, h, 0xFF0B1533) },
    )

    /**
     * Every key Aerial has ever offered, pointed at one of the three that are left: a wallpaper
     * chosen in an older build resolves to the closest of them rather than to nothing. The drawn
     * dunes and waves went in 2.16, the seas and the light fields in 2.17 (the owner asked them
     * off), so their keys land on the picture of the same subject — sand for sand, water for water.
     */
    private val renamed = mapOf(
        // The seas and the light fields, gone in 2.17.
        "sea_sunset" to "dunes_gilded", "sea_clear" to "waves_tide",
        "glow_pink" to "dunes_gilded", "glow_lime" to "dunes_edge", "aurora" to "waves_tide",
        "sunset" to "dunes_gilded", "violet" to "dunes_gilded", "forest" to "dunes_edge",
        "rose" to "dunes_gilded", "graphite" to "dunes_edge",
        // The drawn dunes and waves, gone in 2.16.
        "dunes_gold" to "dunes_gilded", "dune" to "dunes_gilded",
        "dunes_moon" to "dunes_edge", "waves_blue" to "waves_tide", "ocean" to "waves_tide",
        "waves_coral" to "waves_tide", "waves_violet" to "waves_tide",
        // The hills went in 1.80 (the owner asked them off).
        "hills_dawn" to "dunes_gilded", "hills_dusk" to "dunes_gilded",
        "hills_mist" to "dunes_edge", "hills_night" to "waves_tide",
    )

    fun byKey(key: String) = presets.firstOrNull { it.key == (renamed[key] ?: key) }

    /** The key a stored choice resolves to today. */
    fun currentKey(key: String) = renamed[key] ?: key

    /**
     * The card "Follow App" shows: a photograph of its own, not one of the wallpapers, so the card
     * reads as "the app decides" rather than as another picture to pick (the owner, 2.19). It is
     * the third dune, the one that did not make the cut as a wallpaper.
     */
    private val follow = GradientPreset("follow", R.string.wp_auto_follow, "follow") { w, h -> fillAll(w, h, 0xFF2A2622) }

    /** Stands for "Follow App" in the picker. */
    val autoThumb get() = follow

    /** Renders a preset at [width]×[height], dithered so a gradient would not band. */
    fun render(preset: GradientPreset, width: Int, height: Int): Bitmap {
        preset.asset?.let { name -> photo(name, width, height)?.let { return it } }
        val out = createBitmap(width, height)
        Canvas(out).apply { preset.draw(this, width.toFloat(), height.toFloat()) }
        Images.dither(out)
        return out
    }

    /**
     * One of the shipped photographs, cropped to [width]×[height]. Call off the main thread.
     * WebP at quality 92 (1.5): the same pictures, 2244 KB of JPEG down to 1396 KB, and assets are
     * stored uncompressed in the APK, so that comes off its size whole. BitmapFactory reads WebP
     * from Android 4.0 on.
     */
    private fun photo(name: String, width: Int, height: Int): Bitmap? = runCatching {
        val ctx = context ?: return null
        val src = ctx.assets.open("wallpapers/$name.webp").use { BitmapFactory.decodeStream(it) } ?: return null
        Images.cover(src, width, height).also { if (it !== src) src.recycle() }
    }.getOrNull()
}
