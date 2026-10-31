package app.orchard.tv.wallpaper

import android.content.Context
import app.orchard.tv.settings.AerialSettings
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.Display
import android.view.WindowManager
import androidx.core.content.getSystemService
import org.json.JSONObject

/**
 * Apple TV's aerial screensaver films. Aerial ships only the catalogue (assets/aerials.json, built
 * from Apple's public resources-16.tar): addresses and place names. The films themselves stream from
 * Apple's servers while the screensaver runs, the way the Apple TV plays them.
 */
class Aerial(
    val id: String,
    val label: String,
    val category: String,
    /** 1080p H.264, about 10 Mbps: plays everywhere. */
    val url: String,
    /** 4K SDR HEVC, about 12 Mbps. */
    val uhd: String,
    /** 4K Dolby Vision (profile 5, HEVC 10-bit), about 24 Mbps. */
    val dolbyVision: String,
    /** (second, caption) pairs: tvOS changes the place name as the camera moves on. The catalogue
     *  carries a Chinese and a Latin line for every mark; which reads follows the UI language at
     *  display time ([captionAt]), so a language changed after this was loaded still shows right. */
    val captionsZh: List<Pair<Int, String>>,
    val captionsLatin: List<Pair<Int, String>>,
) {
    fun captionAt(seconds: Int, zh: Boolean): String =
        (if (zh) captionsZh else captionsLatin).lastOrNull { it.first <= seconds }?.second ?: label

    fun url(quality: AerialQuality): String = when (quality) {
        AerialQuality.DolbyVision -> dolbyVision
        AerialQuality.Uhd -> uhd
        else -> url
    }
}

/** Which of Apple's files the screensaver streams. Auto takes the best this TV can play. */
enum class AerialQuality(val key: String) {
    Auto("auto"), Hd("1080p"), Uhd("4k"), DolbyVision("dolby_vision");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: Auto
    }
}

/**
 * What this TV's decoders and display can play, asked once. A TV's UI is usually 1080p whatever
 * its panel (Xiaomi MiTV-MFTR0 reports a single 1920×1080 mode), so the panel cannot be asked;
 * the decoders can. 4K SDR costs about what the 1080p H.264 files do (12 against 10 Mbps), so a
 * TV that decodes it gets it. Dolby Vision needs a decoder for profile 5 at 4K and a display that
 * lists Dolby Vision: Apple's HDR files are profile 5, which has no HDR10 base layer to fall back
 * on, and decoded by anything else they come out green and purple.
 */
object AerialFormats {
    @Volatile
    private var probed: Set<AerialQuality>? = null

    fun supported(context: Context): Set<AerialQuality> = probed ?: probe(context).also { probed = it }

    /** [chosen], or for Auto the best supported; a choice the TV cannot play falls back too. */
    fun resolve(context: Context, chosen: AerialQuality): AerialQuality {
        val ok = supported(context)
        return if (chosen != AerialQuality.Auto && chosen in ok) chosen
        else listOf(AerialQuality.DolbyVision, AerialQuality.Uhd).firstOrNull { it in ok && (chosen == AerialQuality.Auto || it.ordinal < chosen.ordinal) }
            ?: AerialQuality.Hd
    }

    /**
     * What the aerial wallpaper plays: as chosen, but never Dolby Vision. A TV in Dolby Vision mode
     * shows the tiles over the film with Dolby's picture settings. A screensaver that carries the
     * wallpaper's film on plays this too (Screensaver), so the two are one, with no switch.
     */
    fun forWallpaper(context: Context): AerialQuality =
        resolve(context, AerialSettings.aerialQuality).let { if (it == AerialQuality.DolbyVision) AerialQuality.Uhd else it }

    /**
     * Whether a screensaver of aerials over the aerial wallpaper carries the wallpaper's film on, in
     * its format. Auto does: going to Dolby Vision from the wallpaper's SDR showed black for 0.9–3.5 s
     * (Xiaomi MiTV-MFTR0, one 4K decoder) and most TVs blink as they switch modes. Dolby Vision
     * chosen by hand is played whatever that costs, from the wallpaper's film and moment.
     */
    fun screensaverCarriesWallpaper(context: Context): Boolean =
        !(AerialSettings.aerialQuality == AerialQuality.DolbyVision && AerialQuality.DolbyVision in supported(context))

    /** What a screensaver of aerials starts in; [overWallpaper]: it begins from the aerial wallpaper's film. */
    fun forScreensaver(context: Context, overWallpaper: Boolean): AerialQuality =
        if (overWallpaper && screensaverCarriesWallpaper(context)) forWallpaper(context)
        else resolve(context, AerialSettings.aerialQuality)

    /** One step down, after a decoder failed on [quality]. */
    fun lower(quality: AerialQuality): AerialQuality = when (quality) {
        AerialQuality.DolbyVision -> AerialQuality.Uhd
        else -> AerialQuality.Hd
    }

    private fun probe(context: Context): Set<AerialQuality> {
        val found = mutableSetOf(AerialQuality.Hd)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return found
        val codecs = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder } }.getOrDefault(emptyList())
        fun caps(mime: String) = codecs.mapNotNull { info ->
            if (info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) null
            else runCatching { info.getCapabilitiesForType(mime) }.getOrNull()
        }
        val uhd = caps(MediaFormat.MIMETYPE_VIDEO_HEVC).any { c ->
            runCatching { c.videoCapabilities.areSizeAndRateSupported(3840, 2160, 30.0) }.getOrDefault(false)
        }
        if (uhd) found += AerialQuality.Uhd
        val dvDecoder = caps(MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION).any { c ->
            c.profileLevels.any {
                it.profile == MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheStn &&
                    it.level >= MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelUhd30
            }
        }
        val dvDisplay = runCatching {
            @Suppress("DEPRECATION")
            context.getSystemService<WindowManager>()?.defaultDisplay?.hdrCapabilities?.supportedHdrTypes
                ?.contains(Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION) == true
        }.getOrDefault(false)
        if (uhd && dvDecoder && dvDisplay) found += AerialQuality.DolbyVision
        return found
    }
}

object Aerials {
    @Volatile
    private var cache: List<Aerial>? = null

    /** The shelf [cache] holds; a shelf chosen since then is read anew. */
    @Volatile
    private var cached: String? = null

    /**
     * The films of the shelf chosen in Personalize. The choice is read here, not taken once: the
     * catalogue is parsed again whenever it changes (1.81), so a film picked while one plays shows
     * on the next start instead of the shelf the app first read.
     */
    fun all(context: Context): List<Aerial> {
        val kind = kind()
        cache?.takeIf { cached == kind }?.let { return it }
        return synchronized(this) {
            cache?.takeIf { cached == kind } ?: load(context, kind).also { cache = it; cached = kind }
        }
    }

    fun kind(): String = AerialSettings.aerialKind.takeIf { it in KINDS } ?: "space"

    private fun load(context: Context, kind: String): List<Aerial> {
        val root = JSONObject(context.assets.open("aerials.json").bufferedReader().use { it.readText() })
        val arr = root.getJSONArray("videos")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val caps = o.getJSONArray("captions")
            // The catalogue's marks carry a Chinese and a Latin line; both are kept, and the UI
            // language picks at display time, so a switch after this was loaded still reads right.
            fun lines(at: Int) = (0 until caps.length()).map { j -> caps.getJSONArray(j).let { it.getInt(0) to it.getString(at).replace(' ', ' ') } }
            Aerial(
                id = o.getString("id"),
                label = o.getString("label"),
                category = o.getString("category"),
                url = o.getString("url"),
                uhd = o.optString("uhd").ifEmpty { o.getString("url") },
                dolbyVision = o.optString("dv").ifEmpty { o.getString("url") },
                captionsZh = lines(1),
                captionsLatin = lines(2),
            )
        // One shelf of Apple's catalogue at a time, chosen in Personalize (1.80); the whole
        // catalogue stays in assets/aerials.json. An unknown saved value falls back to space.
        }.filter { film -> film.category == kind && (PICKED[kind]?.any { film.id.startsWith(it) } ?: true) }
    }

    /**
     * Two films a shelf (2.03), the owner's choice: the Earth by day and China's coast from orbit;
     * Hong Kong over the harbour and over the Peak — Apple's catalogue carries no Taipei, and no
     * other town of China's — and two shallow shoals, no deep water. Landscapes: none.
     */
    private val PICKED = mapOf(
        "space" to setOf(
            "4F881F8B",  // Caribbean Day: the Earth in daylight, the north American plains below
            "6324F6EB",  // China: south-east over Asia, the Chinese coast
        ),
        "cities" to setOf(
            "E99FA658",  // Hong Kong: Central, from over Victoria Harbour — the one night film of a
                         // Chinese town Apple has; the other four Hong Kong films are daylight
                         // (frames checked: the harbour at dusk, the rest in full sun, 2.08).
        ),
        "underwater" to setOf(
            "537A4DAB",  // Jacks: a shoal of six-banded jacks off Borneo
            "687D03A2",  // Bumpheads: bumphead parrotfish on a Bornean reef
        ),
    )

    val KINDS = setOf("space", "cities", "underwater")
}
