package app.orchard.tv.ui.wallpaper

import android.app.Activity
import android.os.Build
import android.view.KeyEvent as NativeKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.orchard.tv.BuildConfig
import app.orchard.tv.R
import app.orchard.tv.system.UsageReport
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.settings.AppLanguage
import app.orchard.tv.settings.UiSounds
import app.orchard.tv.settings.Effects
import app.orchard.tv.ui.components.Pressable
import app.orchard.tv.ui.components.consumeTaps
import app.orchard.tv.ui.components.dismissOnTap
import app.orchard.tv.ui.components.liquidGlass
import app.orchard.tv.ui.theme.Glyph
import app.orchard.tv.ui.theme.GlyphIcon
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type
import app.orchard.tv.ui.theme.scriptFamily
import androidx.compose.ui.text.font.FontFamily
import app.orchard.tv.wallpaper.PhoneServer
import app.orchard.tv.wallpaper.AerialFormats
import app.orchard.tv.wallpaper.Aerials
import app.orchard.tv.wallpaper.AerialQuality
import app.orchard.tv.wallpaper.ScreensaverMode
import app.orchard.tv.system.SystemScreensaver
import app.orchard.tv.wallpaper.Wallpaper
import app.orchard.tv.wallpaper.WallpaperKind
import app.orchard.tv.wallpaper.Wallpapers
import app.orchard.tv.weather.Weather
import app.orchard.tv.weather.Cities
import app.orchard.tv.weather.WeatherKind
import app.orchard.tv.ui.home.appLocale
import app.orchard.tv.ui.home.WeatherTurns
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import androidx.compose.ui.geometry.Size

/**
 * Wallpaper and screensaver, chosen on the TV. A sheet rises from the bottom and leaves the top of
 * the screen showing Home, so a new wallpaper can be seen the moment it is chosen. A QR code on the
 * right opens the same settings on a phone, where sending photos is easy.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun WallpaperSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    hiddenCount: Int,
    onHiddenApps: () -> Unit,
    onStartScreensaver: () -> Unit,
    onPin: (on: Boolean) -> Unit,
    /** The sheet is all the way in (true) or not: Home need not draw under it then ([SheetCovers]). */
    onSettled: (Boolean) -> Unit = {},
    /** Draw the closed sheet once, nearly clear, so that its first opening is not its first draw (HomeScreen). */
    warm: Boolean = false,
) {
    val context = LocalContext.current
    var phoneUrl by remember { mutableStateOf<String?>(null) }
    var qr by remember { mutableStateOf<ImageBitmap?>(null) }
    // Two pickers open over the sheet: the language's, and the towns' the weather takes turns through.
    var languagePicker by remember { mutableStateOf(false) }
    var cityPicker by remember { mutableStateOf(false) }
    // Either picker over the sheet: the screen behind it goes frosted, as behind Hidden Apps.
    val pickerUp = languagePicker || cityPicker
    // Which one was last open, so the sheet can take the remote back when it closes: the panel
    // goes away with the focus it held, and the sheet then sat cursorless until a tap.
    var pickerWas by remember { mutableStateOf<String?>(null) }
    val languageChip = remember { FocusRequester() }
    val cityChip = remember { FocusRequester() }
    LaunchedEffect(languagePicker, cityPicker) {
        if (languagePicker || cityPicker || pickerWas == null) return@LaunchedEffect
        val back = if (pickerWas == "city") cityChip else languageChip
        pickerWas = null
        // The panel leaves over 150 ms and holds the cursor while it does; the chip's grab is
        // retried over many frames, until it takes (or nearly a second is spent in vain).
        repeat(60) {
            withFrameNanos { }
            if (runCatching { back.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }

    // The sheet's own background is a deep translucent tint (1.96), not the frosted picture it
    // was through 1.95: the blur needed the film's frames to chase (sampling, fading, an ambient
    // stand-in while a new film loaded) and still read as an opaque slab. A tint over the wallpaper
    // — which the strip below draws behind the sheet — shows the picture through, keeps the rows
    // readable, and has nothing to wait for.
    // The towns take turns here as they do on Home: the panel is open a while, and its weather row
    // was showing the located town whatever Home had turned to (the owner, 1.74). Only while the
    // panel is up — a hidden sheet ticking them too meant a turn every five seconds (1.83).
    WeatherTurns(Weather.rotating && visible)

    BackHandler(enabled = visible, onBack = onDismiss)

    // The phone page listens all along (PhoneServer.listen); the address is read again each time,
    // as the TV's may have changed. A TV waking from an update or standby may not have its Wi-Fi
    // yet: keep trying while the sheet is open, so the code appears the moment the network does.
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        while (true) {
            val url = withContext(Dispatchers.IO) { PhoneServer.start(context) }
            phoneUrl = url
            // The code lands on the photo upload, the thing people open it for from here; the plain
            // address (typed into a computer) stays without it.
            qr = url?.let { withContext(Dispatchers.Default) { PhoneServer.qr("$it#upload").asImageBitmap() } }
            if (url != null) break
            delay(3_000)
        }
    }

    val shown = remember { MutableTransitionState(false) }
    shown.targetState = visible
    val settled = shown.isIdle && shown.currentState
    LaunchedEffect(settled) { onSettled(settled) }

    AnimatedVisibility(visible = visible, enter = fadeIn(tween(220, easing = Motion.EaseOut)), exit = fadeOut(tween(180, easing = Motion.EaseOut)), modifier = Modifier.fillMaxSize()) {
        // A tap above the sheet closes it, as Back does. No dim behind: the wallpaper above the
        // sheet stays as it is, and the half-black band it drew over the sheet's head read as a
        // strip of another material on top of the panel (the owner, 1.74).
        Box(Modifier.fillMaxSize().dismissOnTap(onDismiss))
    }

    // The wallpaper itself, under the sheet. The sheet is the dock's glass now (translucent), so
    // this is what lies behind it: the wallpaper as it is — the stopped frame, else the film's
    // latest frosted frame — behind the whole sheet, corners' notches included. Once it settles
    // Home draws nothing under it (SheetCovers); films, videos and inputs show through surfaces
    // of their own (1.88).
    // A picker over the sheet frosts the whole screen instead (2.15): the wallpaper blurred small
    // and shaded, exactly the picture behind Hidden Apps, so the panel reads as laid on Home
    // rather than on a black pane — the owner's reference is that sheet, not a dark scrim.
    val frostedUp by animateFloatAsState(
        if (pickerUp) 1f else 0f,
        tween(300, easing = Motion.EaseOut),
        label = "sheet-frost",
    )
    AnimatedVisibility(visible = visible, enter = fadeIn(tween(220, easing = Motion.EaseOut)), exit = fadeOut(tween(180, easing = Motion.EaseOut)), modifier = Modifier.fillMaxSize()) {
        val wp = Wallpapers.resolve()
        val still = Wallpapers.backdrop
        if (wp != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                // Full screen, as Home draws it: the strip the sheet stands on is the picture
                // continued, so the sheet's rounded corners lie over wallpaper and nothing else —
                // a strip of its own, cropped anew to its own size, met the picture above the
                // sheet at a seam, which is the corner the owner keeps seeing (1.100).
                val standIn = still ?: Wallpapers.filmFrost ?: Wallpapers.frosted
                if (standIn != null) {
                    Image(
                        standIn, null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 1f - frostedUp },
                    )
                }
                if (frostedUp > 0.001f) {
                    val frosted = Wallpapers.frosted
                    if (frosted != null) {
                        Image(
                            frosted, null, contentScale = ContentScale.Crop, filterQuality = FilterQuality.Low,
                            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = frostedUp },
                        )
                        // The same shade the grid puts on it (HomeChrome's GRID_SHADE): blurred and
                        // dark, the panels over it keep their contrast on a bright picture.
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f * frostedUp)))
                    } else {
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f * frostedUp)))
                    }
                }
            }
        }
    }

    // The sheet as it opens, or (takeFocus false) drawn once nearly clear for the warm-up.
    val sheet: @Composable (takeFocus: Boolean) -> Unit = { takeFocus ->
        Row(
            Modifier
                .fillMaxWidth()
                .height(SheetHeight)
                .consumeTaps()
                // The wallpaper behind the sheet, blurred and opaque inside its outline: what is
                // under the panel reads as colour and shape only, and no window black can show at
                // its corners (the owner, 1.83).
                .focusProperties { if (languagePicker || cityPicker) enter = { FocusRequester.Cancel } }
                // The dock's own material, edge and all (2.00), a shade deeper (2.01): the same
                // glass, the same lit rim, the same rounded corners, with the veil that keeps the
                // rows readable on a bright picture — the owner wants the contrast high.
                .liquidGlass(SheetCorner, bottomCorners = false, deeper = true)
                .padding(start = 40.dp, end = 32.dp, top = 24.dp),
        ) {
            val scope = rememberCoroutineScope()
            // Six sections, one at a time behind the rail on the left, rather than every setting in
            // one column: the canvas is 540 dp tall and the sheet 360, so all of Home's settings
            // came to three or four screens of scrolling (the owner, 2.20). A section is one screen,
            // and a row holds two of the shorter settings side by side.
            var tab by remember { mutableStateOf(PersonalizeTab.Wallpaper) }
            // The cards take the remote the first time the sheet opens; coming back to the rail's
            // Wallpaper item later must not steal it from the rail.
            var tookFocus by remember { mutableStateOf(false) }
            // The feedback log's press: on its way, and what came of the last one.
            var sending by remember { mutableStateOf(false) }
            var outcome by remember { mutableStateOf<UsageReport.Sent?>(null) }
            val scroll = rememberScrollState()
            // A section opens at its top: a scroll left over from a long one (the towns) put the
            // next one halfway down.
            LaunchedEffect(tab) { scroll.scrollTo(0) }

            PersonalizeRail(tab) { tab = it }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                // The section's name stays put while its rows scroll under it: a long section (six
                // rows of settings) scrolled its own title off the top, which read as a broken pane.
                BasicText(stringResource(tab.label), style = Type.LargeTitle)
                Spacer(Modifier.height(10.dp))
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                        when (tab) {
                            // The picture behind everything, then which shelf of films it comes from.
                            PersonalizeTab.Wallpaper -> {
                                WallpaperRows(takeFocus && !tookFocus) { tookFocus = true }
                                Spacer(Modifier.height(10.dp))
                                // Only while an aerial is the wallpaper: a second-level choice of the
                                // aerial has no business standing under a static picture, where picking
                                // it did nothing (the owner, 1.94).
                                if (Wallpapers.resolve()?.kind == WallpaperKind.Live && Aerials.KINDS.size > 1) Group { note ->
                                    Choices(
                                        R.string.st_aerial_kind, null, note,
                                        listOf(
                                            "space" to R.string.st_aerial_space,
                                            "cities" to R.string.st_aerial_cities,
                                            "underwater" to R.string.st_aerial_underwater,
                                        ),
                                        AerialSettings.aerialKind, AerialSettings::chooseAerialKind, first = true,
                                    )
                                }
                                // Only for the owner's videos: whether keys stop them. The aerial films always move.
                                if (Wallpapers.isVideo()) Group { note ->
                                    Choices(
                                        R.string.st_play, stringResource(R.string.st_play_hint), note,
                                        listOf(false to R.string.st_play_still, true to R.string.st_play_always),
                                        AerialSettings.wallpaperAlwaysMoves, AerialSettings::chooseWallpaperAlwaysMoves, first = true,
                                    )
                                }
                            }

                            // What is on Home and where: the rows, the grid, the names, the plate, the
                            // peek and the apps taken off it — one setting to a row. Side by side, the
                            // two halves left ~235 dp for a name and its control, and a longer name in a
                            // longer language (「下一排预览」, 表示行数, «Contrôle parental») ran out of
                            // room and was cut with an ellipsis (the owner, 2.21).
                            PersonalizeTab.Layout -> Group { note ->
                                SettingRow(R.string.st_layout, stringResource(R.string.st_home_layout_hint), note, first = true) {
                                    Picker(
                                        listOf(true to R.string.st_home_one_row, false to R.string.st_home_standard),
                                        AerialSettings.oneRowHome, AerialSettings::chooseOneRowHome,
                                    )
                                }
                                SettingRow(R.string.st_columns, stringResource(R.string.st_columns_hint), note) {
                                    Picker(
                                        AerialSettings.HOME_COLUMNS.map { it to it.toString() },
                                        AerialSettings.homeColumns, AerialSettings::chooseHomeColumns,
                                    )
                                }
                                SettingRow(R.string.st_names, stringResource(R.string.st_names_hint), note) {
                                    Picker(
                                        listOf(false to R.string.st_names_focused, true to R.string.st_names_all),
                                        AerialSettings.showAllNames, AerialSettings::chooseShowAllNames,
                                    )
                                }
                                SettingRow(R.string.st_plate, stringResource(R.string.st_plate_hint), note) {
                                    Picker(
                                        listOf(true to R.string.st_plate_glass, false to R.string.st_plate_none),
                                        AerialSettings.homePlate, AerialSettings::chooseHomePlate,
                                    )
                                }
                                // Two rows show the second row whole; only one row has a peek to hide.
                                if (AerialSettings.oneRowHome) SettingRow(R.string.st_peek, stringResource(R.string.st_peek_hint), note) {
                                    Picker(
                                        listOf(true to R.string.st_peek_show, false to R.string.st_peek_hide),
                                        AerialSettings.peekNextRow, AerialSettings::choosePeekNextRow,
                                    )
                                }
                                SettingRow(R.string.st_hidden_apps, stringResource(R.string.st_hidden_apps_hint), note) {
                                    HiddenAppsChip(hiddenCount, onHiddenApps)
                                }
                            }

                            // What stands at the top of Home: the two modules, each with a side and a
                            // size. The weather's size is also whether it is there at all, so one
                            // control gives three answers where a switch and a size row gave two.
                            // Every module of the top of Home on a row of its own: weather (one control
                            // for off / small / large), its side, the clock's size and side, and the two
                            // lines under the degrees.
                            PersonalizeTab.Top -> Group { note ->
                                SettingRow(
                                    R.string.wp_weather,
                                    stringResource(if (AerialSettings.showWeather) R.string.st_weather_size_hint else R.string.st_weather_off_hint),
                                    note, first = true,
                                ) { WeatherSize() }
                                SettingRow(R.string.st_weather_place, stringResource(R.string.st_weather_place_hint), note) {
                                    Picker(
                                        listOf(false to R.string.st_clock_left, true to R.string.st_clock_right),
                                        AerialSettings.weatherRight, AerialSettings::chooseWeatherRight,
                                    )
                                }
                                SettingRow(R.string.st_clock_size, stringResource(R.string.st_clock_size_hint), note) {
                                    Picker(
                                        listOf(false to R.string.st_clock_small, true to R.string.st_clock_large),
                                        AerialSettings.largeClock, AerialSettings::chooseLargeClock,
                                    )
                                }
                                SettingRow(R.string.st_clock_place, stringResource(R.string.st_clock_hint), note) {
                                    Picker(
                                        listOf(false to R.string.st_clock_left, true to R.string.st_clock_right),
                                        AerialSettings.clockRight, AerialSettings::chooseClockRight,
                                    )
                                }
                                SettingRow(R.string.st_air, null, note) {
                                    SwitchPill(AerialSettings.showAqi, AerialSettings::chooseShowAqi)
                                }
                                SettingRow(R.string.st_humidity, null, note) {
                                    SwitchPill(AerialSettings.showHumidity, AerialSettings::chooseShowHumidity)
                                }
                            }

                            // What replaces Home when it sits idle, and how long it waits.
                            PersonalizeTab.Saver -> {
                                Group { note ->
                                    SettingRow(
                                        R.string.ss_kind,
                                        when {
                                            Wallpapers.screensaver == ScreensaverMode.Videos -> stringResource(R.string.ss_videos_hint)
                                            Wallpapers.screensaver == ScreensaverMode.System -> stringResource(R.string.ss_system_hint)
                                            Wallpapers.screensaverFollows -> stringResource(R.string.ss_follow_hint)
                                            AerialSettings.backStartsScreensaver -> stringResource(R.string.ss_back_hint)
                                            else -> null
                                        },
                                        note, first = true,
                                    ) { ScreensaverChoice(onStartScreensaver) }
                                    // Only the qualities this TV can play; a 1080p-only TV has nothing to choose.
                                    val qualities = remember { AerialFormats.supported(context) }
                                    if (qualities.size > 1) SettingRow(R.string.ss_quality, stringResource(R.string.ss_quality_hint), note) {
                                        Capsule(
                                            listOf(AerialQuality.Auto to R.string.ss_quality_auto, AerialQuality.Hd to R.string.ss_quality_hd) +
                                                listOf(AerialQuality.Uhd to R.string.ss_quality_uhd, AerialQuality.DolbyVision to R.string.ss_quality_dv)
                                                    .filter { it.first in qualities },
                                            AerialSettings.aerialQuality, AerialSettings::chooseAerialQuality,
                                        )
                                    }
                                    // The firmware's screensaver starts on the firmware's timer: Aerial's
                                    // delay did nothing there, and Xiaomi's 1 minute came before the 5
                                    // chosen (issue #675).
                                    if (Wallpapers.screensaver == ScreensaverMode.System) SettingRow(R.string.ss_delay, null, note) {
                                        val delay = remember { SystemScreensaver.firmwareDelayMs(context) }
                                        BasicText(
                                            when {
                                                delay == null -> stringResource(R.string.ss_delay_system)
                                                delay == 0L -> stringResource(R.string.ss_delay_system_off)
                                                else -> stringResource(R.string.ss_delay_system_minutes, ((delay + 30_000) / 60_000).coerceAtLeast(1).toInt())
                                            },
                                            style = Type.Body.copy(color = Palette.SecondaryLabel),
                                            modifier = Modifier.padding(end = 8.dp),
                                        )
                                    } else SettingRow(R.string.ss_delay, null, note) {
                                        // Five delays in one capsule: as chips they ran past the row in a
                                        // section narrower than the whole sheet.
                                        Capsule(
                                            AerialSettings.screensaverChoices.map {
                                                it to if (it == AerialSettings.NEVER) stringResource(R.string.ss_never) else stringResource(R.string.ss_minutes, it)
                                            },
                                            AerialSettings.screensaverMinutes, AerialSettings::chooseScreensaverMinutes,
                                        )
                                    }
                                    // The clock screensaver is its clock, and the others show none.
                                    val showClock = Wallpapers.screensaver == ScreensaverMode.Aerial || Wallpapers.screensaver == ScreensaverMode.Photos
                                    SettingRow(R.string.ss_back, stringResource(R.string.ss_back_choice_hint), note) {
                                        SwitchPill(AerialSettings.backStartsScreensaver, AerialSettings::chooseBackStartsScreensaver)
                                    }
                                    if (showClock) SettingRow(R.string.ss_show_time, stringResource(R.string.ss_show_time_hint), note) {
                                        SwitchPill(AerialSettings.screensaverClock, AerialSettings::chooseScreensaverClock)
                                    }
                                }
                            }

                            // Where the reading comes from, and the towns it takes turns through.
                            PersonalizeTab.Cities -> Group(
                                // The owner asked when the reading was taken: the hour and the minute it
                                // was fetched, beside what the panel already says about the place.
                                stringResource(
                                    when {
                                        Weather.extras.isNotEmpty() -> R.string.st_cities_hint
                                        Weather.chosen -> R.string.wp_weather_chosen
                                        else -> R.string.wp_weather_guessed
                                    },
                                ) + Weather.fetchedAtText(),
                            ) { note ->
                                SettingRow(R.string.wp_weather_city, null, note, first = true) { WeatherValue() }
                                // Home takes turns through these with the located town, ten seconds each.
                                Weather.extras.forEach { town ->
                                    TownRow(Weather.placeName(town, appLocale().language) ?: town.name, stringResource(R.string.st_cities_hint), note) {
                                        Weather.removeCity(town.name)
                                    }
                                }
                                val full = Weather.extras.size >= Weather.MAX_EXTRA_CITIES
                                SettingRow(
                                    R.string.st_cities,
                                    if (full) stringResource(R.string.st_city_full) else stringResource(R.string.st_cities_hint),
                                    note,
                                ) {
                                    if (full) {
                                        BasicText(
                                            stringResource(R.string.st_city_full),
                                            style = Type.Body.copy(color = Palette.SecondaryLabel),
                                            modifier = Modifier.padding(end = 8.dp),
                                        )
                                    } else {
                                        ActionChip(stringResource(R.string.st_city_add), Glyph.ChevronRight, focusRequester = cityChip) {
                                            pickerWas = "city"
                                            cityPicker = true
                                        }
                                    }
                                }
                            }

                            // Everything about the app itself rather than about Home: its language and
                            // sounds, its pin, and the two rows a reporter is asked for — the anonymous
                            // count and the log to send.
                            PersonalizeTab.General -> {
                                Group(stringResource(R.string.st_language_hint)) { note ->
                                    // The language on a row of its own, at the top: it is the one setting a
                                    // reader who cannot read the rest comes looking for (the owner, 2.21).
                                    SettingRow(R.string.st_language, stringResource(R.string.st_language_hint), note, first = true) {
                                        ActionChip(languageName(AerialSettings.language), Glyph.ChevronRight, focusRequester = languageChip) {
                                            pickerWas = "language"
                                            languagePicker = true
                                        }
                                    }
                                    SettingRow(
                                        R.string.st_sounds,
                                        stringResource(if (AerialSettings.uiSounds == UiSounds.Tv) R.string.st_sounds_hint else R.string.st_sounds_off_hint),
                                        note,
                                    ) {
                                        SwitchPill(AerialSettings.uiSounds == UiSounds.Tv) { on -> AerialSettings.chooseUiSounds(if (on) UiSounds.Tv else UiSounds.Off) }
                                    }
                                    SettingRow(
                                        R.string.st_effects,
                                        stringResource(
                                            if (AerialSettings.effects == Effects.Auto && AerialSettings.lowEnd) R.string.st_effects_auto_lite_hint
                                            else R.string.st_effects_hint,
                                        ),
                                        note,
                                    ) {
                                        Capsule(
                                            listOf(Effects.Auto to R.string.st_effects_auto, Effects.Full to R.string.st_effects_full, Effects.Lite to R.string.st_effects_lite),
                                            AerialSettings.effects, AerialSettings::chooseEffects,
                                        )
                                    }
                                    SettingRow(R.string.st_pin, stringResource(R.string.st_pin_hint), note) {
                                        PinChoice(onPin) {}
                                    }
                                    // The TV's own sleep: what the desktop does with the remote
                                    // untouched, not what it shows — so it sits here with the rest of the
                                    // box's behaviour rather than under the screensaver (2.20).
                                    SettingRow(R.string.st_standby, stringResource(R.string.st_standby_hint), note) {
                                        Minutes(
                                            AerialSettings.standbyMinutes, AerialSettings.STANDBY_MINUTES,
                                            AerialSettings::chooseStandbyMinutes,
                                        )
                                    }
                                }
                            }

                            // What this TV tells the developer, and the way to tell the developer
                            // something: one row, side by side, with the one-time question above it. A
                            // section of its own so that neither is buried five rows down in General.
                            PersonalizeTab.Privacy -> {
                                if (AerialSettings.reportConsent == null && UsageReport.configured) ConsentCard(
                                    onAllow = {
                                        AerialSettings.chooseReportConsent(true)
                                        UsageReport.start(context)
                                    },
                                    onDeny = { AerialSettings.chooseReportConsent(false) },
                                )
                                Group(stringResource(R.string.st_stats_hint)) { note ->
                                    if (UsageReport.configured) {
                                        SettingRow(
                                            R.string.st_stats, stringResource(R.string.st_stats_hint), note, first = true,
                                        ) {
                                            SwitchPill(AerialSettings.reportConsent == true) { on ->
                                                AerialSettings.chooseReportConsent(on)
                                                if (on) UsageReport.start(context)
                                            }
                                        }
                                        SettingRow(
                                            R.string.st_feedback,
                                            stringResource(
                                                when (outcome) {
                                                    is UsageReport.Sent.Ok -> R.string.st_feedback_sent_hint
                                                    is UsageReport.Sent.Failed -> R.string.st_feedback_failed_hint
                                                    else -> R.string.st_feedback_hint
                                                },
                                            ),
                                            note,
                                        ) {
                                            FeedbackChip(sending, outcome) {
                                                if (sending) return@FeedbackChip
                                                sending = true
                                                // The diagnostics and the log take a moment to gather and a
                                                // moment more to go: off the main thread, and the row says
                                                // what became of it.
                                                scope.launch {
                                                    val sent = withContext(Dispatchers.IO) { UsageReport.upload(context) }
                                                    sending = false
                                                    outcome = sent
                                                }
                                            }
                                        }
                                    } else {
                                        SettingRow(
                                            R.string.st_feedback,
                                            stringResource(R.string.st_feedback_off_hint),
                                            note,
                                            first = true,
                                        ) {
                                            BasicText(
                                                stringResource(R.string.st_feedback_off),
                                                style = Type.Body.copy(color = Palette.SecondaryLabel),
                                                modifier = Modifier.padding(end = 8.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                    }
                    // Six settings to a section are taller than the sheet: the rows scroll, and a row
                    // half under the edge read as a row cut in two. The foot of the list fades instead,
                    // which says there is more below without breaking the row (the owner, 2.21).
                    if (scroll.canScrollForward) {
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(46.dp)
                                .background(
                                    Brush.verticalGradient(
                                        0f to Color.Transparent,
                                        0.55f to Color.Black.copy(alpha = 0.30f),
                                        1f to Color.Black.copy(alpha = 0.62f),
                                    ),
                                ),
                        )
                    }
                }
                // The version on the sheet's last line, whatever the section: the one thing a
                // reporter is always asked for (the owner, 2.20).
                VersionLine()
            }
            Spacer(Modifier.width(20.dp))
            PhoneCard(phoneUrl, qr)
        }
    }

    // Aerial's language: five to choose from beside Follow TV, applied at once (the activity is
    // rebuilt, as every string and date in it was resolved with the old one). Declared after the
    // sheet's Box below: Compose paints late declarations on top, and these were painted under the
    // sheet — two glass panels interleaved, each showing through the other (the owner, 1.74).

    // A picker laid over the sheet: the sheet itself goes (2.15), leaving one panel on the frosted
    // screen — the hidden apps' arrangement, which the owner pointed at as the one to copy. It
    // was dimmed to a fifth through 2.14, and the panel then read as two glass panes interleaved
    // over a dark pane.
    val sheetDim by animateFloatAsState(
        if (pickerUp) 0f else 1f,
        tween(200, easing = Motion.EaseOut),
        label = "sheet-dim",
    )

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        // The first opening composed the sheet and gave the GPU its pictures, glyphs and shaders
        // for the first time: frames of 250 and 316 ms, 1.16 s for the slide-in, against about
        // 140 ms at worst on later openings (Xiaomi MiTV-MFTR0). Drawn once at 1% while Home is
        // idle, without focus (HomeScreen, as Control Center), the first opening is like the next.
        if (warm && !visible) Box(Modifier.graphicsLayer { alpha = 0.01f }) { sheet(false) }
        AnimatedVisibility(
            visibleState = shown,
            enter = fadeIn(tween(200, easing = Motion.EaseOut)) + slideInVertically(tween(380, easing = Motion.Drawer)) { it / 3 },
            exit = fadeOut(tween(160, easing = Motion.EaseOut)) + slideOutVertically(tween(200, easing = Motion.EaseOut)) { it / 4 },
        ) {
            // Alpha without an offscreen buffer, as the clock's aside line: a buffer here is the
            // whole sheet, and it would be made and thrown away on every step of the dim.
            Box(
                Modifier.graphicsLayer {
                    alpha = sheetDim
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                },
            ) { sheet(true) }
        }
    }

    // The pickers, last so they paint over the sheet: the sheet dimmed behind (not blacked out),
    // one glass panel, the hidden apps' animation.
    PickerOverlay(
        visible = languagePicker,
        title = stringResource(R.string.st_language),
        onDismiss = { languagePicker = false },
    ) { first ->
        // Eleven languages no longer fit a panel the height of the canvas: the list scrolls, and
        // Compose brings the focused row into view as the remote walks down it.
        Column(Modifier.heightIn(max = 372.dp).verticalScroll(rememberScrollState())) {
            AppLanguage.entries.forEachIndexed { index, language ->
                PickerRow(
                    languageName(language),
                    on = AerialSettings.language == language,
                    focusRequester = if (index == 0) first else null,
                    // Tibetan and Uyghur in their own cut: the list names all the languages at
                    // once, so the two no TV has a font for must carry it themselves.
                    family = scriptFamily(language.tag),
                ) {
                    languagePicker = false
                    if (AerialSettings.language != language) {
                        AerialSettings.chooseLanguage(language)
                        (context as? Activity)?.recreate()
                    }
                }
            }
        }
    }
    // The towns on the phone page's own list (assets/cities-cn.tsv), where a Chinese name always
    // finds its city: Open-Meteo's geocoder does not know most of them (issue #187).
    CityPickerOverlay(visible = cityPicker, onDismiss = { cityPicker = false }) { city ->
        cityPicker = false
        Weather.addCity(city)
    }
}

/**
 * Every wallpaper in one row, in the order most people want them: the moving aerial, Bing's photo
 * of the day, the owner's own photos, then a few of the built-in pictures and "Follow App". Past
 * daily photos and the rest of the built-ins are left out unless one of them is the current
 * choice. The focused card's note is under the row, as under a group of settings.
 */
@Composable
private fun WallpaperRows(takeFocus: Boolean, onTook: () -> Unit = {}) {
    val items = Wallpapers.items
    val selected = Wallpapers.selectedId
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (!takeFocus) return@LaunchedEffect
        withFrameNanos { }
        if (runCatching { first.requestFocus() }.isSuccess) onTook()
    }
    val dailyTitle = stringResource(R.string.wp_daily_latest)
    val rotateTitle = stringResource(R.string.wp_photos_rotate)
    val videosTitle = stringResource(R.string.wp_videos_rotate)
    val list = remember(items, selected, dailyTitle, rotateTitle, videosTitle) {
        buildList {
            addAll(items.filter { it.kind == WallpaperKind.Live })
            // Each video on its own, and with two or more one card that plays them in turn (#762).
            val videos = items.filter { it.kind == WallpaperKind.Video && it.id != Wallpapers.VIDEOS }
            if (videos.size > 1 || selected == Wallpapers.VIDEOS) add(Wallpaper(Wallpapers.VIDEOS, WallpaperKind.Video, videosTitle))
            addAll(videos)
            if (items.any { it.kind == WallpaperKind.Daily }) add(Wallpaper(Wallpapers.LATEST_DAILY, WallpaperKind.Daily, dailyTitle))
            addAll(items.filter { it.kind == WallpaperKind.Daily && it.id == selected })
            val photos = items.filter { it.kind == WallpaperKind.Photo }
            // With two or more, one card takes turns through all of them, one a day (#27).
            if (photos.size > 1) add(Wallpaper(Wallpapers.ROTATE_PHOTOS, WallpaperKind.Photo, rotateTitle))
            addAll(photos)
            addAll(Wallpapers.offeredBuiltins)
            addAll(items.filter { it.kind == WallpaperKind.Auto })
            // The inputs' pictures last: a choice for a home with a set-top box, not the usual one.
            addAll(items.filter { it.kind == WallpaperKind.Source })
        }
    }
    var note by remember { mutableStateOf<String?>(null) }
    // A row clips to its bounds, and the focused card grows by 8%: room on the left for that,
    // with the row moved back by as much so the cards still line up with the titles.
    LazyRow(
        modifier = Modifier.offset(x = (-10).dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        contentPadding = PaddingValues(start = 14.dp, end = 24.dp, top = 6.dp, bottom = 4.dp),
    ) {
        items(list, key = { it.id }) { wp ->
            val hint = wallpaperNote(wp)
            WallpaperCard(wp, if (wp === list.first()) first else null, onFocused = { note = hint })
        }
    }
    HintText(note, start = 4.dp)
}

/** What a wallpaper card is, for the note under the row; null where its picture says it all. */
@Composable
private fun wallpaperNote(wp: Wallpaper): String? = when {
    wp.kind == WallpaperKind.Live -> stringResource(R.string.wp_live)
    wp.id == Wallpapers.VIDEOS -> stringResource(R.string.wp_videos_note)
    wp.kind == WallpaperKind.Video -> stringResource(R.string.wp_video_note)
    wp.kind == WallpaperKind.Source -> stringResource(R.string.wp_source_note)
    wp.id == Wallpapers.LATEST_DAILY -> stringResource(R.string.wp_daily_note)
    wp.id == Wallpapers.ROTATE_PHOTOS -> stringResource(R.string.wp_photos_rotate_note)
    wp.kind == WallpaperKind.Photo -> stringResource(R.string.wp_photo_note)
    wp.kind == WallpaperKind.Daily -> wp.caption
    else -> null
}

@Composable
private fun WallpaperCard(wp: Wallpaper, focusRequester: FocusRequester?, onFocused: () -> Unit) {
    val selected = Wallpapers.selectedId == wp.id
    val thumb by produceState<ImageBitmap?>(null, wp.id, Wallpapers.items) {
        value = withContext(Dispatchers.Default) { Wallpapers.thumbnail(wp.id)?.asImageBitmap() }
    }
    Pressable(onClick = { Wallpapers.select(wp.id) }, focusRequester = focusRequester, focusedScale = 1.08f) { state ->
        LaunchedEffect(state.focused) { if (state.focused) onFocused() }
        Column(Modifier.width(168.dp)) {
            Box(
                Modifier
                    .size(168.dp, 94.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Palette.Fill)
                    .border(
                        if (state.focused || selected) 3.dp else 1.dp,
                        when {
                            state.focused -> Palette.Focus
                            selected -> Palette.Focus.copy(alpha = 0.55f)
                            else -> Palette.Hairline
                        },
                        RoundedCornerShape(12.dp),
                    ),
            ) {
                thumb?.let {
                    Canvas(Modifier.fillMaxSize()) {
                        drawImage(it, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Medium)
                    }
                }
                if (selected) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(7.dp).size(22.dp).clip(CircleShape).background(Palette.Focus),
                        contentAlignment = Alignment.Center,
                    ) { GlyphIcon(Glyph.Check, Palette.OnFocus, 13.dp) }
                }
                // The aerial wallpaper moves; its card shows the last frame it stopped on.
                if (wp.kind == WallpaperKind.Live || wp.kind == WallpaperKind.Video) {
                    GlyphIcon(Glyph.Play, Color.White, 22.dp, Modifier.align(Alignment.BottomStart).padding(7.dp))
                }
                // An input's picture cannot be copied for a card: its mark over the dark picture.
                if (wp.kind == WallpaperKind.Source) {
                    GlyphIcon(Glyph.Hdmi, Color.White, 34.dp, Modifier.align(Alignment.Center))
                }
            }
            Spacer(Modifier.height(6.dp))
            BasicText(
                wp.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = Type.Caption.copy(color = if (state.focused) Palette.Label else Palette.SecondaryLabel),
            )
        }
    }
}

/**
 * The sheet's sections, in the order a home is set up: the picture, the layout over it, the two
 * modules at the top, what replaces it when idle, the towns the weather turns through, and then the
 * app itself — its language, its pin, its version, and the log to send when something goes wrong.
 * One at a time behind the rail, so each opens on one screen instead of four (the owner, 2.20).
 */
private enum class PersonalizeTab(val label: Int, val glyph: Glyph) {
    Wallpaper(R.string.wp_wallpaper, Glyph.Photo),
    Layout(R.string.st_home_layout, Glyph.Move),
    Top(R.string.st_tab_top, Glyph.CloudSun),
    Saver(R.string.wp_screensaver, Glyph.Moon),
    Cities(R.string.st_cities, Glyph.Place),
    General(R.string.st_tab_general, Glyph.Gear),
    // Last, and on its own: the two settings that leave the box — the anonymous count and the log
    // to send — and the one question asked about them. In General they made five rows, which ran
    // past the bottom of the sheet (the owner, 2.20).
    Privacy(R.string.st_tab_privacy, Glyph.Shield),
}

/**
 * The sections down the sheet's left edge, as the settings on Apple TV have them: the panel beside
 * the rail is that one section, and the rail stays put, so the sheet never becomes a long column.
 */
@Composable
private fun PersonalizeRail(selected: PersonalizeTab, onSelect: (PersonalizeTab) -> Unit) {
    // Moving onto a section opens it, as the tab bars on Apple TV do — but only when the owner
    // moved there. When focus is merely *handed back* to the rail (a picker closed, Hidden Apps
    // came back, the screensaver ended), Compose re-seats it on whichever item lies nearest by
    // geometry, and that item's focus effect opened its own section: backing out of the wallpaper
    // page landed on 天气与时钟, the third item, because the card that had held the focus sits
    // beside it (the owner, 2.22). So the rail remembers whether a D-pad move happened; without
    // one it puts the cursor back on the open section and nothing changes.
    var walked by remember { mutableStateOf(false) }
    val requesters = remember { PersonalizeTab.entries.associateWith { FocusRequester() } }
    // A move off the end of the rail moves no focus, so the flag would still be set for the next,
    // unrelated hand-back to consume: it lapses on its own.
    LaunchedEffect(walked) { if (walked) { delay(400); walked = false } }
    Column(
        Modifier
            .width(176.dp)
            .padding(top = 2.dp)
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && (e.key == Key.DirectionDown || e.key == Key.DirectionUp)) walked = true
                false
            }
            .onFocusChanged { if (!it.hasFocus) walked = false },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PersonalizeTab.entries.forEach { tab ->
            RailItem(
                tab = tab,
                selected = tab == selected,
                focusRequester = requesters.getValue(tab),
                isWalked = { walked },
                // OK on a section, or a walk onto it: that section opens.
                onSelect = { walked = false; onSelect(tab) },
                // Focus arrived without a walk: give it back to the open section.
                onRestored = { walked = false; runCatching { requesters.getValue(selected).requestFocus() } },
            )
        }
    }
}

@Composable
private fun RailItem(
    tab: PersonalizeTab,
    selected: Boolean,
    focusRequester: FocusRequester,
    isWalked: () -> Boolean,
    onSelect: () -> Unit,
    onRestored: () -> Unit,
) {
    Pressable(onClick = onSelect, focusRequester = focusRequester, focusedScale = 1.03f) { state ->
        LaunchedEffect(state.focused) {
            when {
                !state.focused || selected -> Unit
                isWalked() -> onSelect()
                else -> onRestored()
            }
        }
        val bg by animateColorAsState(
            when {
                state.focused -> Palette.Focus
                selected -> Palette.FillStrong
                else -> Color.Transparent
            },
            tween(150, easing = Motion.EaseOut), label = "rail-bg",
        )
        val fg = when {
            state.focused -> Palette.OnFocus
            selected -> Palette.Label
            else -> Palette.SecondaryLabel
        }
        Row(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(bg)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GlyphIcon(tab.glyph, fg, 16.dp)
            BasicText(stringResource(tab.label), style = Type.Caption.copy(color = fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The sheet's last line: which build this TV runs. Whatever section is open, it is the one thing a
 * reporter is always asked for, so it never scrolls away (the owner, 2.20).
 */
@Composable
private fun VersionLine() {
    BasicText(
        stringResource(R.string.st_version_footer, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
        style = Type.Caption.copy(color = Palette.TertiaryLabel),
        // 14 dp off the sheet's edge: at 2 dp the line sat against the bottom of the screen, and
        // most TVs crop a few dp there anyway (overscan).
        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 8.dp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * The feedback row's button, half a row wide: it says what became of the last log — sent, or worth
 * another try — and while one is on its way it says that instead of taking a second press.
 */
@Composable
private fun FeedbackChip(sending: Boolean, outcome: UsageReport.Sent?, onSend: () -> Unit) {
    if (sending) {
        BasicText(
            stringResource(R.string.st_feedback_sending),
            style = Type.Body.copy(color = Palette.SecondaryLabel),
            modifier = Modifier.padding(end = 8.dp),
        )
        return
    }
    ActionChip(
        stringResource(
            when (outcome) {
                is UsageReport.Sent.Ok -> R.string.st_feedback_sent
                is UsageReport.Sent.Failed -> R.string.st_feedback_retry
                else -> R.string.st_feedback_send
            },
        ),
        Glyph.ChevronRight,
        onClick = onSend,
    )
}

/**
 * The one question Aerial asks about itself, once, at the top of General: whether the anonymous
 * install line may go (system/UsageReport). It stays until it is answered — silence is not a yes —
 * and the same answer can be changed afterwards on the row below.
 */
@Composable
private fun ConsentCard(onAllow: () -> Unit, onDeny: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.FillStrong)
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
    ) {
        BasicText(stringResource(R.string.st_stats_title), style = Type.Headline)
        Spacer(Modifier.height(6.dp))
        BasicText(
            stringResource(R.string.st_stats_body),
            style = Type.Caption,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Chip(stringResource(R.string.st_stats_allow), on = false) { onAllow() }
            Chip(stringResource(R.string.st_stats_deny), on = false) { onDeny() }
        }
    }
    Spacer(Modifier.height(10.dp))
}

/**
 * The one line of explanation under a card or a group of rows: two lines of room, measured in the
 * font the language actually uses. A fixed height was the bug — a line of Caption is ~16 dp in
 * Latin and ~24 dp in Japanese, Chinese and Russian, so the fixed box held two Latin lines and one
 * CJK one and cut every Japanese hint into an ellipsis (the owner, 2.22). Reading the line height
 * off the style keeps the box steady for one- and two-line hints, and lets the three-line ones
 * (ру́сский) push it open rather than lose their last line.
 */
@Composable
private fun HintText(text: String?, start: Dp, end: Dp = 0.dp) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val twoLines = with(density) {
        (measurer.measure("国", style = Type.Caption, maxLines = 1, softWrap = false).size.height * 2).toDp()
    }
    Box(Modifier.heightIn(min = twoLines + 5.dp).padding(start = start, top = 5.dp, end = end)) {
        text?.let {
            BasicText(it, style = Type.Caption.copy(color = Palette.TertiaryLabel), maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * A card of setting rows, as in tvOS Settings, with one note under it: the focused row's, or
 * [note] when the row has none or focus is elsewhere. The note keeps two lines of room so the
 * sheet does not shift as focus moves.
 */
@Composable
private fun Group(note: String? = null, rows: @Composable ColumnScope.(note: (String?) -> Unit) -> Unit) {
    var focusedNote by remember { mutableStateOf<String?>(null) }
    var inside by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .onFocusChanged { inside = it.hasFocus },
    ) { rows { focusedNote = it } }
    // Two lines of room, measured rather than guessed: a line of Caption is ~16 dp in Latin and
    // ~24 dp in Japanese and Russian, because a CJK or Cyrillic face carries taller metrics. A
    // fixed 40 dp held two Latin lines and one Japanese one, so the Japanese hint ended in an
    // ellipsis (the owner, 2.22). Taller hints (ру́сский runs to three lines) push the box open
    // instead of being cut.
    HintText(if (inside) focusedNote ?: note else note, start = 16.dp, end = 8.dp)
}

/** One row: the setting's name on the left, its control on the right, a hairline above all but the first. */
@Composable
private fun SettingRow(label: Int, hint: String?, note: (String?) -> Unit, first: Boolean = false, control: @Composable () -> Unit) {
    if (!first) Box(Modifier.padding(start = 18.dp).fillMaxWidth().height(1.dp).background(Palette.Hairline))
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(start = 16.dp, end = 8.dp)
            .onFocusChanged { if (it.hasFocus) note(hint) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Two lines for the name: 表示行数 fits on one, but «Contrôle parental» and
        // «Родительский контроль» do not, and an ellipsis there hid what the row was (the owner,
        // 2.21). Every language the app speaks has to read whole.
        BasicText(
            stringResource(label),
            style = Type.Body,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        control()
    }
}

/** The weather's size, and with it whether there is one: 关 / 小 / 大 in one capsule. A switch for
 *  "show weather" beside a size row asked the same question twice (the owner, 2.20). */
@Composable
private fun WeatherSize() {
    val size = when {
        !AerialSettings.showWeather -> 0
        AerialSettings.weatherLarge -> 2
        else -> 1
    }
    Capsule(
        listOf(0 to R.string.st_off, 1 to R.string.st_clock_small, 2 to R.string.st_clock_large),
        size,
    ) { pick ->
        AerialSettings.chooseShowWeather(pick != 0)
        if (pick != 0) AerialSettings.chooseWeatherLarge(pick == 2)
    }
}

/** Hidden Apps: how many there are, and the way into them. */
@Composable
private fun HiddenAppsChip(count: Int, onClick: () -> Unit) {
    ActionChip(
        if (count == 0) stringResource(R.string.st_hidden_none) else stringResource(R.string.st_hidden_count, count),
        Glyph.ChevronRight,
        onClick = onClick,
    )
}

/** The values of a setting on their own, without the row around them: what a [PairRow] half holds. */
@Composable
private fun <T> Picker(options: List<Pair<T, Any>>, current: T, choose: (T) -> Unit) {
    // Two or three values as one capsule, as iOS splits a switch (the owner, 2.16); more stay
    // chips, where a capsule would be a row of its own with a pill per value.
    if (options.size <= 3) Capsule(options, current, choose)
    else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        options.forEach { (value, text) ->
            Chip(if (text is Int) stringResource(text) else text.toString(), on = current == value) { choose(value) }
        }
    }
}

/** 关 on the left, 开 on the right, one capsule: iOS's switch, as the owner asked (2.16). */
@Composable
private fun SwitchPill(on: Boolean, choose: (Boolean) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Palette.Fill).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Segment(stringResource(R.string.st_off), on = !on) { choose(false) }
        Segment(stringResource(R.string.st_on), on = on) { choose(true) }
    }
}

/** A row of chips for one of a few values. */
@Composable
private fun <T> Choices(
    label: Int,
    hint: String?,
    note: (String?) -> Unit,
    options: List<Pair<T, Any>>,
    current: T,
    choose: (T) -> Unit,
    first: Boolean = false,
) {
    SettingRow(label, hint, note, first) { Picker(options, current, choose) }
}

/**
 * One capsule holding a row of values, the chosen one a white pill inside it — iOS's segmented
 * control, which the owner asked for by name (2.16: "左边关，右边开那种"). The track is the row's
 * own fill, so it sits on the glass without another material.
 */
@Composable
private fun <T> Capsule(options: List<Pair<T, Any>>, current: T, choose: (T) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Palette.Fill).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { (value, text) ->
            Segment(if (text is Int) stringResource(text) else text.toString(), on = current == value) { choose(value) }
        }
    }
}

/**
 * One value in a capsule: white when it is the value in use, its own fill when the cursor is on it,
 * and a white rim around the cursor so the two can be told apart on a white pill.
 */
@Composable
private fun Segment(
    label: String,
    on: Boolean,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    Pressable(onClick = onClick, focusRequester = focusRequester, focusedScale = 1.05f) { state ->
        val bg by animateColorAsState(
            when {
                on -> Palette.Focus
                state.focused -> Palette.FillStrong
                else -> Color.Transparent
            },
            tween(150, easing = Motion.EaseOut), label = "segment-bg",
        )
        val fg = when {
            on -> Palette.OnFocus
            state.focused -> Palette.Label
            else -> Palette.SecondaryLabel
        }
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .then(if (state.focused) Modifier.border(1.6.dp, Color.White, RoundedCornerShape(50)) else Modifier)
                .background(bg)
                .height(32.dp)
                .padding(horizontal = 15.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(label, style = Type.Body.copy(color = fg), maxLines = 1)
        }
    }
}

/**
 * A row for a number of minutes: the remote's left and right take it down and up, one minute at a
 * time, as Control Center's brightness does. Thirty minutes are too many for a row of chips, and
 * a remote has no keys to type a number on.
 */
@Composable
private fun MinutesRow(
    label: Int,
    hint: String?,
    note: (String?) -> Unit,
    minutes: Int,
    range: IntRange,
    choose: (Int) -> Unit,
) {
    SettingRow(label, hint, note, first = true) { Minutes(minutes, range, choose) }
}

/** The stepper itself: a [Pressable] whose left and right are the number, so half a row holds it. */
@Composable
private fun Minutes(minutes: Int, range: IntRange, choose: (Int) -> Unit) {
    Pressable(
        onClick = {},
        focusedScale = 1.03f,
        onKey = { e ->
            val code = e.nativeKeyEvent.keyCode
            if (code != NativeKeyEvent.KEYCODE_DPAD_LEFT && code != NativeKeyEvent.KEYCODE_DPAD_RIGHT) return@Pressable false
            // A held key repeats: thirty minutes is one sweep of the pad.
            if (e.type == KeyEventType.KeyDown) {
                choose((minutes + if (code == NativeKeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1).coerceIn(range))
            }
            true
        },
    ) { state ->
        val bg by animateColorAsState(if (state.focused) Palette.Focus else Palette.FillStrong, tween(140, easing = Motion.EaseOut), label = "minutes-bg")
        val fg = if (state.focused) Palette.OnFocus else Palette.Label
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(bg).height(40.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GlyphIcon(Glyph.Minus, fg, 16.dp)
            BasicText(
                stringResource(R.string.ss_minutes, minutes),
                style = Type.Body.copy(color = fg, textAlign = TextAlign.Center),
                modifier = Modifier.widthIn(min = 52.dp),
            )
            GlyphIcon(Glyph.Plus, fg, 16.dp)
        }
    }
}

/** Parental PIN on or off; either way goes through the PIN pad (issue #10). */
@Composable
private fun PinChoice(onPin: (on: Boolean) -> Unit, onFocused: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(false to R.string.st_pin_off, true to R.string.st_pin_on).forEach { (on, label) ->
            Chip(stringResource(label), on = AerialSettings.pinSet == on, onFocused = onFocused) {
                if (AerialSettings.pinSet != on) onPin(on)
            }
        }
    }
}

/**
 * The place and its reading: the located town's alone, and still — Home takes turns through the
 * towns, but this row stands for where the TV is, and it does not turn over as Home's does (the
 * owner, 1.75). The pin is the located town's; the others were put there by hand.
 */
@Composable
private fun WeatherValue() {
    val slot = Weather.slots.firstOrNull { it.located } ?: Weather.shown
    val w = slot?.now
    val place = Weather.placeName(slot, appLocale().language)
    val kind = w?.let {
        stringResource(
            when (it.kind) {
                WeatherKind.Clear -> R.string.wx_clear
                WeatherKind.PartlyCloudy -> R.string.wx_partly_cloudy
                WeatherKind.Cloudy -> R.string.wx_cloudy
                WeatherKind.Fog -> R.string.wx_fog
                WeatherKind.Rain -> R.string.wx_rain
                WeatherKind.Snow -> R.string.wx_snow
                WeatherKind.Thunder -> R.string.wx_thunder
            },
        )
    }
    Row(
        Modifier.padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
    ) {
        if (slot?.located == true) GlyphIcon(Glyph.Place, Palette.SecondaryLabel, 15.dp)
        BasicText(
            if (w != null) listOfNotNull(place, "${w.celsius}° $kind").joinToString(" · ") else stringResource(R.string.wp_weather_none),
            style = Type.Body.copy(color = Palette.SecondaryLabel),
        )
    }
}

/** A pill to pick one of a few options: a check on the chosen one, white when focused. */
@Composable
private fun Chip(label: String, on: Boolean, onFocused: (() -> Unit)? = null, onClick: () -> Unit) {
    Pressable(onClick = onClick, focusedScale = 1.06f) { state ->
        if (onFocused != null) LaunchedEffect(state.focused) { if (state.focused) onFocused() }
        val bg by animateColorAsState(
            when {
                state.focused -> Palette.Focus
                on -> Palette.FillStrong
                else -> Palette.Fill
            },
            tween(140, easing = Motion.EaseOut), label = "chip-bg",
        )
        val fg = if (state.focused) Palette.OnFocus else Palette.Label
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(bg).height(40.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (on) GlyphIcon(Glyph.Check, fg, 14.dp)
            BasicText(label, style = Type.Body.copy(color = fg))
        }
    }
}

/** A pill that does something rather than choosing a value: white when focused, a glyph after its label. */
@Composable
private fun ActionChip(label: String, glyph: Glyph, focusRequester: FocusRequester? = null, onClick: () -> Unit) {
    Pressable(onClick = onClick, focusRequester = focusRequester, focusedScale = 1.06f) { state ->
        val bg by animateColorAsState(if (state.focused) Palette.Focus else Palette.FillStrong, tween(140, easing = Motion.EaseOut), label = "action-bg")
        val fg = if (state.focused) Palette.OnFocus else Palette.Label
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(bg).height(40.dp).padding(start = 16.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            BasicText(label, style = Type.Body.copy(color = fg))
            GlyphIcon(glyph, fg, 18.dp)
        }
    }
}

@Composable
private fun ScreensaverChoice(onStart: () -> Unit) {
    // The choice first, then the action that tries it. Three only (the owner, 2.18): the films,
    // the wallpaper itself, and the clock. The videos and the system's own screensaver stay for
    // whoever chose them, but they are no longer offered here.
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(
            ScreensaverMode.Aerial to R.string.ss_aerial,
            ScreensaverMode.Photos to R.string.ss_photos,
            ScreensaverMode.Clock to R.string.ss_clock,
        ).forEach { (mode, label) ->
            Chip(stringResource(label), on = Wallpapers.screensaver == mode) { Wallpapers.chooseScreensaver(mode) }
        }
        Spacer(Modifier.width(6.dp))
        Pressable(onClick = onStart, focusedScale = 1.06f) { state ->
            val bg by animateColorAsState(if (state.focused) Palette.Focus else Palette.FillStrong, tween(140, easing = Motion.EaseOut), label = "ss-start")
            val fg = if (state.focused) Palette.OnFocus else Palette.Label
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(bg).height(40.dp).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GlyphIcon(Glyph.Play, fg, 14.dp)
                BasicText(stringResource(R.string.ss_start), style = Type.Body.copy(color = fg))
            }
        }
    }
}

@Composable
private fun PhoneCard(url: String?, qr: ImageBitmap?) {
    Column(Modifier.width(180.dp).padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(144.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (qr != null) {
                Image(qr, contentDescription = null, contentScale = ContentScale.Fit, filterQuality = FilterQuality.None, modifier = Modifier.fillMaxSize())
            } else {
                GlyphIcon(Glyph.Offline, Color.Black.copy(alpha = 0.35f), 40.dp)
            }
        }
        Spacer(Modifier.height(14.dp))
        BasicText(stringResource(R.string.wp_phone_title), style = Type.Headline)
        Spacer(Modifier.height(4.dp))
        BasicText(
            stringResource(if (url != null) R.string.wp_phone_body else R.string.wp_phone_offline),
            style = Type.Caption.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center),
        )
        if (url != null) {
            Spacer(Modifier.height(6.dp))
            // The whole address, token and all, to type into a computer's browser.
            BasicText(url.removePrefix("http://"), style = Type.Caption.copy(color = Palette.SecondaryLabel))
        }
    }
}


/** Aerial's languages by the names their readers know them by. */
@Composable
private fun languageName(language: AppLanguage): String = stringResource(
    when (language) {
        AppLanguage.System -> R.string.lang_system
        AppLanguage.Zh -> R.string.lang_zh
        AppLanguage.ZhTw -> R.string.lang_zhtw
        AppLanguage.En -> R.string.lang_en
        AppLanguage.Ja -> R.string.lang_ja
        AppLanguage.Ko -> R.string.lang_ko
        AppLanguage.Fr -> R.string.lang_fr
        AppLanguage.Ru -> R.string.lang_ru
        AppLanguage.Bo -> R.string.lang_bo
        AppLanguage.Ug -> R.string.lang_ug
        AppLanguage.Mn -> R.string.lang_mn
    },
)

/** A town of the owner's in the weather list: its name, and the way to take it out. */
@Composable
private fun TownRow(name: String, hint: String?, note: (String?) -> Unit, onRemove: () -> Unit) {
    Box(Modifier.padding(start = 18.dp).fillMaxWidth().height(1.dp).background(Palette.Hairline))
    Row(
        Modifier
            .fillMaxWidth()
            .height(58.dp)
            .padding(start = 18.dp, end = 10.dp)
            .onFocusChanged { if (it.hasFocus) note(hint) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(name, style = Type.Body, modifier = Modifier.weight(1f), maxLines = 1)
        Chip(stringResource(R.string.st_city_remove), on = false, onClick = onRemove)
    }
}

/**
 * A panel over the sheet for choosing one of a few: Aerial's language, a town. [rows] gets the
 * requester of the panel's first line: the sheet under the panel keeps taking the remote's keys
 * otherwise, and a picked town could never be reached. (The sheet is kept out of the search from
 * the other side — an [enter] block on the sheet's root — so the panel's own rows, down from the
 * provinces to the towns, are left whole.)
 *
 * Back is [onBack], one level up where there is one, and off the panel where there is not.
 */
@Composable
private fun PickerOverlay(
    visible: Boolean,
    title: String,
    onDismiss: () -> Unit,
    onBack: () -> Unit = onDismiss,
    rows: @Composable (FocusRequester) -> Unit,
) {
    val first = remember { FocusRequester() }
    BackHandler(enabled = visible, onBack = onBack)
    LaunchedEffect(visible) {
        if (visible) {
            withFrameNanos { }
            runCatching { first.requestFocus() }
        }
    }
    // The sheet dimmed behind, as over Home: blacking the screen out made the panel read as
    // another page rather than something laid on top (the owner, 2.13).
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200, easing = Motion.EaseOut)) + scaleIn(tween(300, easing = Motion.Drawer), initialScale = 0.94f),
        exit = fadeOut(tween(150, easing = Motion.EaseOut)) + scaleOut(tween(150, easing = Motion.EaseOut), targetScale = 0.97f),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            // No pane of its own: the frosted wallpaper under it is the backdrop (2.15), as the
            // hidden apps' sheet has Home's. A black scrim here was what the owner kept seeing.
            Modifier
                .fillMaxSize()
                .dismissOnTap(onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .width(430.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .liquidGlass(24.dp)
                    .consumeTaps()
                    .padding(22.dp),
            ) {
                BasicText(title, style = Type.Headline)
                Spacer(Modifier.height(14.dp))
                rows(first)
            }
        }
    }
}

/** The provinces in the order of their names' reading, so the row reads like a pinyin index. */
private val ProvincePinyin = mapOf(
    "安徽" to "Anhui", "北京" to "Beijing", "重庆" to "Chongqing", "福建" to "Fujian",
    "甘肃" to "Gansu", "广东" to "Guangdong", "广西" to "Guangxi", "贵州" to "Guizhou",
    "海南" to "Hainan", "河北" to "Hebei", "黑龙江" to "Heilongjiang", "河南" to "Henan",
    "香港" to "Xianggang", "湖北" to "Hubei", "湖南" to "Hunan", "江苏" to "Jiangsu",
    "江西" to "Jiangxi", "吉林" to "Jilin", "辽宁" to "Liaoning", "澳门" to "Aomen",
    "内蒙古" to "Neimenggu", "宁夏" to "Ningxia", "青海" to "Qinghai", "山东" to "Shandong",
    "上海" to "Shanghai", "山西" to "Shanxi", "陕西" to "Shaanxi", "四川" to "Sichuan",
    "台湾" to "Taiwan", "天津" to "Tianjin", "西藏" to "Xizang", "新疆" to "Xinjiang",
    "云南" to "Yunnan", "浙江" to "Zhejiang",
)

/** One letter over the towns: the panel's own pill, a size smaller, white on the one in use. */
@Composable
private fun LetterChip(label: String, on: Boolean, focusRequester: FocusRequester? = null, onClick: () -> Unit) {
    Pressable(onClick = onClick, focusRequester = focusRequester, focusedScale = 1.06f) { state ->
        val bg by animateColorAsState(
            when {
                state.focused -> Palette.Focus
                on -> Palette.FillStrong
                else -> Palette.Fill
            },
            tween(140, easing = Motion.EaseOut), label = "letter-bg",
        )
        val fg = if (state.focused) Palette.OnFocus else Palette.Label
        Box(
            Modifier
                .widthIn(min = 42.dp)
                .clip(RoundedCornerShape(10.dp))
                // The cursor: a white rim around the pill. The chosen pill and the focused one are
                // both light otherwise, and the remote's place was invisible (the owner, 1.74).
                .then(if (state.focused) Modifier.border(1.6.dp, Color.White, RoundedCornerShape(10.dp)) else Modifier)
                .background(bg)
                .height(38.dp)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(label, style = Type.Body.copy(color = fg))
        }
    }
}

/** One line of a picker: a check on the one in use, white when focused. */
@Composable
private fun PickerRow(
    label: String,
    on: Boolean,
    focusRequester: FocusRequester? = null,
    /** The family this one row is set in: the bundled cut for the scripts no TV has. */
    family: FontFamily? = null,
    onClick: () -> Unit,
) {
    Pressable(onClick = onClick, focusRequester = focusRequester, focusedScale = 1.03f) { state ->
        val bg by animateColorAsState(
            if (state.focused) Palette.Focus else Palette.Fill,
            tween(140, easing = Motion.EaseOut),
            label = "pick-bg",
        )
        val fg = if (state.focused) Palette.OnFocus else Palette.Label
        val style = family?.let { Type.Body.copy(fontFamily = it) } ?: Type.Body
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(bg)
                .height(48.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                if (on) GlyphIcon(Glyph.Check, fg, 15.dp)
            }
            BasicText(label, style = style.copy(color = fg))
        }
    }
}

/**
 * China's towns, from the phone page's own list (assets/cities-cn.tsv): the country read as the
 * owner asks for it — province first, then the letter the town's name starts with, then the town.
 * The list holds cities and their districts alike (GeoNames gives no city grouping), so a district
 * sits beside its city under the province, in the order of their names.
 */
@Composable
private fun CityPickerOverlay(visible: Boolean, onDismiss: () -> Unit, onPick: (Cities.City) -> Unit) {
    val context = LocalContext.current
    val cities by produceState(emptyList<Cities.City>(), visible) {
        if (visible && value.isEmpty()) {
            value = withContext(Dispatchers.IO) {
                runCatching { Cities.load(context) }.getOrDefault(emptyList())
            }
        }
    }
    // The names read in the language the TV is set to (the owner, 2.16): the list itself is
    // Chinese (GeoNames' Chinese names, which are how these places are found), so a French or
    // Russian TV showed 浙江, 杭州 and 余杭 in the middle of its own language. The Chinese
    // characters stay for Chinese and Japanese, which read them; everything else gets the Latin
    // name the same table carries (Hangzhou, Yuhang) and the province's pinyin.
    val nativeNames = appLocale().language == "zh" || appLocale().language == "ja"
    fun placeLabel(city: Cities.City) = if (nativeNames) city.name else city.ascii.ifBlank { city.name }
    fun provinceLabel(name: String) = if (nativeNames) name else ProvincePinyin[name] ?: name
    val added = Weather.extras.map { it.name }.toSet()
    // One step at a time: the province, then the prefecture-level city it holds, then that city's
    // district. A row does not appear until the one above it has been chosen, and the town is only
    // taken at the bottom, a district of a city of a province (the owner, 1.74).
    var province by remember { mutableStateOf<String?>(null) }
    var prefecture by remember { mutableStateOf<String?>(null) }
    val provinces = remember(cities) {
        cities.map { it.province }.filter { it.isNotEmpty() }.toSet()
            .sortedBy { ProvincePinyin[it] ?: it }
    }
    // The prefecture-level cities of the province chosen, in the order of their names' reading:
    // each one's seat stands for it (杭州 for 杭州, whose 西湖 and 余杭 are its districts).
    val prefectures = remember(cities, province) {
        if (province == null) emptyList() else cities
            .filter { it.province == province && it.prefecture.isNotEmpty() }
            .groupBy { it.prefecture }
            .map { (name, places) -> name to places.minBy { it.rank }.ascii }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.second })
            .map { it.first }
    }
    // The Latin name of each prefecture-level city, beside the Chinese one the table keeps.
    val prefectureAscii = remember(cities, province) {
        if (province == null) emptyMap() else cities
            .filter { it.province == province && it.prefecture.isNotEmpty() }
            .groupBy { it.prefecture }
            .mapValues { (_, places) -> places.minBy { it.rank }.ascii }
    }
    // The districts and counties of the city chosen: the city itself first, then its districts.
    val areas = remember(cities, province, prefecture) {
        if (prefecture == null) emptyList() else cities
            .filter { it.province == province && it.prefecture == prefecture }
            .sortedWith(compareBy({ it.rank }, { it.name }))
    }
    val provinceFirst = remember { FocusRequester() }
    val prefectureFirst = remember { FocusRequester() }
    val gridFirst = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    /** Back goes one level up while there is one, and puts the cursor on the row it left. */
    fun back() {
        when {
            prefecture != null -> {
                prefecture = null
                scope.launch {
                    repeat(3) {
                        withFrameNanos { }
                        if (runCatching { prefectureFirst.requestFocus() }.isSuccess) return@launch
                    }
                }
            }
            province != null -> {
                province = null
                scope.launch {
                    repeat(3) {
                        withFrameNanos { }
                        if (runCatching { provinceFirst.requestFocus() }.isSuccess) return@launch
                    }
                }
            }
            else -> onDismiss()
        }
    }
    PickerOverlay(visible, stringResource(R.string.st_city_pick), onDismiss, onBack = { back() }) { _ ->
        // The towns load off the main thread, so the picker's rows are empty on its first frame.
        // The remote goes to the first province, then, as a row appears under the one just chosen,
        // to that row's first; each grab waits a frame or two, or it lands where the row was and
        // the cursor wandered (the owner, 1.74).
        LaunchedEffect(visible, cities.isNotEmpty()) {
            if (visible && cities.isNotEmpty()) {
                var taken = false
                repeat(4) {
                    if (taken) return@repeat
                    withFrameNanos { }
                    if (runCatching { provinceFirst.requestFocus() }.isSuccess) taken = true
                }
            }
        }
        LaunchedEffect(prefectures.firstOrNull()) {
            if (prefectures.isEmpty()) return@LaunchedEffect
            var taken = false
            repeat(4) {
                if (taken) return@repeat
                withFrameNanos { }
                if (runCatching { prefectureFirst.requestFocus() }.isSuccess) taken = true
            }
        }
        LaunchedEffect(areas.firstOrNull()) {
            if (areas.isEmpty()) return@LaunchedEffect
            var taken = false
            repeat(4) {
                if (taken) return@repeat
                withFrameNanos { }
                if (runCatching { gridFirst.requestFocus() }.isSuccess) taken = true
            }
        }
        if (cities.isEmpty()) {
            BasicText(stringResource(R.string.wp_weather_none), style = Type.Caption.copy(color = Palette.SecondaryLabel))
        }
        // The province.
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 8.dp)) {
            items(provinces, key = { it }) { at ->
                LetterChip(provinceLabel(at), province == at, if (at == provinces.firstOrNull()) provinceFirst else null) {
                    prefecture = null
                    province = at
                }
            }
        }
        // The cities of the province, appearing once one is chosen.
        if (prefectures.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                items(prefectures, key = { it }) { at ->
                    val label = if (nativeNames) at else prefectureAscii[at] ?: at
                    LetterChip(label, prefecture == at, if (at == prefectures.firstOrNull()) prefectureFirst else null) {
                        prefecture = at
                    }
                }
            }
        } else if (province != null) {
            BasicText(
                stringResource(R.string.st_city_no_city),
                style = Type.Caption.copy(color = Palette.SecondaryLabel),
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
        // The districts of the city: the only row a town is taken from.
        if (areas.isEmpty()) {
            Box(Modifier.height(320.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                BasicText(
                    stringResource(if (prefecture == null) R.string.st_city_need_city else R.string.wp_weather_none),
                    style = Type.Caption.copy(color = Palette.SecondaryLabel),
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.height(320.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                gridItems(areas, key = { "${it.name}${it.lat}" }) { city ->
                    val taken = city.name in added
                    val lead = areas.firstOrNull()
                    Pressable(
                        onClick = { if (!taken) onPick(city) },
                        focusRequester = if (city.name == lead?.name && city.lat == lead.lat) gridFirst else null,
                        focusedScale = 1.03f,
                    ) { state ->
                        val bg by animateColorAsState(
                            if (state.focused) Palette.Focus else Palette.Fill,
                            tween(140, easing = Motion.EaseOut),
                            label = "city-bg",
                        )
                        val fg = when {
                            state.focused -> Palette.OnFocus
                            taken -> Palette.TertiaryLabel
                            else -> Palette.Label
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(bg)
                                .height(46.dp)
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BasicText(
                                placeLabel(city), style = Type.Body.copy(color = fg),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

private val SheetHeight = 360.dp
private val SheetCorner = 30.dp

/** Two settings in one row's height, a shade under a full row's 58: a narrow control still fits. */

/**
 * How much of the screen's bottom the settled sheet hides whole: its height less its rounded top
 * corners, under which Home shows. Home drew all of itself under the opaque sheet every frame,
 * three full-screen layers and the tiles that nobody sees (Xiaomi MiTV-MFTR0).
 */
/** The sheet covers its whole height: less left a strip of bare backdrop showing over its head. */
val SheetCovers = SheetHeight
