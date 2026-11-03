package app.orchard.tv.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import androidx.core.content.getSystemService
import app.orchard.tv.apps.AppCatalog
import app.orchard.tv.system.DiagLog
import app.orchard.tv.apps.FOLDER_KEY_PREFIX
import app.orchard.tv.apps.AppEntry
import app.orchard.tv.apps.AppNames
import app.orchard.tv.apps.CustomIcons
import app.orchard.tv.apps.IconLibrary
import app.orchard.tv.apps.SourceDevice
import app.orchard.tv.apps.SourceLooks
import app.orchard.tv.apps.TileArtFactory
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.system.Diagnostics
import app.orchard.tv.system.currentNetwork
import app.orchard.tv.settings.FocusStyle
import app.orchard.tv.weather.Weather
import androidx.core.graphics.createBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Inet4Address
import java.security.KeyFactory
import java.security.KeyStore
import java.security.spec.PKCS8EncodedKeySpec
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import java.security.SecureRandom

/**
 * The page a phone opens after scanning the TV's QR code: choose a wallpaper and the screensaver,
 * send photos, give an app's tile a picture of one's own, and name the weather's city. Choosing
 * pictures or typing a place with a remote is slow; with a phone it is not.
 *
 * It listens on the local network while Aerial runs (see [listen]), and every address carries a
 * random token shown only on the TV (the QR code and the address under it), so another device on
 * the network cannot use it by guessing. The token stays the same from one start to the next.
 */
class PhoneServer private constructor(private val context: Context, private val token: String, port: Int = PORT) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val prefix = "/$token"
        val uri = session.uri
        // A bookmarked address, or a tab kept for days past a re-install: the token has changed.
        // Say so instead of a bare 404 the owner cannot act on.
        if (!uri.startsWith(prefix)) return gone(token.isEmpty())
        val path = uri.removePrefix(prefix).ifEmpty { "/" }
        return runCatching {
            when {
                // The page changes with every build, and a phone keeps its tab for days: told
                // nothing, browsers kept serving an old page for weeks (icons set "not changing").
                // Always revalidate — the file is small and the TV is on the same network.
                path == "/" -> newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", page()).apply {
                    addHeader("Cache-Control", "no-cache")
                }
                path == "/api/state" -> json(state())
                path == "/api/diagnostics" -> text(Response.Status.OK, Diagnostics.report(context))
                path.startsWith("/thumb/") -> thumb(path.removePrefix("/thumb/"))
                session.method == Method.POST && path == "/api/wallpaper" -> {
                    val id = form(session)["id"].orEmpty()
                    onMain { Wallpapers.select(id) }
                    json(state())
                }
                session.method == Method.POST && path == "/api/screensaver" -> {
                    val mode = ScreensaverMode.of(form(session)["mode"])
                    onMain { Wallpapers.chooseScreensaver(mode) }
                    json(state())
                }
                session.method == Method.POST && path == "/api/setting" -> {
                    // The few on/off settings the phone page shows, as Personalize does.
                    val f = form(session)
                    val on = f["on"] == "1"
                    val known = when (f["key"]) {
                        "wallpaperAlwaysMoves" -> { onMain { AerialSettings.chooseWallpaperAlwaysMoves(on) }; true }
                        "showWeather" -> { onMain { AerialSettings.chooseShowWeather(on) }; true }
                        "screensaverClock" -> { onMain { AerialSettings.chooseScreensaverClock(on) }; true }
                        "showAqi" -> { onMain { AerialSettings.chooseShowAqi(on) }; true }
                        "showHumidity" -> { onMain { AerialSettings.chooseShowHumidity(on) }; true }
                        else -> false
                    }
                    json(state().put("saved", known))
                }
                session.method == Method.POST && path == "/api/focus" -> {
                    val style = FocusStyle.of(form(session)["style"])
                    onMain { AerialSettings.chooseFocusStyle(style) }
                    json(state())
                }
                session.method == Method.POST && path == "/api/delete" -> {
                    val id = form(session)["id"].orEmpty()
                    onMain { Wallpapers.deletePhoto(id) }
                    json(state())
                }
                session.method == Method.POST && path == "/api/upload" -> upload(session)
                session.method == Method.POST && path == "/api/videos/upload" -> video(session)
                session.method == Method.POST && path == "/api/videos/delete" -> {
                    val removed = ScreensaverVideos.delete(context, form(session)["id"].orEmpty())
                    if (removed) onMain { Wallpapers.videosChanged() }
                    json(state().put("removed", removed))
                }
                path.startsWith("/tile/") -> tile(java.net.URLDecoder.decode(path.removePrefix("/tile/"), "UTF-8"), own = session.parameters["art"]?.firstOrNull() == "own")
                path.startsWith("/library/") -> libraryPicture(java.net.URLDecoder.decode(path.removePrefix("/library/"), "UTF-8"))
                session.method == Method.POST && path == "/api/icon" -> icon(session)
                session.method == Method.POST && path == "/api/source/name" -> {
                    val f = form(session)
                    val source = AppCatalog.pinnedSources.firstOrNull { it.key == f["app"] }?.source
                    // On the main thread before answering, so the state sent back has the new name.
                    if (source != null) runBlocking(Dispatchers.Main) { SourceLooks.rename(source.id, f["name"]) }
                    json(state())
                }
                session.method == Method.POST && path == "/api/app/name" -> {
                    val f = form(session)
                    val key = f["app"].orEmpty()
                    val source = AppCatalog.pinnedSources.firstOrNull { it.key == key }?.source
                    // On the main thread before answering, so the state sent back has the new name.
                    val saved = when {
                        source != null -> { runBlocking(Dispatchers.Main) { SourceLooks.rename(source.id, f["name"]) }; true }
                        AppCatalog.loaded.any { it.key == key } -> { runBlocking(Dispatchers.Main) { AppNames.rename(key, f["name"]) }; true }
                        else -> false
                    }
                    json(state().put("saved", saved))
                }
                session.method == Method.POST && path == "/api/layout" -> layout(form(session))
                session.method == Method.POST && path == "/api/source/device" -> {
                    val f = form(session)
                    val source = AppCatalog.pinnedSources.firstOrNull { it.key == f["app"] }?.source
                    // As from the tile's menu: a device names the tile after it, "" goes back to the plain input.
                    if (source != null) runBlocking(Dispatchers.Main) { SourceLooks.chooseDevice(source.id, SourceDevice.of(f["device"])) }
                    json(state().put("saved", source != null))
                }
                session.method == Method.POST && path == "/api/source/pin" -> {
                    val f = form(session)
                    val id = f["id"].orEmpty()
                    val pin = AppCatalog.pinSource
                    val known = AppCatalog.sources.any { it.id == id }
                    if (known && pin != null) runBlocking(Dispatchers.Main) { pin(id, f["pinned"] == "1") }
                    json(state().put("saved", known && pin != null))
                }
                session.method == Method.POST && path == "/api/folder/name" -> {
                    val f = form(session)
                    val id = f["id"].orEmpty()
                    val name = f["name"].orEmpty().trim().take(FOLDER_NAME_MAX)
                    val rename = AppCatalog.renameFolder
                    val known = AppCatalog.folders.any { it.folder?.id == id }
                    // On the main thread before answering, so the state sent back has the new name.
                    if (known && name.isNotEmpty() && rename != null) runBlocking(Dispatchers.Main) { rename(id, name) }
                    json(state().put("saved", known && name.isNotEmpty() && rename != null))
                }
                session.method == Method.POST && path == "/api/icon/pick" -> {
                    // The library's picture or the app's own art for one app; "custom" puts a sent
                    // picture back in front. A pick only reorders what stands there — a sent
                    // picture is never deleted by it (it was once, and the owner lost pictures
                    // he had sent by tapping through the choices).
                    val f = form(session)
                    val pkg = f["app"].orEmpty()
                    val asked = f["pick"].orEmpty()
                    val pick = when (asked) {
                        "library" -> IconLibrary.Pick.Library
                        "own" -> IconLibrary.Pick.Own
                        else -> null
                    }
                    val ok = installed(pkg) && asked in setOf("library", "own", "custom") &&
                        (asked != "library" || IconLibrary.has(pkg))
                    // A pick that changes nothing (custom when the sent picture already stands in
                    // front) is not a change: recording it would overwrite the state worth
                    // returning to with a copy of the present one.
                    val changes = ok && when (asked) {
                        "custom" -> IconLibrary.pick(pkg) != null
                        "library" -> IconLibrary.pick(pkg) != IconLibrary.Pick.Library
                        else -> IconLibrary.pick(pkg) != IconLibrary.Pick.Own
                    }
                    if (changes) {
                        CustomIcons.snapshot(pkg)
                        IconLibrary.choose(pkg, pick)
                        runBlocking(Dispatchers.Main) { CustomIcons.changed(); IconLibrary.changed() }
                    }
                    json(state().put("saved", ok))
                }
                session.method == Method.POST && path == "/api/icon/reset" -> {
                    val pkg = form(session)["app"].orEmpty()
                    if (installed(pkg)) {
                        CustomIcons.snapshot(pkg)
                        CustomIcons.clear(pkg)
                        IconLibrary.choose(pkg, null)
                        onMain { CustomIcons.changed(); IconLibrary.changed() }
                    }
                    json(state())
                }
                session.method == Method.POST && path == "/api/icon/undo" -> {
                    // The replaced picture comes back, the current one steps aside for it.
                    val pkg = form(session)["app"].orEmpty()
                    val ok = installed(pkg) && CustomIcons.undo(pkg)
                    if (ok) onMain { CustomIcons.changed(); IconLibrary.changed() }
                    json(state().put("saved", ok))
                }
                session.method == Method.POST && path == "/api/weather/city" -> {
                    val name = form(session)["name"].orEmpty()
                    val found = name.isNotBlank() && runBlocking(Dispatchers.Main) { Weather.choose(name) }
                    json(state().put("found", found))
                }
                // The phone page's city search: typed on the phone, answered in its language.
                session.method == Method.GET && path == "/api/weather/search" -> {
                    val f = form(session)
                    json(Weather.search(f["q"].orEmpty(), f["lang"].orEmpty().ifBlank { "en" }))
                }
                // A city chosen out of those answers: the point and names stand as given.
                session.method == Method.POST && path == "/api/weather/pick" -> {
                    val f = form(session)
                    val lat = f["lat"]?.toDoubleOrNull()
                    val lon = f["lon"]?.toDoubleOrNull()
                    val name = f["name"].orEmpty().trim()
                    if (lat != null && lon != null && name.isNotBlank() && lat in -90.0..90.0 && lon in -180.0..180.0) {
                        runBlocking(Dispatchers.Main) { Weather.pick(lat, lon, name, f["ascii"].orEmpty()) }
                    }
                    json(state())
                }
                session.method == Method.POST && path == "/api/weather/auto" -> {
                    runBlocking { runCatching { Weather.relocate() } }
                    json(state())
                }
                // A town put into the rotation, and one taken out of it: the same answers a search
                // gives, three of them at most beside the located town (the owner, 11-03).
                session.method == Method.POST && path == "/api/weather/add" -> {
                    val f = form(session)
                    val lat = f["lat"]?.toDoubleOrNull()
                    val lon = f["lon"]?.toDoubleOrNull()
                    val name = f["name"].orEmpty().trim()
                    val added = lat != null && lon != null && name.isNotBlank() &&
                        lat in -90.0..90.0 && lon in -180.0..180.0 &&
                        runBlocking(Dispatchers.Main) { Weather.addSlot(lat, lon, name, f["ascii"].orEmpty()) }
                    json(state().put("added", added))
                }
                session.method == Method.POST && path == "/api/weather/remove" -> {
                    val name = form(session)["name"].orEmpty().trim()
                    if (name.isNotBlank()) runBlocking(Dispatchers.Main) { Weather.removeCity(name) }
                    json(state())
                }
                // The phone's own fix: a TV has no location of its own, and behind a proxy the
                // address is the proxy's town. Named here, off the TV's main thread.
                session.method == Method.POST && path == "/api/weather/locate" -> {
                    val f = form(session)
                    val lat = f["lat"]?.toDoubleOrNull()
                    val lon = f["lon"]?.toDoubleOrNull()
                    if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) {
                        val named = runCatching { Weather.nameAt(lat, lon) }.getOrNull()
                        runBlocking(Dispatchers.Main) { Weather.locate(lat, lon, named) }
                    }
                    json(state())
                }
                else -> text(Response.Status.NOT_FOUND, "Not found")
            }
        }.getOrElse {
            Log.w(TAG, "request failed", it)
            text(Response.Status.INTERNAL_ERROR, "error")
        }
    }

    private fun page(): String = context.assets.open("web/index.html").bufferedReader().use { it.readText() }
        .replace("{{DEVICE}}", deviceName())

    /**
     * The TV as its owner knows it, for the page's title and header (the owner, 2.19): the model
     * with its underscores and hyphens read as spaces, and the maker in front where the model does
     * not already name it — "Xiaomi MiTV MFTP0", "Sony BRAVIA 4K GB". Nothing of Aerial's own, so
     * the page says which TV is being set up and nothing else.
     */
    private fun deviceName(): String {
        fun clean(raw: String) =
            raw.replace('_', ' ').replace('-', ' ').replace(Regex("\\s+"), " ").trim()
        // Names already mixed-case or all caps keep as they are: MiTV, BRAVIA, MFTP0, 4K.
        fun title(word: String) =
            if (word.length < 3 || word == word.uppercase()) word else word.replaceFirstChar { it.uppercase() }
        val model = clean(Build.MODEL).ifBlank { "Android TV" }
        val maker = clean(Build.MANUFACTURER).takeUnless { it.isBlank() || it.equals("unknown", true) }
        val name = if (maker == null || model.contains(maker, ignoreCase = true)) model else "$maker $model"
        return name.split(' ').joinToString(" ") { title(it) }
    }

    private fun state(): JSONObject {
        val list = JSONArray()
        val selected = Wallpapers.selectedId
        // The built-in pictures are the ones the TV's own row offers, in its order: the phone
        // showed every preset the TV keeps, a longer row than Personalize has (the owner, 2.17).
        val offered = Wallpapers.offeredBuiltins.map { it.id }.toSet()
        Wallpapers.items.forEach { w ->
            if (w.kind == WallpaperKind.Builtin && w.id !in offered) return@forEach
            list.put(JSONObject().put("id", w.id).put("kind", w.kind.name.lowercase()).put("title", w.title).put("caption", w.caption ?: ""))
        }
        return JSONObject()
            .put("selected", selected)
            // The page follows the language the TV speaks (the owner): the app's own choice, or,
            // when that follows the TV, the TV system's language. An empty tag means System.
            .put("lang", AerialSettings.language.tag.ifEmpty { java.util.Locale.getDefault().toLanguageTag() })
            .put("latestDaily", Wallpapers.LATEST_DAILY)
            .put("rotatePhotos", Wallpapers.ROTATE_PHOTOS)
            .put("screensaver", Wallpapers.screensaver.key)
            .put("screensaverFollows", Wallpapers.screensaverFollows)
            .put("videos", JSONArray().apply {
                ScreensaverVideos.all(context).forEach { put(JSONObject().put("id", it.id).put("name", it.name)) }
            })
            .put("focusStyle", AerialSettings.focusStyle.key)
            .put("wallpaperAlwaysMoves", AerialSettings.wallpaperAlwaysMoves)
            .put("showWeather", AerialSettings.showWeather)
            .put("showAqi", AerialSettings.showAqi)
            .put("showHumidity", AerialSettings.showHumidity)
            .put("screensaverClock", AerialSettings.screensaverClock)
            .put("wallpapers", list)
            .put("apps", JSONArray().apply {
                // Pinned inputs first, then apps by the name Home shows. [default] is the name a
                // blank one goes back to; [name] is the one typed on this page, if any.
                val apps = AppCatalog.loaded.map { it to (AppNames.of(it.key) ?: it.label) }.sortedBy { it.second }
                AppCatalog.pinnedSources.forEach { a ->
                    val src = a.source ?: return@forEach
                    // An input's label as Home will show it once it has arranged the new look.
                    val look = SourceLooks.of(src.id)
                    val label = look?.let { it.name ?: it.device?.let { d -> context.getString(d.label) } } ?: src.label
                    // The stamp makes the phone fetch the tile again once its picture changes.
                    put(JSONObject().put("key", a.key).put("label", label).put("default", src.label).put("custom", CustomIcons.has(a.key)).put("prev", CustomIcons.hasPrev(a.key)).put("stamp", CustomIcons.stamp(a.key))
                        .put("source", true).put("port", src.label).put("name", look?.name ?: "").put("device", look?.device?.key ?: "").put("look", look.hashCode()))
                }
                apps.forEach { (a, label) ->
                    // [icon] is where the tile's picture comes from now; [library] whether the icon
                    // library has one for this app, which the phone can pick instead.
                    val icon = when {
                        IconLibrary.pick(a.key) == IconLibrary.Pick.Own -> "own"
                        IconLibrary.pick(a.key) == IconLibrary.Pick.Library && IconLibrary.has(a.key) -> "library"
                        CustomIcons.has(a.key) -> "custom"
                        IconLibrary.has(a.key) -> "library"
                        else -> "own"
                    }
                    put(JSONObject().put("key", a.key).put("label", label).put("default", a.label).put("name", AppNames.of(a.key) ?: "")
                        .put("custom", CustomIcons.has(a.key)).put("prev", CustomIcons.hasPrev(a.key)).put("library", IconLibrary.has(a.key)).put("icon", icon)
                        .put("stamp", CustomIcons.stamp(a.key) + IconLibrary.stamp(a.key) + icon.hashCode()))
                }
            })
            .put("layout", layoutState())
            .put("devices", JSONArray().apply { SourceDevice.entries.forEach { put(JSONObject().put("key", it.key).put("label", context.getString(it.label))) } })
            .put("inputs", JSONArray().apply {
                // Every input Home knows of, for pinning to Home from the phone.
                AppCatalog.sources.forEach { src ->
                    val look = SourceLooks.of(src.id)
                    put(JSONObject().put("id", src.id).put("port", src.label).put("device", src.deviceName ?: "")
                        .put("name", look?.let { it.name ?: it.device?.let { d -> context.getString(d.label) } } ?: "")
                        .put("pinned", src.id in AerialSettings.pinnedSources))
                }
            })
            .put("folders", JSONArray().apply {
                AppCatalog.folders.forEach { e ->
                    val f = e.folder ?: return@forEach
                    put(JSONObject().put("id", f.id).put("key", e.key).put("name", f.name).put("count", f.apps.size).put("stamp", e.art.hashCode()))
                }
            })
            .put("weather", JSONObject().apply {
                put("place", Weather.place ?: "")
                // The same place in Latin letters, so the page reads Hangzhou rather than 杭州
                // when it is set to a language that does not read Chinese (2.17).
                put("placeAscii", Weather.placeAscii ?: "")
                put("chosen", Weather.chosen)
                // The towns Home turns through beside it, which the phone adds and takes away
                // (the owner, 11-03).
                put("maxCities", Weather.MAX_EXTRA_CITIES)
                put("cities", JSONArray().apply {
                    Weather.extras.forEach { town ->
                        put(JSONObject().put("name", town.name).put("ascii", town.ascii)
                            .put("lat", town.lat).put("lon", town.lon).apply {
                                // Each town's own reading, so the phone shows them side by side as
                                // the TV turns through them.
                                town.now?.let { put("celsius", it.celsius); put("kind", it.kind.name.lowercase()) }
                            })
                    }
                })
                Weather.now?.let { put("celsius", it.celsius); put("kind", it.kind.name.lowercase()); put("day", it.day) }
            })
    }

    /** Home in order, each folder's apps and the hidden apps, by the names Home shows. */
    private fun layoutState(): JSONObject {
        fun tile(e: AppEntry) = JSONObject().put("key", e.key).put("label", e.label)
            .put("kind", when { e.folder != null -> "folder"; e.source != null -> "source"; else -> "app" })
            // The hash of the picture as it is drawn now: any re-render — a pick on the phone, a
            // picture sent, an update — changes it, so the phone fetches the tile anew instead of
            // showing its cached one (the stamps of the files alone kept a changed tile hidden).
            .put("stamp", e.art.hashCode().toLong())
            .apply { e.folder?.let { put("id", it.id) }; e.source?.let { put("port", it.label) } }
        val folders = JSONObject()
        AppCatalog.folderApps.forEach { (id, apps) -> folders.put(id, JSONArray().apply { apps.forEach { put(tile(it)) } }) }
        // With the parental PIN on, hidden apps stay hidden from here: on the TV, seeing them takes the PIN.
        val locked = AerialSettings.locked
        return JSONObject()
            .put("home", JSONArray().apply { AppCatalog.home.forEach { put(tile(it)) } })
            .put("folders", folders)
            .put("hidden", JSONArray().apply { if (!locked) AppCatalog.hiddenApps.forEach { put(tile(it)) } })
            .put("hiddenCount", AppCatalog.hiddenApps.size)
            .put("columns", AerialSettings.homeColumns)
            .put("locked", locked)
            .put("ready", AppCatalog.layout != null)
    }

    /**
     * One step of arranging Home from the phone: [op] is move, movein, hide, unhide, folder (into
     * [folder], or a new one when it is empty), unfolder or ungroup. Done on the main thread before
     * answering, so the state sent back shows it.
     */
    private fun layout(f: Map<String, String>): Response {
        val ops = AppCatalog.layout ?: return json(state().put("saved", false))
        val key = f["key"].orEmpty()
        val target = f["target"].orEmpty()
        val folder = f["folder"].orEmpty()
        val known = (AppCatalog.home + AppCatalog.folderApps.values.flatten() + AppCatalog.hiddenApps).map { it.key }.toSet()
        val step: (() -> Unit)? = when (f["op"]) {
            "move" -> if (key in known && target in known) ({ ops.move(key, target) }) else null
            "movein" -> if (key in known && target in known) ({ ops.moveInFolder(key, target) }) else null
            "hide" -> if (key in known) ({ ops.hide(key) }) else null
            "unhide" -> if (key in known && !AerialSettings.locked) ({ ops.unhide(key) }) else null
            "folder" -> if (key in known && !key.startsWith(FOLDER_KEY_PREFIX)) ({ ops.toFolder(key, folder.ifEmpty { null }) }) else null
            "unfolder" -> if (key in known) ({ ops.outOfFolder(key) }) else null
            "ungroup" -> if (folder in AppCatalog.folderApps) ({ ops.ungroup(folder) }) else null
            else -> null
        }
        if (step != null) runBlocking(Dispatchers.Main) { step() }
        return json(state().put("saved", step != null))
    }

    /** An app on the TV or a pinned input: the only tiles a picture may be sent for. */
    /** An app, a firmware shortcut such as Xiaomi's children's mode (issue #530), or a pinned input. */
    private fun installed(pkg: String) =
        pkg.isNotEmpty() && (AppCatalog.loaded.any { it.key == pkg } || AppCatalog.pinnedSources.any { it.key == pkg })

    /** The tile as Home shows it; with [own], as the app's own art would draw it. */
    private fun tile(pkg: String, own: Boolean = false): Response {
        val app = (AppCatalog.pinnedSources + AppCatalog.loaded + AppCatalog.folders).firstOrNull { it.key == pkg } ?: return text(Response.Status.NOT_FOUND, "")
        val art = if (own && app.source == null && app.folder == null) AppCatalog.ownArt(context, app) ?: app.art else app.art
        val bmp = TileArtFactory.flatten(art)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bmp.recycle()
        val bytes = out.toByteArray()
        return newFixedLengthResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(bytes), bytes.size.toLong()).apply {
            addHeader("Cache-Control", "max-age=600")
        }
    }

    /** The icon library's picture of an app, as downloaded. */
    private fun libraryPicture(pkg: String): Response {
        val f = IconLibrary.file(pkg) ?: return text(Response.Status.NOT_FOUND, "")
        val type = if (f.name.endsWith(".png")) "image/png" else "image/jpeg"
        return newFixedLengthResponse(Response.Status.OK, type, f.inputStream(), f.length()).apply {
            addHeader("Cache-Control", "max-age=600")
        }
    }

    private fun icon(session: IHTTPSession): Response {
        if (tooLarge(session)) return text(Response.Status.BAD_REQUEST, "too large")
        val files = HashMap<String, String>()
        session.parseBody(files)
        val pkg = session.parameters["app"]?.firstOrNull().orEmpty()
        if (!installed(pkg)) return text(Response.Status.BAD_REQUEST, "unknown app")
        val f = files.values.map(::File).firstOrNull { it.exists() && it.length() > 0 }
            ?: return text(Response.Status.BAD_REQUEST, "no picture")
        val saved = f.inputStream().use { CustomIcons.save(pkg, it, context.cacheDir) }
        if (saved) onMain { CustomIcons.changed() }
        return json(state().put("saved", saved))
    }

    private fun thumb(id: String): Response {
        val bmp = Wallpapers.thumbnail(java.net.URLDecoder.decode(id, "UTF-8")) ?: return text(Response.Status.NOT_FOUND, "")
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
        val bytes = out.toByteArray()
        return newFixedLengthResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(bytes), bytes.size.toLong()).apply {
            addHeader("Cache-Control", "max-age=600")
        }
    }

    private fun upload(session: IHTTPSession): Response {
        if (tooLarge(session)) return text(Response.Status.BAD_REQUEST, "too large")
        val files = HashMap<String, String>()
        session.parseBody(files)
        var added = 0
        // NanoHTTPD stores each part in a temp file; several photos arrive as photo, photo1, photo2...
        files.values.forEach { path ->
            val f = File(path)
            if (f.exists() && f.length() > 0) {
                val id = f.inputStream().use { input -> runBlocking(Dispatchers.Main) { Wallpapers.addPhoto(input) } }
                if (id != null) added++
            }
        }
        return json(state().put("added", added))
    }

    /** One film per request: a batch never needs twice its total size free on the TV. */
    private fun video(session: IHTTPSession): Response {
        val length = session.headers["content-length"]?.toLongOrNull() ?: 0L
        // Include room for multipart headers, but do not accept an unbounded chunked body.
        if (length <= 0 || length > ScreensaverVideos.MAX_FILE_BYTES + 1024 * 1024) return text(Response.Status.BAD_REQUEST, "too large")
        val files = HashMap<String, String>()
        session.parseBody(files)
        val path = files["video"] ?: return text(Response.Status.BAD_REQUEST, "no video")
        val name = session.parameters["video"]?.firstOrNull().orEmpty()
        val saved = ScreensaverVideos.add(context, File(path), name) != null
        if (saved) onMain { Wallpapers.videosChanged() }
        return json(state().put("saved", saved))
    }

    // NanoHTTPD writes the whole body to the cache before we see it; keep a stray or hostile
    // upload from filling the TV's storage (#57).
    private fun tooLarge(session: IHTTPSession): Boolean =
        (session.headers["content-length"]?.toLongOrNull() ?: 0L) > MAX_UPLOAD

    private fun form(session: IHTTPSession): Map<String, String> {
        session.parseBody(HashMap())
        return session.parameters.mapValues { it.value.firstOrNull().orEmpty() }
    }

    private fun onMain(block: () -> Unit) = runBlocking(Dispatchers.Main) { block() }

    // The page polls; told nothing, a phone browser heuristically cached even these answers and
    // showed a state that was minutes old (a tile changed on the TV stayed old on the phone).
    private fun json(o: JSONObject) = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", o.toString()).apply {
        addHeader("Cache-Control", "no-cache")
    }

    private fun text(status: Response.Status, msg: String) = newFixedLengthResponse(status, "text/plain; charset=utf-8", msg)

    /** Shown on an address whose token is no longer this TV's, in every language the app speaks. */
    private fun gone(empty: Boolean): Response {
        val body = """
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>HLine TV</title>
            <div style="font:16px/1.7 -apple-system,system-ui,'PingFang SC','Noto Sans CJK SC',sans-serif;
                        max-width:34em;margin:12vh auto;padding:0 22px;color:#111">
            <p>这个地址已经失效：请在电视上重新扫描二维码（长按 App → 自定义图标/名字，或个性化面板右侧）。</p>
            <p>此地址已失效，請在電視上重新掃描 QR 碼。</p>
            <p>このアドレスは使えなくなりました。テレビで QR コードを読み取り直してください。</p>
            <p>이 주소는 더 이상 쓸 수 없습니다. TV에서 QR코드를 다시 스캔하세요.</p>
            <p>This address is no longer in use. Scan the QR code on the TV again.</p>
            <p>Этот адрес больше не действует. Отсканируйте QR-код на телевизоре заново.</p>
            <p>Ця адреса більше не діє. Відскануйте QR-код на телевізорі знову.</p>
            <p>Бұл мекенжай енді жарамсыз. Теледидардағы QR кодын қайта сканнерлеңіз.</p>
            </div>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/html; charset=utf-8", body)
    }

    companion object {
        const val PORT = 8790
        /** Tried in order: the page works on whichever is free, and its QR code says which. */
        private val PORTS = intArrayOf(PORT, 8791, 8792, 8793, 8794)
        /**
         * The same page over https, on ports of its own: a browser hands out the phone's own
         * location only on a secure page, so the address in the QR code is this one (the owner,
         * 11-03). The certificate is the app's own, made once and carried in the assets.
         */
        const val SECURE_PORT = 8795
        private val SECURE_PORTS = intArrayOf(SECURE_PORT, 8796, 8797, 8798, 8799)
        private const val CERT_FILE = "phone-cert.der"
        private const val KEY_FILE = "phone-key.der"
        private const val KEY_PASS = "hline-phone"
        private const val TAG = "AerialPhone"
        /** Enough for a batch of phone photos at full size. */
        private const val MAX_UPLOAD = 200L * 1024 * 1024
        /** As for an input's name: Home's caption has room for about this much. */
        private const val FOLDER_NAME_MAX = 24

        @Volatile
        private var running: PhoneServer? = null

        @Volatile
        private var secureRunning: PhoneServer? = null

        /**
         * Listens for as long as Aerial runs, from Home's start (MainActivity.startWork), so a
         * computer can open the page at any time without Personalize open on the TV. On every
         * interface: the address the TV has may change, the page stays the same.
         */
        @Synchronized
        fun listen(context: Context): PhoneServer? {
            running?.let { if (it.isAlive) { listenSecure(context); return it } }
            // A force-stop that brings a new process up while the old socket is still being torn
            // down leaves the port in use for a few seconds: try again before giving up (2.10).
            // Something else may hold the port for good (another app on the TV, or a firmware
            // server): the next one in line is taken instead, and the QR code carries its number.
            var cause: Throwable? = null
            repeat(4) { attempt ->
                for (port in PORTS) {
                    val server = runCatching {
                        PhoneServer(context.applicationContext, token(context), port).also {
                            it.start(SOCKET_READ_TIMEOUT, true)
                            running = it
                        }
                    }.onFailure { cause = it }.getOrNull()
                    if (server != null) {
                        if (port != PORT) Log.i(TAG, "listening on $port: $PORT was taken")
                        listenSecure(context)
                        return server
                    }
                }
                Thread.sleep(1_500L)
            }
            cause?.let { Log.w(TAG, "could not listen on any of ${PORTS.joinToString()}", it) }
            return null
        }

        /** The page over https, beside the plain one: a failure here leaves the plain address. */
        @Synchronized
        private fun listenSecure(context: Context) {
            secureRunning?.let { if (it.isAlive) return }
            // The certificate and the key ride along as DER: a PKCS12 made by a desktop keytool
            // carries key protection Android's own reader does not answer (PBKDF2, the owner's TV).
            val factory = runCatching {
                val cert = CertificateFactory.getInstance("X.509")
                    .generateCertificate(context.assets.open(CERT_FILE)) as X509Certificate
                val key = KeyFactory.getInstance("RSA").generatePrivate(
                    PKCS8EncodedKeySpec(context.assets.open(KEY_FILE).use { it.readBytes() })
                )
                val store = KeyStore.getInstance("PKCS12").apply {
                    load(null, null)
                    setKeyEntry("phone", key, KEY_PASS.toCharArray(), arrayOf(cert))
                }
                val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                keys.init(store, KEY_PASS.toCharArray())
                SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }.serverSocketFactory
            }.onFailure { Log.w(TAG, "no certificate for the secure page", it) }.getOrNull() ?: return
            for (port in SECURE_PORTS) {
                val server = runCatching {
                    PhoneServer(context.applicationContext, token(context), port).also {
                        it.makeSecure(factory, null)
                        it.start(SOCKET_READ_TIMEOUT, true)
                        secureRunning = it
                    }
                }.getOrNull()
                if (server != null) {
                    if (port != SECURE_PORT) Log.i(TAG, "secure page on $port: $SECURE_PORT was taken")
                    return
                }
            }
            Log.w(TAG, "could not listen on any of ${SECURE_PORTS.joinToString()}")
        }

        /** The address to put in the QR code, listening first if need be, or null offline. */
        fun start(context: Context): String? {
            val ip = lanAddress(context) ?: return null
            val server = listen(context) ?: return null
            // https where it could be had: only a secure page may read the phone's own location.
            secureRunning?.takeIf { it.isAlive }?.let { that ->
                val port = runCatching { that.listeningPort }.getOrDefault(SECURE_PORT)
                return "https://$ip:$port/${that.token}/".also { DiagLog.i("phone page: $it") }
            }
            val port = runCatching { server.listeningPort }.getOrDefault(PORT)
            return "http://$ip:$port/${server.token}/".also { DiagLog.i("phone page: $it") }
        }

        /**
         * Kept from one start to the next, so the address can be bookmarked on a computer and the
         * phone's tab still works the next day.
         */
        private fun token(context: Context): String {
            val prefs = context.getSharedPreferences("phone_page", Context.MODE_PRIVATE)
            prefs.getString("token", null)?.takeIf { it.length == 8 }?.let { return it }
            return newToken().also { prefs.edit().putString("token", it).apply() }
        }

        private fun newToken(): String {
            val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
            val rnd = SecureRandom()
            return (1..8).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
        }

        /** The network's IPv4 address, the kind a phone on the same network can reach. */
        private fun ipv4Of(cm: ConnectivityManager, n: android.net.Network): String? =
            runCatching { cm.getLinkProperties(n) }.getOrNull()?.linkAddresses?.map { it.address }
                ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress && !isBenchmark(it) }
                ?.hostAddress

        private fun isWifi(cm: ConnectivityManager, n: android.net.Network): Boolean =
            runCatching { cm.getNetworkCapabilities(n) }?.getOrNull()
                ?.let { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) } ?: false

        private fun isEthernet(cm: ConnectivityManager, n: android.net.Network): Boolean =
            runCatching { cm.getNetworkCapabilities(n) }?.getOrNull()
                ?.let { it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) && !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) } ?: false

        fun lanAddress(context: Context): String? {
            val cm = context.getSystemService<ConnectivityManager>() ?: return null
            val current = cm.currentNetwork()
            // Under a proxy app's VPN the default network is the tunnel, whose address the phone
            // cannot reach (Clash and the like use 198.18.0.1; Xiaomi S55, issue #667). The Wi-Fi
            // or Ethernet network under it is still in allNetworks: take its address first.
            val networks = runCatching { cm.allNetworks.toList() }.getOrDefault(emptyList())
            // Wi-Fi before Ethernet: a TV with a cable and Wi-Fi both up is reached from the phone
            // over the Wi-Fi one, which is the network the phone is on; boxes wired to a router the
            // phone also reaches by Wi-Fi are the same case. The default network comes last: on
            // VIDAA with a VPN it is the tunnel (docs/devices/hisense-vidaa.md).
            val order = networks.filter { isWifi(cm, it) } + networks.filter { isEthernet(cm, it) } + listOfNotNull(current)
            var addr = order.firstNotNullOfOrNull { ipv4Of(cm, it) }
            // Some firmware (Konka, Skyworth, operator boxes) keeps the address out of
            // ConnectivityManager's reach: the Wi-Fi service still knows it.
            if (addr == null) {
                val ip = runCatching {
                    context.applicationContext.getSystemService<WifiManager>()?.connectionInfo?.ipAddress
                }.getOrNull()
                if (ip != null && ip != 0) {
                    val bytes = byteArrayOf(
                        (ip and 0xff).toByte(), (ip shr 8 and 0xff).toByte(),
                        (ip shr 16 and 0xff).toByte(), (ip shr 24 and 0xff).toByte(),
                    )
                    addr = runCatching { java.net.InetAddress.getByAddress(bytes).hostAddress }.getOrNull()
                    if (addr != null) Log.i(TAG, "address from the Wi-Fi service: $addr")
                }
            }
            // When a TV reports no address the owner reads "offline" — say what was there, for the
            // diagnostics that come back with a report (nothing to retry against otherwise).
            if (addr == null) Log.w(TAG, "no LAN address: ${networks.size} network(s): " +
                networks.joinToString { n ->
                    val caps = runCatching { cm.getNetworkCapabilities(n) }.getOrNull()
                    (when {
                        caps == null -> "?"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "eth"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
                        else -> "other"
                    }) + (if (n == current) "*" else "")
                })
            return addr
        }

        /** 198.18.0.0/15, the range proxy apps put their tunnel in; never a home network. */
        private fun isBenchmark(address: java.net.InetAddress): Boolean {
            val b = address.address
            return b.size == 4 && (b[0].toInt() and 0xff) == 198 && (b[1].toInt() and 0xfe) == 18
        }

        /** A QR code, dark modules on white, each module [scale] pixels square. */
        fun qr(text: String, scale: Int = 8): Bitmap {
            val matrix = QRCodeWriter().encode(
                text, BarcodeFormat.QR_CODE, 0, 0,
                mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
            )
            val n = matrix.width
            val bmp = createBitmap(n * scale, n * scale)
            for (y in 0 until n) for (x in 0 until n) {
                if (matrix[x, y]) for (dy in 0 until scale) for (dx in 0 until scale) bmp.setPixel(x * scale + dx, y * scale + dy, Color.BLACK)
                else for (dy in 0 until scale) for (dx in 0 until scale) bmp.setPixel(x * scale + dx, y * scale + dy, Color.WHITE)
            }
            return bmp
        }
    }
}
