package app.orchard.tv.ui.theme

import android.content.Context
import android.graphics.Typeface
import android.text.TextPaint
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import kotlin.concurrent.thread

/**
 * Loads Home's fonts on a background thread while the activity is still being created. The first
 * text Home measured did it on the main thread: making Inter's typefaces, and with them the
 * system's fallback chain (dozens of Noto fonts), took 58 ms of the first frame on a cold start
 * (Xiaomi MiTV-MFTR0, release build, perfetto). Compose keeps resolved typefaces in a cache shared
 * by every resolver, and the system keeps the fonts it loaded, so the main thread finds them ready.
 * If the main thread gets there first it simply loads them itself, as before.
 */
object FontWarmup {
    @Volatile private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        thread(name = "AerialFonts", priority = Thread.NORM_PRIORITY) {
            runCatching {
                val resolver = createFontFamilyResolver(app)
                val faces = (listOf(
                    InterText to FontWeight.W500, InterText to FontWeight.W600,
                    InterDisplay to FontWeight.W500, InterDisplay to FontWeight.W600,
                ) + ScriptWarm).mapNotNull { (family, weight) ->
                    runCatching { resolver.resolve(family, weight).value as? Typeface }.getOrNull()
                }
                // Shaping a line with Latin, digits, Cyrillic, CJK, Tibetan and Arabic loads the
                // fallback fonts Home's names, clock and weather need.
                val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { textSize = 28f }
                for (face in faces.ifEmpty { listOf(Typeface.DEFAULT) }) {
                    paint.typeface = face
                    paint.measureText(SAMPLE)
                }
            }
        }
    }

    private const val SAMPLE = "Aerial 0123456789:° 设置天气相册应用 Настройки 天気予報 날씨 アニメ " +
        "གཙོ་ངོས། གནམ་གཤིས། ھاۋا رايى شەھەرلەر"
}
