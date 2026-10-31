package app.orchard.tv.system

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue

/**
 * Brightness as a veil over the screen, for a TV whose panel a third party cannot move.
 *
 * MiTV-MFTP0 (alita, Android 9) answers none of the Xiaomi routes — no picture_backlight, its
 * TvService's getBacklightStatus returns -1, and the mitv picture manager throws
 * ExceptionInInitializerError in the vendor's private AIPQ library — and its panel does not follow
 * Settings.System screen_brightness either: written 79 -> 63, the picture stayed as it was (the
 * owner's test, 2026-10-30, AerialDiag "brightness: asked 26 -> 25, screen_brightness 66 -> 63").
 *
 * So the last resort is a black veil Aerial draws over its own screen: it cannot go brighter than
 * the panel, but it can go darker, stepped like a slider. 100% is the panel as it is.
 */
object Dim {
    /** How dark the veil gets at 1%: enough to read as a change, short of a black screen. */
    const val DEEPEST = 0.72f

    /** The veil's alpha, 0 (clear) for the panel's own level. */
    var fraction by mutableFloatStateOf(0f)
        private set

    /** Puts the veil where [percent] (1-100) asks. */
    fun of(percent: Int) {
        fraction = ((100 - percent.coerceIn(1, 100)) / 100f * DEEPEST).coerceIn(0f, DEEPEST)
    }

    /** The level the veil leaves, 0..1, as [Brightness.level] reports it. */
    fun level(): Float = 1f - fraction / DEEPEST
}
