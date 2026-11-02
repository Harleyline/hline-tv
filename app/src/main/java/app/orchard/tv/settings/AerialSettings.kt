package app.orchard.tv.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import android.os.SystemClock
import android.provider.Settings
import app.orchard.tv.apps.IconLibrary
import app.orchard.tv.guard.GuardSetup
import app.orchard.tv.system.DeviceClass
import app.orchard.tv.system.IdlePower
import app.orchard.tv.wallpaper.AerialQuality
import java.security.MessageDigest

/**
 * How the focused app shows itself, named after tvOS's Accessibility > Increase Contrast > Focus Style.
 *
 * tvOS's standard focus relies on the Siri Remote's touch surface: the icon tilts with the thumb, and
 * that motion is what the eye follows. With the D-pad remotes these TVs ship with, the icon only
 * grows, and owners lost track of it. High Contrast adds a white frame and is the default here.
 */
enum class FocusStyle(val key: String) {
    Standard("standard"), HighContrast("high_contrast");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: Standard
    }
}

/**
 * Where interface sounds come from. The TV's own are silent wherever its key sound setting is off,
 * which many TVs ship with (Sony, issue #160; #169), so that is not the default there.
 */
enum class UiSounds(val key: String) {
    Tv("tv"), Off("off");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key }
    }
}

/**
 * How much of the interface's light and motion to draw. Full is the glass panels, a focused tile's
 * sheen, parallax and shadow, and the panel's highest refresh rate. Lite draws the panels in plain
 * dark glass (no offscreen layer per frame), a focused tile without sheen, parallax or shadow, and
 * stays at 60 Hz: for TVs whose GPU cannot keep up (issues #232, #354). Auto is Lite on a low-end
 * TV (DeviceClass) and Full elsewhere.
 */
enum class Effects(val key: String) {
    Auto("auto"), Full("full"), Lite("lite");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: Auto
    }
}

/**
 * The language Aerial speaks, chosen in Personalize rather than on the TV: the firmware's own
 * language menu is often four entries deep and, on the sets this ships to, cannot be set over
 * ADB at all. System follows the TV; every other entry overrides it for Aerial alone, through
 * [MainActivity]'s attachBaseContext, so the date, the clock and the weather's place change too.
 */
enum class AppLanguage(val tag: String) {
    System(""), Zh("zh"), ZhTw("zh-TW"), En("en"), Ja("ja"), Ko("ko"),
    Fr("fr"), Ru("ru"), Es("es"), De("de"), Th("th"), Pt("pt"), Ar("ar");

    companion object {
        fun of(tag: String?) = entries.firstOrNull { it.tag == (tag ?: "") } ?: System
    }
}

/** Aerial's own preferences. Compose state, written on the main thread only. */
object AerialSettings {
    private const val PREFS = "settings"
    private var started = false
    private lateinit var app: Context

    var focusStyle by mutableStateOf(FocusStyle.Standard); private set

    /** The language Aerial speaks; [AppLanguage.System] follows the TV. */
    var language by mutableStateOf(AppLanguage.System); private set


    /** TV inputs pinned to Home as tiles, by TvInputInfo id. */
    var pinnedSources by mutableStateOf<Set<String>>(emptySet()); private set

    /**
     * The input to open when the TV is switched on (a set-top box, an Apple TV), by TvSource id;
     * null for none. Home still comes back to Aerial. Issue #25.
     */
    var bootSource by mutableStateOf<String?>(null); private set

    /**
     * An app to open when the TV is switched on instead of an input, by package name: a live TV
     * app that plays the last channel. Only one of this and [bootSource] is set.
     */
    var bootApp by mutableStateOf<String?>(null); private set

    /**
     * Only the top row on Home, low on the screen, so the wallpaper (a family photo) shows above it,
     * as tvOS does under a full-screen top shelf. The rest of the grid is one press down.
     */
    var oneRowHome by mutableStateOf(true); private set

    /**
     * Apps a row on Home: [HOME_COLUMNS], five by default as Aerial always had, six as tvOS 26 lays
     * them out, four for bigger tiles from the sofa. Folders follow it.
     */
    var homeColumns by mutableIntStateOf(DEFAULT_HOME_COLUMNS); private set

    /**
     * Home shows only its first this many tiles, for trying a nearly empty Home on a TV that has
     * many apps (ConfigReceiver preview_apps). Kept in memory only: nothing is hidden or moved,
     * and the next start, or 0, shows them all again.
     */
    var previewApps by mutableIntStateOf(0)

    /** In one-row mode, the top of the second row peeking under the first; off hides it until you go down. */
    var peekNextRow by mutableStateOf(true); private set

    /**
     * How long the desktop sits without a key before the TV goes to standby (IdlePower). One
     * minute in a bedroom, where the set is hardly used; thirty in a living room, where a set
     * that sleeps mid-film is a nuisance. Chosen in Personalize, a minute at a time.
     *
     * Aerial used to drop the picture to 20% on the way there; the dimming never moved the
     * panel on the TVs that matter (the firmware drives the backlight itself), so it is gone:
     * the desktop is itself until it sleeps (the owner, 1.74).
     */
    var standbyMinutes by mutableIntStateOf(DEFAULT_STANDBY_MINUTES); private set

    /** When the TV suspends, in milliseconds. */
    val standbyAfterMs get() = standbyMinutes * 60_000L

    /** Interface sounds (system/Sounds), as tvOS plays them on every press. */
    var uiSounds by mutableStateOf(UiSounds.Tv); private set

    /** The Liquid Glass plate under the top row; some prefer the apps straight on the wallpaper (#151). */
    var homePlate by mutableStateOf(true); private set

    /** The clock, with the date and lunar day, in the top right instead of the top left. */
    var clockRight by mutableStateOf(true); private set

    /** Whether Home shows the clock at all — the same 关 / 小 / 大 the weather is given, rather than
     *  a size with no way to turn it off (the owner, 2.23). */
    var showClock by mutableStateOf(true); private set

    /** The date and lunar day over a big clock above the top row, instead of one small line. */
    var largeClock by mutableStateOf(true); private set

    /** The weather as a block of its own (icon, degrees, humidity), in the top right instead of the left. */
    var weatherRight by mutableStateOf(false); private set

    /** The weather large: the icon and degrees big, the place, sky and humidity under them. */
    var weatherLarge by mutableStateOf(false); private set

    /**
     * The moving wallpaper (aerial films, the owner's own videos) plays on the top row whatever the
     * keys do, under a little shade, rather than only once Home has sat still (issues #360, #456).
     * The grid below and panels still stop it: they cover it.
     */
    var wallpaperAlwaysMoves by mutableStateOf(false); private set

    /** The weather beside the clock; off for those who want the time alone (issue #472). */
    var showWeather by mutableStateOf(true); private set

    /** The air's index beside the weather (the owner, 1.74: show it, or leave the line to the sky). */
    var showAqi by mutableStateOf(true); private set

    /** The air's humidity beside the weather. */
    var showHumidity by mutableStateOf(true); private set

    /**
     * The time over Aerial's aerial and photo screensavers (issue #240: TCL's firmware puts its own
     * clock over the system screensaver). The clock screensaver is the clock and keeps it.
     */
    var screensaverClock by mutableStateOf(true); private set

    /** Every app's name under its tile, not only the focused one's (issue #28), the top row's on the glass plate too. */
    var showAllNames by mutableStateOf(false); private set

    /** Back on Home's top row starts the screensaver, as on tvOS; off for homes where it gets pressed by accident (issue #94). */
    var backStartsScreensaver by mutableStateOf(true); private set

    /**
     * Parental PIN (issue #10): asked before TV settings open and before hidden apps come back.
     * Kept as a SHA-256 hash. One right entry holds for a while, until Home is pressed, Aerial
     * leaves the screen or the screensaver starts.
     */
    var pinSet by mutableStateOf(false); private set

    /**
     * Whether Aerial may send its anonymous install line and, when asked, a feedback log
     * (system/UsageReport). null until the owner answers the card in Personalize's General tab:
     * nothing is sent while it is null, so a yes is an answer given, not assumed.
     */
    var reportConsent by mutableStateOf<Boolean?>(null); private set

    /**
     * The PIN is four presses of the arrow keys rather than four digits: quicker on a remote with
     * no number keys, and a child watching sees nothing on screen to copy (issue #368).
     */
    var pinArrows by mutableStateOf(false); private set
    private var pinHash: String? = null
    private var unlockedUntil = 0L
    private const val UNLOCK_MS = 10 * 60 * 1000L

    /**
     * Minutes of no input on Home before Aerial's screensaver starts (issue #66). The firmware's own
     * screensaver still caps it: Aerial starts just ahead of that one, see MainActivity.
     * [NEVER] starts none, and switches off the system's while that is Aerial's (issue #333).
     */
    var screensaverMinutes by mutableStateOf(DEFAULT_SCREENSAVER_MINUTES); private set

    const val NEVER = 0

    /** One minute at a time from 2 to 20: the owner asked for a stepper rather than five fixed
     *  delays, which also left the row's label cut in a section narrower than the whole sheet. */
    val SCREENSAVER_MINUTES = 2..20

    /** Which of Apple's files the aerial screensaver plays; Auto is the best this TV decodes (issues #198, #217). */
    var aerialQuality by mutableStateOf(AerialQuality.Auto); private set

    /**
     * Which shelf of Apple's aerial catalogue the films come from: the earth from orbit, cities,
     * landscapes or the sea. One shelf at a time, chosen in Personalize; "space" is what Aerial
     * shipped with.
     */
    var aerialKind by mutableStateOf("space"); private set

    var effects by mutableStateOf(Effects.Auto); private set

    /** This TV counts as low-end for [Effects.Auto]; see DeviceClass. */
    var lowEnd = false; private set

    /** Draw the lighter interface (see [Effects]). */
    // Auto used to drop to Lite on a low-end TV, which also dropped the tile's light and shadow.
    // This build keeps the full material unless Lite is asked for by hand.
    val lite: Boolean get() = effects == Effects.Lite
    private const val DEFAULT_SCREENSAVER_MINUTES = 5

    /** The shelves of Apple's aerial catalogue, as [aerialKind] holds them (2.03: three left). */
    val AERIAL_KINDS = setOf("space", "cities", "underwater")

    val HOME_COLUMNS = 4..6
    private const val DEFAULT_HOME_COLUMNS = 5

    /** The standby delay, in minutes: one at a time, as the owner asked (1.74). */
    val STANDBY_MINUTES = 1..30
    private const val DEFAULT_STANDBY_MINUTES = 5

    /**
     * Everything that decides how Home looks and behaves, on one line, so one TV's choices can be
     * read off it and written into another (ConfigReceiver: dump_settings, then the same names as
     * extras). Per-TV things — the boot target, the pinned inputs, the PIN, the wallpaper — are not
     * in it: they belong to the room, not to the look.
     */
    fun summary(): String = buildString {
        append("one_row=$oneRowHome columns=$homeColumns peek=$peekNextRow plate=$homePlate")
        append(" names=$showAllNames clock_right=$clockRight large_clock=$largeClock")
        append(" weather=$showWeather weather_right=$weatherRight weather_large=$weatherLarge")
        append(" saver_clock=$screensaverClock moves=$wallpaperAlwaysMoves")
        append(" back_saver=$backStartsScreensaver sounds=${uiSounds.key} effects=${effects.key}")
        append(" quality=${aerialQuality.key} saver_min=$screensaverMinutes preview=$previewApps")
        append(" standby=$standbyMinutes")
    }

    fun init(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        focusStyle = FocusStyle.of(prefs.getString("focus_style", null))
        language = AppLanguage.of(prefs.getString("language", null))
        pinnedSources = prefs.getStringSet("pinned_sources", null)?.toSet() ?: emptySet()
        bootSource = prefs.getString("boot_source", null)
        bootApp = prefs.getString("boot_app", null)
        pinHash = prefs.getString("pin_hash", null)
        pinSet = pinHash != null
        // Answered once, kept for good: an unanswered card stays unanswered, and nothing is sent.
        reportConsent = when (prefs.getString("report_consent", null)) {
            "yes" -> true
            "no" -> false
            else -> null
        }
        pinArrows = prefs.getBoolean("pin_arrows", false)
        oneRowHome = prefs.getBoolean("one_row_home", true)
        peekNextRow = prefs.getBoolean("peek_next_row", true)
        // A set already in use kept the dim and the sleep apart; what it had for both together is
        // what it now waits in all (the owner, 1.74).
        val kept = prefs.getInt("idle_dim_minutes", DEFAULT_STANDBY_MINUTES) + prefs.getInt("idle_sleep_minutes", 0)
        standbyMinutes = prefs.getInt("standby_minutes", kept).takeIf { it in STANDBY_MINUTES } ?: DEFAULT_STANDBY_MINUTES
        homeColumns = prefs.getInt("home_columns", DEFAULT_HOME_COLUMNS).takeIf { it in HOME_COLUMNS } ?: DEFAULT_HOME_COLUMNS
        clockRight = prefs.getBoolean("clock_right", true)
        showClock = prefs.getBoolean("show_clock", true)
        largeClock = prefs.getBoolean("large_clock", true)
        weatherRight = prefs.getBoolean("weather_right", false)
        weatherLarge = prefs.getBoolean("weather_large", false)
        // 1.70 split the one clock block into a weather module and a clock module. A copy from
        // before took the new defaults once: weather at the top left, the big date-and-time clock
        // at the top right, which is the layout the split was made for.
        if (!prefs.getBoolean("modules_v1", false)) {
            weatherRight = false
            weatherLarge = false
            clockRight = true
            largeClock = true
            prefs.edit {
                putBoolean("modules_v1", true)
                putBoolean("weather_right", false)
                putBoolean("weather_large", false)
                putBoolean("clock_right", true)
                putBoolean("large_clock", true)
            }
        }
        homePlate = prefs.getBoolean("home_plate", true)
        showAllNames = prefs.getBoolean("show_all_names", false)
        wallpaperAlwaysMoves = prefs.getBoolean("wallpaper_always_moves", false)
        showWeather = prefs.getBoolean("show_weather", true)
        showAqi = prefs.getBoolean("show_aqi", true)
        showHumidity = prefs.getBoolean("show_humidity", true)
        screensaverClock = prefs.getBoolean("screensaver_clock", true)
        // Before 1.28 this was on or off; on meant whichever sounds Aerial played.
        uiSounds = UiSounds.of(prefs.getString("ui_sounds_kind", null)) ?: when {
            !prefs.getBoolean("ui_sounds", true) -> UiSounds.Off
            Settings.System.getInt(app.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED, 1) == 1 -> UiSounds.Tv
            else -> UiSounds.Tv
        }
        backStartsScreensaver = prefs.getBoolean("back_starts_screensaver", true)
        // A value kept from the five fixed delays (1, 5, 10, 30) is brought into the stepper's
        // range on the way in, rather than leaving the panel on a number it cannot show.
        val keptSaver = prefs.getInt("screensaver_minutes", DEFAULT_SCREENSAVER_MINUTES)
        screensaverMinutes = if (keptSaver == NEVER) NEVER else keptSaver.coerceIn(SCREENSAVER_MINUTES)
        aerialQuality = AerialQuality.of(prefs.getString("aerial_quality", null))
        aerialKind = prefs.getString("aerial_kind", null)?.takeIf { it in AERIAL_KINDS } ?: "space"
        effects = Effects.of(prefs.getString("effects", null))
        lowEnd = DeviceClass.lowEnd(app)
    }

    fun chooseBootSource(id: String?) {
        bootSource = id
        bootApp = null
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("boot_source", id).remove("boot_app") }
    }

    fun chooseBootApp(pkg: String?) {
        bootApp = pkg
        bootSource = null
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("boot_app", pkg).remove("boot_source") }
    }

    /** Something opens when the TV is switched on. */
    val hasBootTarget: Boolean get() = bootSource != null || bootApp != null

    val locked: Boolean get() = pinSet && SystemClock.elapsedRealtime() > unlockedUntil

    fun unlock(pin: String): Boolean {
        if (hash(pin) != pinHash) return false
        unlockedUntil = SystemClock.elapsedRealtime() + UNLOCK_MS
        return true
    }

    fun relock() { unlockedUntil = 0L }

    /** Sets the PIN, digits or [arrows] (U, D, L, R), or turns it off with null. */
    fun choosePin(pin: String?, arrows: Boolean = false) {
        pinHash = pin?.let(::hash)
        pinSet = pinHash != null
        pinArrows = pin != null && arrows
        // Locked from the start: a parent who sets the PIN and hands the remote over expects it
        // to hold at once, not after the next Home press (issue #718).
        unlockedUntil = 0L
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("pin_hash", pinHash); putBoolean("pin_arrows", pinArrows) }
    }

    private fun hash(pin: String): String =
        MessageDigest.getInstance("SHA-256").digest("aerial:$pin".toByteArray()).joinToString("") { "%02x".format(it) }

    fun chooseOneRowHome(on: Boolean) {
        oneRowHome = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("one_row_home", on) }
    }

    fun chooseHomeColumns(columns: Int) {
        if (columns !in HOME_COLUMNS) return
        homeColumns = columns
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putInt("home_columns", columns) }
    }

    /**
     * How long the desktop waits before the TV sleeps (IdlePower). The delay the TV itself works
     * to is written again at once, so the choice is in force before the sheet is closed.
     */
    fun chooseStandbyMinutes(minutes: Int) {
        standbyMinutes = minutes.coerceIn(STANDBY_MINUTES)
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putInt("standby_minutes", standbyMinutes) }
        IdlePower.arm(app)
    }

    fun choosePeekNextRow(on: Boolean) {
        peekNextRow = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("peek_next_row", on) }
    }

    fun chooseUiSounds(kind: UiSounds) {
        uiSounds = kind
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("ui_sounds_kind", kind.key) }
    }

    fun chooseHomePlate(on: Boolean) {
        homePlate = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("home_plate", on) }
    }

    fun chooseClockRight(on: Boolean) {
        clockRight = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("clock_right", on) }
    }

    fun chooseShowClock(on: Boolean) {
        showClock = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("show_clock", on) }
    }

    fun chooseLargeClock(on: Boolean) {
        largeClock = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("large_clock", on) }
    }

    fun chooseWeatherRight(on: Boolean) {
        weatherRight = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("weather_right", on) }
    }

    fun chooseWeatherLarge(on: Boolean) {
        weatherLarge = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("weather_large", on) }
    }

    fun chooseShowAllNames(on: Boolean) {
        showAllNames = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("show_all_names", on) }
    }

    fun chooseWallpaperAlwaysMoves(on: Boolean) {
        wallpaperAlwaysMoves = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("wallpaper_always_moves", on) }
    }

    fun chooseShowWeather(on: Boolean) {
        showWeather = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("show_weather", on) }
    }

    fun chooseShowAqi(on: Boolean) {
        showAqi = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("show_aqi", on) }
    }

    fun chooseShowHumidity(on: Boolean) {
        showHumidity = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("show_humidity", on) }
    }

    /** The owner's answer on the anonymous install line (system/UsageReport). */
    fun chooseReportConsent(on: Boolean) {
        reportConsent = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("report_consent", if (on) "yes" else "no") }
    }

    fun chooseScreensaverClock(on: Boolean) {
        screensaverClock = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("screensaver_clock", on) }
    }

    fun chooseBackStartsScreensaver(on: Boolean) {
        backStartsScreensaver = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("back_starts_screensaver", on) }
    }

    fun chooseAerialQuality(quality: AerialQuality) {
        aerialQuality = quality
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("aerial_quality", quality.key) }
    }

    fun chooseAerialKind(kind: String) {
        if (kind !in AERIAL_KINDS) return
        aerialKind = kind
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("aerial_kind", kind) }
    }

    fun chooseScreensaverMinutes(minutes: Int) {
        screensaverMinutes = if (minutes == NEVER) NEVER else minutes.coerceIn(SCREENSAVER_MINUTES)
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putInt("screensaver_minutes", screensaverMinutes) }
        GuardSetup.setSystemScreensaverOff(app, screensaverMinutes == NEVER)
    }

    fun setPinned(inputId: String, pinned: Boolean) {
        pinnedSources = if (pinned) pinnedSources + inputId else pinnedSources - inputId
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putStringSet("pinned_sources", pinnedSources) }
    }


    fun chooseEffects(choice: Effects) {
        effects = choice
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("effects", choice.key) }
    }

    fun chooseFocusStyle(style: FocusStyle) {
        focusStyle = style
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("focus_style", style.key) }
    }

    /**
     * The language, for the next attach and every screen after it; the caller rebuilds the
     * activity so the change shows at once. Written the same way [localeTag] reads it, before
     * [init] has necessarily run (attachBaseContext).
     */
    fun chooseLanguage(language: AppLanguage) {
        this.language = language
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(LANGUAGE_KEY, language.tag) }
        // The test hook in [localized] overrides this setting, so a leftover
        // `settings put global aerial_locale_preview xx-XX` (a screenshot session, a report) would
        // keep the old language on screen and make choosing one here look broken. Choosing is the
        // owner speaking: the hook goes.
        runCatching { Settings.Global.putString(app.contentResolver, "aerial_locale_preview", null) }
    }

    private const val LANGUAGE_KEY = "language"

    /** The tag the activity should attach with, read straight from the prefs: null follows the TV. */
    fun localeTag(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(LANGUAGE_KEY, null)
            ?.takeIf { it.isNotEmpty() && AppLanguage.of(it) != AppLanguage.System }

    /**
     * [context] reading the language chosen in Personalize: its strings, its dates. The activity
     * attaches with this (MainActivity.attachBaseContext), and so does anything that reads a name
     * off the application's context alone — the wallpaper names, which Home takes once at start.
     * A test hook on top: `adb shell settings put global aerial_locale_preview ja-JP` reads that
     * language whatever the setting says.
     */
    fun localized(context: Context): Context {
        val preview = Settings.Global.getString(context.contentResolver, "aerial_locale_preview")
        val tag = preview?.takeIf { it.isNotEmpty() } ?: localeTag(context)
        if (tag.isNullOrEmpty()) return context
        val conf = android.content.res.Configuration(context.resources.configuration)
        conf.setLocale(java.util.Locale.forLanguageTag(tag))
        // The launcher is laid out left to right whatever the language: Arabic's script runs right
        // to left, and mirroring the whole TV UI for it is not what anyone asked for (2.24).
        if (conf.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL) {
            conf.setLayoutDirection(java.util.Locale.US)
        }
        return context.createConfigurationContext(conf)
    }
}
