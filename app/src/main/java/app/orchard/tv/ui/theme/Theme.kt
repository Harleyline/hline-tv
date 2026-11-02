package app.orchard.tv.ui.theme

import android.os.Build
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.orchard.tv.R

/**
 * Pixels per dp of Aerial's 960×540 design canvas on this screen: 2 for a 1080p UI, 4 for a TV
 * that draws its UI at 4K. Anything rasterized ahead of time (tile art, wallpapers) is sized from
 * this too, so it stays as sharp as the text drawn live.
 */
fun canvasDensity(metrics: android.util.DisplayMetrics): Float =
    minOf(metrics.widthPixels, metrics.heightPixels * 16 / 9) / 960f

/** tvOS dark appearance. Values follow Apple's system colors for the dark, elevated context. */
object Palette {
    val Background = Color(0xFF000000)
    val Label = Color(0xFFFFFFFF)
    val SecondaryLabel = Color(0xB3EBEBF5)
    val TertiaryLabel = Color(0x4DEBEBF5)
    // Clean glass: almost no tint, so the picture behind reads through it.
    val Glass = Color(0x2EFFFFFF)
    val Fill = Color(0x2E787880)
    val FillStrong = Color(0x52787880)
    val Hairline = Color(0x1AFFFFFF)
    val Green = Color(0xFF30D158)
    val Orange = Color(0xFFFF9F0A)
    val Red = Color(0xFFFF453A)

    /** tvOS draws a focused control as a near-white plate with dark content. */
    val Focus = Color(0xFFF5F5F7)
    val OnFocus = Color(0xFF1D1D1F)
}

object Motion {
    /** Strong ease-out: entrances, anything that answers the user. */
    val EaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

    /** On-screen movement between two resting places. */
    val EaseInOut = CubicBezierEasing(0.77f, 0f, 0.175f, 1f)

    /** iOS sheet curve, for panels sliding in. */
    val Drawer = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)
}

/** True when the TV's animator scale is 0 ("remove animations"). Motion collapses to fades. */
val LocalReducedMotion = staticCompositionLocalOf { false }

// Inter has an optical size axis. Text sizes get the 14pt master, display sizes the 32pt master,
// the way SF Pro switches between Text and Display. Font variations need Android 8.0; before that
// every weight would come out as Inter Regular, so those TVs use the system's Roboto instead.
//
// Inter is Latin-only. Noto Sans JP and Noto Sans KR (one W400 cut each since 1.5) come after it in
// the family, so kana, hangul and kanji draw from a proper Japanese/Korean cut instead of falling
// through the TV's dozens of system fonts — the list Inter, JP, KR covers Latin, Japanese and
// Korean in one chain, Inter first so Latin never changes (the owner, 1.75). Noto Serif Tibetan
// and Noto Sans Arabic then close the chain for Tibetan and Uyghur, the two scripts nothing else
// in it covers (2.12).
// One weight per cut: the bundled Noto Sans JP and KR were 9.1 MB and 10.2 MB variable fonts
// carrying nine weights, and every style below asks for W400 (see Type). They are now the same
// fonts instanced at 400 with the vertical metrics dropped, 5.3 MB and 5.8 MB (1.5, docs/PERF-1.5.md).
// A static face also needs no Android 8.0 variation support, but the gate stays: on the TVs below
// 8.0 the system font already covers CJK, and 11 MB of bundled cuts would buy them nothing.
private fun cjkFonts(res: Int): List<Font> =
    if (Build.VERSION.SDK_INT < 26) emptyList() else listOf(Font(res, FontWeight.W400))

private val japanFonts by lazy { cjkFonts(R.font.noto_sans_jp) }
private val koreaFonts by lazy { cjkFonts(R.font.noto_sans_kr) }

// Tibetan and Uyghur, the two scripts Inter has nothing for. Inter carries Latin, Greek and
// Cyrillic already, so English, French, Russian and Mongolian Cyrillic need nothing extra; but a
// Tibetan syllable or an Arabic-script word would otherwise come from whatever the TV happens to
// ship, which on most Chinese sets is nothing at all. Both are Noto, so they sit in the same
// chain: Noto Serif Tibetan is the only Tibetan Noto Google publishes (there is no Sans), and
// Noto Sans Arabic covers Uyghur's own letters (ئ ۇ ۆ ۈ ې ڭ گ) as well as Arabic.
private val tibetanFonts by lazy { listOf(Font(R.font.noto_serif_tibetan, FontWeight.W400)) }
private val arabicFonts by lazy { listOf(Font(R.font.noto_sans_arabic, FontWeight.W400)) }

private val tibetan by lazy { FontFamily(tibetanFonts) }
private val arabic by lazy { FontFamily(arabicFonts) }

/**
 * The family a language's own name reads in: the bundled cut for the two scripts no TV ships,
 * whatever language the app itself is speaking — the language picker lists all of them at once.
 */
fun scriptFamily(tag: String?): FontFamily? = when (tag) {
    "bo" -> tibetan
    "ug" -> arabic
    else -> null
}

/**
 * The family the app's words are set in for the language [tag] speaks. There is no way on this
 * Android to add a fallback of one's own — CustomFallbackBuilder is API 29 and the TVs are 9 — and
 * a Compose font list resolves to a single typeface, so for the two scripts no TV ships, the
 * bundled cut has to be the base. Latin then falls to the TV's own, which is Roboto here; every
 * other language keeps Inter.
 */
internal fun familyFor(tag: String?, display: Boolean): FontFamily =
    scriptFamily(tag) ?: if (display) InterDisplay else InterText

/** The bundled non-Latin cuts alone, for FontWarmup: resolving each loads its typeface before the
 *  first line in that script is drawn. */
val ScriptWarm: List<Pair<FontFamily, FontWeight>> by lazy {
    listOfNotNull(
        japanFonts.firstOrNull()?.let { FontFamily(it) to FontWeight.W400 },
        koreaFonts.firstOrNull()?.let { FontFamily(it) to FontWeight.W400 },
        FontFamily(tibetanFonts) to FontWeight.W400,
        FontFamily(arabicFonts) to FontWeight.W400,
    )
}

@OptIn(ExperimentalTextApi::class)
private fun inter(opticalSize: Float) = if (Build.VERSION.SDK_INT < 26) FontFamily.SansSerif else FontFamily(
    (1..9).map { i ->
        val w = i * 100
        Font(
            R.font.inter,
            FontWeight(w),
            variationSettings = FontVariation.Settings(
                FontVariation.weight(w),
                FontVariation.Setting("opsz", opticalSize),
            ),
        )
    } + japanFonts + koreaFonts + tibetanFonts + arabicFonts,
)

/** Tabular figures: a clock's digits keep their width as they change, and match line to line. */
private const val TNUM = "tnum"

val InterText = inter(14f)
val InterDisplay = inter(32f)

object Type {
    /**
     * Everything except the hero clock is drawn at the regular weight. The bundled Inter is
     * Latin-only, so a Chinese character falls back to the TV's system font — and that font
     * ships one weight only, so W500/W600 requests came out regular for CJK and semi-bold for
     * Latin. Any mixed line ("多云 18°", "通知中心 Bilibili") then looked half bold. Asking for
     * W400 everywhere makes the two scripts weigh the same.
     */
    /**
     * The smaller clocks (Home's small clock, Control Center, the screensaver) and every other
     * figure out of the module scale. Display cuts and negative tracking made digits run together
     * at these sizes (#494), so tracking is nought rather than negative: the family stays Inter
     * Display, the one the big clock uses, so a time reads the same wherever it appears. Size is
     * set where used.
     */
    private val baseSmallClock = TextStyle(
        fontFamily = InterDisplay, fontWeight = FontWeight.W400,
        letterSpacing = 0.sp, color = Palette.Label,
        fontFeatureSettings = TNUM,
    )
    private val baseLargeTitle = TextStyle(
        fontFamily = InterDisplay, fontWeight = FontWeight.W400,
        fontSize = 26.sp, letterSpacing = (-0.015).em, color = Palette.Label,
    )
    private val baseTitle = TextStyle(
        fontFamily = InterDisplay, fontWeight = FontWeight.W400,
        fontSize = 19.sp, letterSpacing = (-0.01).em, color = Palette.Label,
    )
    private val baseHeadline = TextStyle(
        fontFamily = InterText, fontWeight = FontWeight.W400,
        fontSize = 15.sp, letterSpacing = (-0.005).em, color = Palette.Label,
    )
    private val baseBody = TextStyle(
        fontFamily = InterText, fontWeight = FontWeight.W400,
        fontSize = 15.sp, color = Palette.Label,
    )
    private val baseCallout = TextStyle(
        fontFamily = InterText, fontWeight = FontWeight.W400,
        fontSize = 14.sp, color = Palette.SecondaryLabel,
    )
    private val baseCaption = TextStyle(
        fontFamily = InterText, fontWeight = FontWeight.W400,
        fontSize = 13.sp, letterSpacing = 0.01.em, color = Palette.SecondaryLabel,
    )

    /**
     * Every figure Aerial draws — the clock, the degrees, the air quality, the date's numbers —
     * comes from this one scale: Inter Display, one weight (W400), tabular figures, and tracking
     * that closes by size (a fixed sp, not em, so a widening asked for by hand stays put). That is
     * what makes the digits look like one typeface from Home to Control Center to the screensaver;
     * before 1.72 the clock was W500 here, W700 in the saver and Inter Text in the panels.
     */
    private val baseModuleNumber = TextStyle(
        fontFamily = InterDisplay, fontWeight = FontWeight.W400,
        fontSize = 72.sp, letterSpacing = (-2.0).sp, color = Palette.Label,
        fontFeatureSettings = TNUM,
    )

    /** The weather's big degrees: the same figure, a step down from the time. */
    private val baseModuleTemp = baseModuleNumber.copy(fontSize = 52.sp, letterSpacing = (-1.4).sp)

    /**
     * The date, the place, the air and the humidity: one size, one weight, one family — the same
     * one the figures above use, so a number in a detail line is not a different typeface.
     */
    private val baseModuleDetail = TextStyle(
        fontFamily = InterDisplay, fontWeight = FontWeight.W400,
        fontSize = 17.sp, color = Palette.Label,
        fontFeatureSettings = TNUM,
    )

    // The styles as they read. They are rebuilt by [applyLanguage] — the language can change
    // within a process (Personalize rebuilds the activity), and on Android 9 the two scripts no
    // TV ships have to be the base family rather than a fallback, so the family follows it.
    var SmallClock: TextStyle = baseSmallClock; private set
    var LargeTitle: TextStyle = baseLargeTitle; private set
    var Title: TextStyle = baseTitle; private set
    var Headline: TextStyle = baseHeadline; private set
    var Body: TextStyle = baseBody; private set
    var Callout: TextStyle = baseCallout; private set
    var Caption: TextStyle = baseCaption; private set
    var ModuleNumber: TextStyle = baseModuleNumber; private set
    var ModuleTemp: TextStyle = baseModuleTemp; private set
    var ModuleDetail: TextStyle = baseModuleDetail; private set

    /**
     * Sets the families for the language [tag] speaks. Called from the activity's start, before
     * the first frame, and again when the owner picks another language and the activity rebuilds.
     */
    fun applyLanguage(tag: String?) {
        val small = familyFor(tag, display = false)
        val large = familyFor(tag, display = true)
        SmallClock = baseSmallClock.copy(fontFamily = large)
        LargeTitle = baseLargeTitle.copy(fontFamily = large)
        Title = baseTitle.copy(fontFamily = large)
        Headline = baseHeadline.copy(fontFamily = small)
        Body = baseBody.copy(fontFamily = small)
        Callout = baseCallout.copy(fontFamily = small)
        Caption = baseCaption.copy(fontFamily = small)
        ModuleNumber = baseModuleNumber.copy(fontFamily = large)
        ModuleTemp = baseModuleTemp.copy(fontFamily = large)
        ModuleDetail = baseModuleDetail.copy(fontFamily = large)
    }
}
