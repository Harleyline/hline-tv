package app.orchard.tv.weather

import app.orchard.tv.BuildConfig
import app.orchard.tv.system.DiagLog

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.ssl.SSLException
import kotlin.math.roundToInt

enum class WeatherKind { Clear, PartlyCloudy, Cloudy, Fog, Rain, Snow, Thunder }

/**
 * [high] and [low] are today's, shown under the large clock; null in a reading saved by an older
 * version. [celsius], [humidity] and [usAqi] are the station's three figures, read from 在意空气
 * in one answer (1.14) so they are true of the same moment; any one of them that the station does
 * not carry falls back to Open-Meteo's own, and the weather itself stands whatever happens to
 * them. [usAqi] keeps its old name — the field is written to prefs — but its scale is now the one
 * 在意空气 publishes, which for a Chinese station is the national index, lower than the EPA's for
 * the same air; only the fallback is on the EPA's scale. Nor is it an hourly model any more: it is
 * the nearest station's own reading, which usually moves once an hour.
 */
data class WeatherNow(
    val celsius: Int, val kind: WeatherKind, val day: Boolean,
    val high: Int? = null, val low: Int? = null, val humidity: Int? = null,
    val usAqi: Int? = null,
)

/**
 * One town in the rotation: the place the weather is located for, or one the owner added in
 * Personalize. [ascii] is the Latin name GeoNames gives it, for the languages that read the
 * place in Latin letters; [now] is this town's reading (null until one arrives).
 */
data class CitySlot(
    val name: String, val ascii: String, val lat: Float, val lon: Float, val now: WeatherNow? = null,
    /** True for the town the weather is located for: only it is marked with the pin. */
    val located: Boolean = false,
)

/**
 * The weather beside the home clock, from Open-Meteo: no key, reachable from China. The place is
 * the one the owner names on the phone page, or else the one the TV puts itself in (weather/Where:
 * GPS, the network's or the fused fix). Only a TV that has no location of its own falls back to a
 * guess from the address its traffic leaves by, which behind a proxy is the proxy's town — that is
 * how a home in Hangzhou read as Tokyo (2.07).
 *
 * Up to two more towns can be added in Personalize; Home then takes turns through them, ten
 * seconds each, like a departure board.
 *
 * Fetched when Home comes to the front, at most once an hour; nothing runs while the TV is idle.
 * Compose state, written on the main thread only.
 */
object Weather {
    private const val TAG = "AerialWeather"
    private const val PREFS = "weather"
    /**
     * The reading is fetched again this often, by whether anyone is in front of it (the owner,
     * 1.14): three minutes while Home is up, half an hour while the screen is dark. The ticker
     * below is what keeps the second beat going with nobody watching — ten minutes of staleness on
     * the screen at dawn is what the owner saw otherwise.
     */
    private const val REFRESH_ACTIVE_MS = 3 * 60 * 1000L
    private const val REFRESH_IDLE_MS = 30 * 60 * 1000L
    private fun refreshInterval(): Long = if (active) REFRESH_ACTIVE_MS else REFRESH_IDLE_MS

    /** Home in front of the owner (MainActivity, onResume and onPause). */
    @Volatile var active = false
    private const val RETRY_MS = 10 * 60 * 1000L
    private const val RELOCATE_MS = 24 * 60 * 60 * 1000L
    /** A reading older than this is not shown at all. */
    private const val STALE_MS = 3 * 60 * 60 * 1000L
    /** Stands for "no high or low" in the prefs. */
    private const val NONE = Int.MIN_VALUE

    /** How many towns the owner may add beside the located one. */
    const val MAX_EXTRA_CITIES = 3

    var now by mutableStateOf<WeatherNow?>(null); private set

    /** Where the weather is for, as a name to show; null until known. */
    var place by mutableStateOf<String?>(null); private set

    /** The same place in Latin letters (GeoNames), for English and Korean. */
    var placeAscii by mutableStateOf<String?>(null); private set

    /** True when the owner named the place; false while it is guessed from the network. */
    var chosen by mutableStateOf(false); private set

    /** The towns the owner added, at most [MAX_EXTRA_CITIES], in the order added. */
    var extras by mutableStateOf<List<CitySlot>>(emptyList()); private set

    /** Which town Home is showing: 0 is the located one, then [extras] in turn. */
    var cursor by mutableIntStateOf(0); private set

    /** Every town in the rotation, the located one first. */
    val slots: List<CitySlot>
        get() = buildList {
            val n = place
            if (n != null) add(CitySlot(n, placeAscii ?: n, Float.NaN, Float.NaN, now, located = true))
            addAll(extras)
        }

    /** The town on screen now, null while nothing is known. */
    val shown: CitySlot?
        get() = slots.getOrNull(cursor) ?: slots.firstOrNull()

    /** Home's next turn: ten seconds later, the town after this one. */
    fun advance() {
        val n = slots.size
        if (n > 1) cursor = (cursor + 1) % n
    }

    /**
     * When the towns next take their turn, on the monotonic clock (1.83). A time, not a delay the
     * ticker sleeps for: the ticker is restarted whenever an overlay opens or closes, and Home and
     * the Personalize sheet each have one — with a fresh delay every restart the towns stood still
     * for a while after every panel, and with two tickers up at once they turned twice as fast.
     */
    var nextTurnAt = 0L; private set

    /**
     * One turn, if it is due. The first caller starts the clock, and a second ticker asking in the
     * same instant finds the next turn already ten seconds away — Home and the sheet each have a
     * ticker, and both taking the turn made the towns jump two at once and turn twice as often
     * (the owner, 1.83: "滚动不按 10 秒一次").
     */
    fun turnIfDue(everyMs: Long): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (nextTurnAt == 0L) {
            nextTurnAt = now + everyMs
            return false
        }
        if (now < nextTurnAt) return false
        advance()
        nextTurnAt = now + everyMs
        return true
    }

    /** Whether Home takes turns at all. */
    val rotating: Boolean get() = slots.size > 1

    /**
     * The place's name as this language reads it: Chinese in Chinese and Japanese, which use the
     * same characters; Latin letters in English; and in Korean the name the geocoder gives for it
     * (항저우), which falls back to the Latin name where it has none.
     */
    fun placeName(slot: CitySlot?, language: String): String? = slot?.let {
        when (language) {
            "zh", "ja" -> it.name
            "ko" -> koNames[it.ascii] ?: it.ascii.ifBlank { it.name }
            else -> it.ascii.ifBlank { it.name }
        }
    }

    /** The towns' names in Korean (ascii name to Korean name), from the geocoder, kept in the prefs. */
    var koNames by mutableStateOf(emptyMap<String, String>()); private set

    private lateinit var prefs: SharedPreferences
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false
    private var lastAttempt = 0L
    private var job: Job? = null

    fun init(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        place = prefs.getString("place", null)
        placeAscii = prefs.getString("place_ascii", null)
        chosen = prefs.getBoolean("chosen", false)
        // A place located before the Latin name was kept has none: backfill it from the cities
        // table, so English, Korean and the rest do not read a Chinese name forever (the owner,
        // 1.78). The locate path writes the pair together; only old prefs miss the Latin half.
        // Some of those older builds wrote the Chinese name into both halves, which is why a
        // perfectly non-empty 杭州 in the Latin half still left the whole panel Chinese on a
        // French or Russian TV (the owner, 2.17): the pair that reads the same is backfilled too.
        if (place != null && (placeAscii.isNullOrBlank() || placeAscii == place)) {
            val name = place
            scope.launch {
                val latin = withContext(Dispatchers.IO) {
                    val fromList = Cities.load(app).firstOrNull { it.name == name }?.ascii
                    // A place the list does not reach is named again from its own point; the
                    // stored fix costs no request when there is none.
                    fromList?.takeIf { it.isNotBlank() && it != name } ?: run {
                        val lat = prefs.getFloat("lat", Float.NaN).toDouble()
                        val lon = prefs.getFloat("lon", Float.NaN).toDouble()
                        if (lat.isNaN() || lon.isNaN()) null
                        else runCatching { reverseGeocode(lat, lon) }.getOrNull()?.second
                    }?.takeIf { it.isNotBlank() && it != name }
                }
                if (latin != null) {
                    prefs.edit { putString("place_ascii", latin) }
                    placeAscii = latin
                }
            }
        }
        extras = readCities()
        cursor = 0
        scope.launch {
            while (true) {
                delay(60_000L)
                refreshIfStale()
            }
        }
        if (System.currentTimeMillis() - prefs.getLong("fetched_at", 0) < STALE_MS) {
            val kind = runCatching { WeatherKind.valueOf(prefs.getString("kind", null) ?: "") }.getOrNull()
            if (kind != null) now = WeatherNow(
                prefs.getInt("celsius", 0), kind, prefs.getBoolean("day", true),
                high = prefs.getInt("high", NONE).takeIf { it != NONE },
                low = prefs.getInt("low", NONE).takeIf { it != NONE },
                humidity = prefs.getInt("humidity", NONE).takeIf { it != NONE },
                usAqi = prefs.getInt("aqi", NONE).takeIf { it != NONE },
            )
        }
    }

    private var failed = false

    /** Like the daily photos: a failed reading is retried when the network connects (issue #129). */
    fun refreshIfStale(networkCameUp: Boolean = false) {
        if (!started) return
        val since = System.currentTimeMillis() - lastAttempt
        // A reading saved before 1.70 has no humidity, one before 1.72 no air quality: fetch once so
        // the weather block can show them, on the same interval as any other fetch.
        val missingDetail = (now?.humidity == null || now?.usAqi == null) && since >= refreshInterval()
        if (since >= refreshInterval() || missingDetail || (failed && networkCameUp)) refresh()
    }

    private fun refresh() {
        lastAttempt = System.currentTimeMillis()
        job?.cancel()
        val towns = extras
        job = scope.launch {
            val reading = withContext(Dispatchers.IO) {
                try {
                    fetch()
                } catch (t: Throwable) {
                    Log.w(TAG, "weather unavailable", t)
                    null
                }
            }
            // The owner's own towns, one request each; a town that fails keeps the reading it had.
            val extraReadings = if (towns.isEmpty()) emptyList() else withContext(Dispatchers.IO) {
                towns.map { town ->
                    runCatching { fetchAt(town.lat, town.lon) }
                        .onFailure { Log.w(TAG, "weather unavailable for ${town.name}", it) }
                        .getOrNull()
                }
            }
            prefs.getString("place", null)?.let { place = it }
            prefs.getString("place_ascii", null)?.let { placeAscii = it }
            failed = reading == null
            if (reading != null) {
                now = reading
                prefs.edit {
                    putLong("fetched_at", System.currentTimeMillis())
                    putInt("celsius", reading.celsius)
                    putString("kind", reading.kind.name)
                    putBoolean("day", reading.day)
                    putInt("high", reading.high ?: NONE)
                    putInt("low", reading.low ?: NONE)
                    putInt("humidity", reading.humidity ?: NONE)
                    putInt("aqi", reading.usAqi ?: NONE)
                }
            }
            if (extraReadings.isNotEmpty()) {
                extras = towns.mapIndexed { i, town -> town.copy(now = extraReadings[i] ?: town.now) }
            }
            // The towns' names in Korean: one request each, kept, so it is asked once in a town's life.
            val saved = readKoNames()
            val wanted = buildList {
                prefs.getString("place_ascii", null)?.takeIf { it.isNotBlank() }?.let { add(it) }
                towns.mapTo(this) { it.ascii }
            }.distinct().filter { it.isNotBlank() && saved[it] == null }
            if (wanted.isNotEmpty()) {
                val found = withContext(Dispatchers.IO) {
                    wanted.mapNotNull { ascii -> koreanName(ascii)?.let { ascii to it } }.toMap()
                }
                if (found.isNotEmpty()) {
                    koNames = saved + found
                    writeKoNames(koNames)
                }
            } else if (koNames != saved) {
                koNames = saved
            }
        }
    }

    /** When the reading on screen was fetched, for Personalize: " · 更新于 17:05". */
    fun fetchedAtText(): String {
        val at = if (started) prefs.getLong("fetched_at", 0L) else 0L
        if (at == 0L) return ""
        return " · ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))} 更新"
    }

    /** A town's name in Korean, from the geocoder: null where it has none of its own. */
    private fun koreanName(ascii: String): String? = runCatching {
        val url = "https://geocoding-api.open-meteo.com/v1/search?name=" +
            URLEncoder.encode(ascii, "UTF-8") + "&count=1&language=ko&format=json"
        val results = JSONObject(get(url)).optJSONArray("results") ?: return null
        val name = results.getJSONObject(0).optString("name", "").ifBlank { return null }
        // Asked for a Korean name it does not have, it answers with the name it was given.
        name.takeIf { it != ascii }
    }.onFailure { Log.w(TAG, "korean name unavailable for $ascii", it) }.getOrNull()

    private fun readKoNames(): Map<String, String> =
        prefs.getString("ko_names", null).orEmpty().lineSequence().mapNotNull { line ->
            val split = line.indexOf('=')
            if (split <= 0) null else line.substring(0, split) to line.substring(split + 1)
        }.toMap()

    private fun writeKoNames(names: Map<String, String>) =
        prefs.edit { putString("ko_names", names.entries.joinToString("\n") { "${it.key}=${it.value}" }) }

    /** Off the main thread. */
    private suspend fun fetch(): WeatherNow {
        var lat = prefs.getFloat("lat", Float.NaN)
        var lon = prefs.getFloat("lon", Float.NaN)
        val guessedLongAgo = System.currentTimeMillis() - prefs.getLong("located_at", 0) > RELOCATE_MS
        if (prefs.getBoolean("chosen", false) && !lat.isNaN() && !prefs.getBoolean("checked_against_cities", false)) {
            correctChosenPlace(lat, lon)?.let { lat = it.lat.toFloat(); lon = it.lon.toFloat() }
        }
        // A town the address named is a guess until the TV has placed itself: a fix of the TV's own
        // is asked for again, once, however fresh the guess is (2.07).
        val neverLocatedByTv = prefs.getString("located_by", null) !in listOf("device", "phone")
        if (!prefs.getBoolean("chosen", false) && (lat.isNaN() || guessedLongAgo || neverLocatedByTv)) {
            // The TV's own fix first: the address its traffic leaves by is the proxy's town behind
            // one, and that is how a home in Hangzhou read as Tokyo (the owner, 2.07).
            val fix = Where.device(app)
            if (fix != null) {
                val named = nameAt(fix.lat, fix.lon)
                prefs.edit {
                    putFloat("lat", fix.lat.toFloat()); putFloat("lon", fix.lon.toFloat())
                    putLong("located_at", System.currentTimeMillis())
                    putString("located_by", fix.how)
                    if (named != null) {
                        putString("place", named.first)
                        putString("place_ascii", named.second)
                    }
                }
                lat = fix.lat.toFloat(); lon = fix.lon.toFloat()
            } else if (lat.isNaN() || guessedLongAgo) {
                val byAddress = locateByAddress()
                if (byAddress != null) {
                    lat = byAddress.first; lon = byAddress.second
                } else {
                    lat = prefs.getFloat("lat", Float.NaN); lon = prefs.getFloat("lon", Float.NaN)
                }
            }
        }
        return fetchAt(lat, lon)
    }

    /**
     * The town a fix belongs to: the listed city nearest to it, or the one the geocoder names for
     * the point where Aerial's own list does not reach. Null when neither can name it; the weather
     * still stands at the point. Off the main thread: the geocoder is a request.
     */
    fun nameAt(lat: Double, lon: Double): Pair<String, String>? {
        Cities.nearest(app, lat, lon)?.let { return it.name to it.ascii.ifBlank { it.name } }
        return reverseGeocode(lat, lon)
    }

    /**
     * Names a point from BigDataCloud's reverse geocoder: no key, and it answers in Chinese. Only
     * for a fix outside Aerial's own list — abroad, or a place the list misses.
     */
    private fun reverseGeocode(lat: Double, lon: Double): Pair<String, String>? = runCatching {
        fun town(json: JSONObject) = json.optString("city").ifBlank { json.optString("locality") }
            .ifBlank { json.optString("principalSubdivision") }
        val zh = town(JSONObject(get("https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=$lat&longitude=$lon&localityLanguage=zh"))
            ).removeSuffix("市").removeSuffix("区")
        if (zh.isBlank()) null else {
            val en = runCatching {
                town(JSONObject(get("https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=$lat&longitude=$lon&localityLanguage=en")))
            }.getOrNull().orEmpty()
            DiagLog.i("weather: the point reads as $zh")
            zh to en.ifBlank { zh }
        }
    }.onFailure { Log.w(TAG, "could not name the point", it) }.getOrNull()

    /**
     * The place from the address the TV's traffic leaves by: ipinfo.io, no key, its "loc" being
     * "lat,lon". Behind a proxy that address is the proxy's town, so this is the last resort.
     * Null when the town already known should stand.
     */
    private fun locateByAddress(): Pair<Float, Float>? {
        val ip = JSONObject(get("https://ipinfo.io/json"))
        val loc = ip.getString("loc").split(',')
        val lat = loc[0].toFloat()
        val lon = loc[1].toFloat()
        // ipinfo names the city in English ("Shangqiu"); in China, say it as the TV's owner would.
        val city = ip.optString("city").ifBlank { null }
        val near = city?.takeIf { ip.optString("country") == "CN" }
            ?.let { Cities.near(app, it, lat.toDouble(), lon.toDouble(), km = 80.0, ascii = true) }
        // An address that reads as another country is a guess gone wrong, not a move: the town
        // the weather stands for stays the one it had (the owner's TV, whose address put it in
        // Tokyo, 2.04). With no town at all, a wrong one is still a start, and the picker sets
        // it right.
        val home = java.util.Locale.getDefault().country
        if (prefs.getString("place", null) != null && home.isNotBlank() && !ip.optString("country").equals(home, true)) {
            DiagLog.i("weather: address reads ${ip.optString("country")}, not $home; the town stays")
            return null
        }
        prefs.edit {
            putFloat("lat", lat); putFloat("lon", lon)
            putString("place", near?.name ?: city)
            putString("place_ascii", near?.ascii ?: city)
            putLong("located_at", System.currentTimeMillis())
            putString("located_by", "address")
        }
        return lat to lon
    }

    /** One town's reading from its coordinates; off the main thread. */
    private fun fetchAt(lat: Float, lon: Float): WeatherNow {
        val json = JSONObject(get("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code,is_day,relative_humidity_2m&daily=temperature_2m_max,temperature_2m_min&forecast_days=1&timezone=auto"))
        val current = json.getJSONObject("current")
        val daily = json.optJSONObject("daily")
        fun today(key: String) = daily?.optJSONArray(key)?.optDouble(0)?.takeIf { !it.isNaN() }?.roundToInt()
        // The degrees, the humidity and the air's index come from the one station (the owner,
        // 1.14); the sky's icon and today's range stay Open-Meteo's, which is the only one of the
        // two that knows what the sky is doing. 在意空气's ground is Greater China, where its
        // stations are dense; abroad the free sources answer for themselves (the owner, 1.6).
        val air = if (inGreaterChina(lat, lon)) airReading(lat, lon) else AirReading()
        return WeatherNow(
            celsius = air.celsius ?: current.getDouble("temperature_2m").roundToInt(),
            kind = kindOf(current.getInt("weather_code")),
            day = current.optInt("is_day", 1) == 1,
            high = today("temperature_2m_max"),
            low = today("temperature_2m_min"),
            humidity = air.humidity
                ?: current.optDouble("relative_humidity_2m").takeIf { !it.isNaN() }?.roundToInt(),
            usAqi = air.aqi ?: openMeteoAqi(lat, lon),
        )
    }

    /**
     * The three figures the owner checks — the degrees, the humidity, the air's index — read from
     * one place, one station, one moment (the owner, 1.14): 在意空气 has all three in one answer,
     * where they used to come from two hosts and two clocks. A reading it does not carry falls
     * back to Open-Meteo's own, so a station without a thermometer costs only the thermometer.
     */
    private data class AirReading(val celsius: Int? = null, val humidity: Int? = null, val aqi: Int? = null)

    /** Greater China — the mainland with 香港、澳门、台湾 — where 在意空气's stations stand. */
    private fun inGreaterChina(lat: Float, lon: Float) = lat in 17f..55f && lon in 73f..135.5f

    /**
     * 在意空气's own source (aqicn.org, the WAQI feed), by coordinates so no town needs naming.
     * The token is free and immediate at aqicn.org/data-platform/token — put it in
     * `local/waqi.token` (not in version control) and rebuild; "demo" answers Shanghai for every
     * point, so it is refused.
     */
    private val WAQI_TOKEN = BuildConfig.WAQI_TOKEN

    private fun airReading(lat: Float, lon: Float): AirReading = runCatching {
        if (WAQI_TOKEN == "demo") return@runCatching AirReading()
        val json = JSONObject(get("https://api.waqi.info/feed/geo:$lat;$lon/?token=$WAQI_TOKEN"))
        if (json.optString("status") != "ok") return@runCatching AirReading()
        val data = json.getJSONObject("data")
        val iaqi = data.optJSONObject("iaqi")
        fun figure(key: String) = iaqi?.optJSONObject(key)?.optDouble("v")?.takeIf { !it.isNaN() }?.roundToInt()
        AirReading(
            celsius = figure("t"),
            humidity = figure("h")?.takeIf { it in 0..100 },
            aqi = data.optString("aqi").toDoubleOrNull()?.roundToInt()?.takeIf { it > 0 },
        )
    }.onFailure { Log.w(TAG, "aqicn unavailable", it) }.getOrNull() ?: AirReading()

    private fun openMeteoAqi(lat: Float, lon: Float): Int? = runCatching {
        val json = JSONObject(get("https://air-quality-api.open-meteo.com/v1/air-quality?latitude=$lat&longitude=$lon&current=us_aqi&timezone=auto"))
        json.getJSONObject("current").optDouble("us_aqi").takeIf { !it.isNaN() }?.roundToInt()
    }.onFailure { Log.w(TAG, "air quality unavailable", it) }.getOrNull()

    /**
     * A place chosen before 1.29 came from Open-Meteo's geocoder, which took 安庆 for a village in
     * Jiangsu. If a listed city of that name is not where the chosen point is, the city is meant.
     * Checked once.
     */
    private fun correctChosenPlace(lat: Float, lon: Float): Cities.City? {
        val name = prefs.getString("place", null)
        val city = name?.let { runCatching { Cities.find(app, it) }.getOrNull() }
            ?.takeIf { Cities.near(app, it.name, lat.toDouble(), lon.toDouble(), km = 30.0) == null }
        prefs.edit {
            putBoolean("checked_against_cities", true)
            if (city != null) {
                Log.i(TAG, "chosen place $name moved to the city of that name")
                putFloat("lat", city.lat.toFloat()); putFloat("lon", city.lon.toFloat())
            }
        }
        return city
    }

    /**
     * Names the place (from the phone page) and fetches its weather at once. Chinese names are
     * looked up in Aerial's own list first, others (pinyin, places abroad) in Open-Meteo's geocoder.
     * False when there is no such place.
     */
    suspend fun choose(name: String): Boolean {
        val hit = withContext(Dispatchers.IO) {
            runCatching { Cities.find(app, name) }.onFailure { Log.w(TAG, "could not read the city list", it) }.getOrNull()
                ?.let { Quad(it.lat, it.lon, it.name, it.ascii) }
                ?: runCatching {
                    val q = URLEncoder.encode(name.trim(), "UTF-8")
                    val results = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=10&language=zh&format=json"))
                        .optJSONArray("results")
                    val all = (0 until (results?.length() ?: 0)).map { results!!.getJSONObject(it) }
                    // Towns before villages of the same name.
                    val best = all.firstOrNull { it.optString("feature_code").startsWith("PPLA") || it.optString("feature_code") == "PPLC" } ?: all.firstOrNull()
                    best?.let {
                        val n = it.optString("name").ifBlank { name.trim() }
                        // This list answers in Chinese, so the name it gives is no Latin name:
                        // the point's own Latin reading comes from the reverse geocoder, and the
                        // pair stands as it is when that fails (the owner, 2.17 — 杭州/杭州 left
                        // the whole weather panel Chinese on a French TV).
                        val latin = runCatching {
                            reverseGeocode(it.getDouble("latitude"), it.getDouble("longitude"))
                        }.getOrNull()?.second?.takeIf { en -> en.any { c -> c.code in 32..126 } }
                        Quad(it.getDouble("latitude"), it.getDouble("longitude"), n, latin ?: n)
                    }
                }.onFailure { Log.w(TAG, "could not look up $name", it) }.getOrNull()
        } ?: return false
        prefs.edit {
            putFloat("lat", hit.lat.toFloat())
            putFloat("lon", hit.lon.toFloat())
            putString("place", hit.name)
            putString("place_ascii", hit.ascii)
            putBoolean("chosen", true)
            putBoolean("checked_against_cities", true)
        }
        chosen = true
        place = prefs.getString("place", null)
        placeAscii = prefs.getString("place_ascii", null)
        refresh()
        return true
    }

    /** A place looked up: coordinates and both names. */
    private class Quad(val lat: Double, val lon: Double, val name: String, val ascii: String)

    /**
     * City search for the phone page: the geocoder's answers in the phone's own language, so a
     * query typed in any script comes back readable, towns before villages. Off the main thread.
     */
    fun search(query: String, lang: String): JSONObject {
        val q = query.trim()
        val hits = JSONArray()
        if (q.isEmpty()) return JSONObject().put("hits", hits)
        val page = lang.take(2).lowercase().ifBlank { "en" }

        fun ask(language: String): List<JSONObject> = runCatching {
            val url = "https://geocoding-api.open-meteo.com/v1/search?name=" +
                URLEncoder.encode(q, "UTF-8") + "&count=8&language=" +
                URLEncoder.encode(language, "UTF-8") + "&format=json"
            val res = JSONObject(get(url)).optJSONArray("results") ?: JSONArray()
            (0 until res.length()).map { res.getJSONObject(it) }
        }.onFailure { Log.w(TAG, "city search failed", it) }.getOrDefault(emptyList())

        var found = ask(page)
        var inPageLanguage = true
        if (found.isEmpty()) {
            // A name in a script the index does not match (東京 answers in zh, not ja): found in
            // another language, then read back in the page's own by id — Apple's rule, the local
            // name where it has one, English where it does not.
            found = ask("en").ifEmpty { ask("zh") }
            inPageLanguage = false
        }
        for (r0 in found) {
            var r = r0
            if (!inPageLanguage) {
                val id = r0.optLong("id", 0L)
                if (id != 0L) runCatching {
                    r = JSONObject(get("https://geocoding-api.open-meteo.com/v1/get?id=$id&language=" + URLEncoder.encode(page, "UTF-8")))
                }.onFailure { Log.w(TAG, "could not name ${r0.optString("name")} in $page", it) }
            }
            val name = r.optString("name").ifBlank { q }
            val region = r.optString("admin1").ifBlank { r0.optString("admin1") }
            val country = r.optString("country").ifBlank { r0.optString("country") }
            hits.put(JSONObject()
                .put("lat", r.optDouble("latitude", r0.optDouble("latitude", 0.0)))
                .put("lon", r.optDouble("longitude", r0.optDouble("longitude", 0.0)))
                .put("name", name)
                .put("ascii", r0.optString("ascii_name", "").ifBlank { name })
                .put("where", listOf(region, country).filter { it.isNotBlank() }.distinct().joinToString(" · ")))
        }
        return JSONObject().put("hits", hits)
    }

    /**
     * A city picked on the phone out of [search]'s answers: its point and its names stand as the
     * answer gave them, nothing looked up again. A fix abroad keeps the name the searcher saw.
     */
    suspend fun pick(lat: Double, lon: Double, name: String, ascii: String) {
        prefs.edit {
            putFloat("lat", lat.toFloat())
            putFloat("lon", lon.toFloat())
            putString("place", name)
            putString("place_ascii", ascii.ifBlank { name })
            putBoolean("chosen", true)
            putBoolean("checked_against_cities", true)
        }
        chosen = true
        place = prefs.getString("place", null)
        placeAscii = prefs.getString("place_ascii", null)
        refresh()
    }

    /**
     * Places the weather at a point somebody else found: the phone page, whose browser knows where
     * the phone is. The one sure place for a TV that has no location of its own — no GPS, no
     * network location — and whose address reads as the proxy's town (the owner, 2.07).
     * [named] is the town at that point, already looked up off the main thread.
     */
    fun locate(lat: Double, lon: Double, named: Pair<String, String>? = null, how: String = "phone") {
        prefs.edit {
            putFloat("lat", lat.toFloat()); putFloat("lon", lon.toFloat())
            putBoolean("chosen", false)
            putBoolean("checked_against_cities", true)
            putLong("located_at", System.currentTimeMillis())
            putString("located_by", how)
            if (named != null) {
                putString("place", named.first)
                putString("place_ascii", named.second)
            }
        }
        chosen = false
        place = prefs.getString("place", null)
        placeAscii = prefs.getString("place_ascii", null)
        DiagLog.i("weather: put at ${"%.4f".format(java.util.Locale.US, lat)},${"%.4f".format(java.util.Locale.US, lon)} by $how (${named?.first ?: "no town named"})")
        // Named later, off the main thread, when the caller had no name to hand.
        if (named == null) scope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { nameAt(lat, lon) }.getOrNull() }
            if (found != null) {
                prefs.edit { putString("place", found.first); putString("place_ascii", found.second) }
                place = found.first
                placeAscii = found.second
            }
        }
        refresh()
    }

    /**
     * Adds one of the listed towns to the rotation, up to [MAX_EXTRA_CITIES]; false when the list is
     * full or the town is already there.
     */
    fun addCity(city: Cities.City): Boolean {
        if (extras.size >= MAX_EXTRA_CITIES || extras.any { it.name == city.name }) return false
        extras = extras + CitySlot(city.name, city.ascii, city.lat.toFloat(), city.lon.toFloat())
        writeCities()
        refresh()
        return true
    }

    /** Takes a town out of the rotation, by its Chinese name. */
    fun removeCity(name: String) {
        val left = extras.filterNot { it.name == name }
        if (left.size == extras.size) return
        extras = left
        if (cursor >= slots.size) cursor = 0
        writeCities()
    }

    private fun readCities(): List<CitySlot> = prefs.getString("extras", null)?.lineSequence()
        ?.filter { it.isNotBlank() }
        ?.mapNotNull { line ->
            val c = line.split('\t')
            if (c.size < 4) return@mapNotNull null
            val lat = c[2].toFloatOrNull() ?: return@mapNotNull null
            val lon = c[3].toFloatOrNull() ?: return@mapNotNull null
            CitySlot(c[0], c[1], lat, lon)
        }
        ?.take(MAX_EXTRA_CITIES)
        ?.toList()
        .orEmpty()

    private fun writeCities() {
        prefs.edit {
            putString("extras", extras.joinToString("\n") { "${it.name}\t${it.ascii}\t${it.lat}\t${it.lon}" })
        }
    }

    /** Back to locating the place: the TV's own fix, or else the address. */
    fun useNetworkLocation() {
        prefs.edit {
            putBoolean("chosen", false); putString("place_ascii", null)
            remove("lat"); remove("lon"); remove("located_at"); remove("located_by")
        }
        chosen = false
        refresh()
    }

    /** WMO weather interpretation codes, as Open-Meteo reports them. */
    private fun kindOf(code: Int): WeatherKind = when (code) {
        0 -> WeatherKind.Clear
        1, 2 -> WeatherKind.PartlyCloudy
        3 -> WeatherKind.Cloudy
        45, 48 -> WeatherKind.Fog
        in 71..77, 85, 86 -> WeatherKind.Snow
        in 95..99 -> WeatherKind.Thunder
        else -> WeatherKind.Rain // drizzle, rain and showers: 51..67, 80..82
    }

    /**
     * Android before 7.1 does not trust the Let's Encrypt root these hosts use; they answer the
     * same over plain HTTP (allowed for them in network_security_config.xml).
     */
    private fun get(url: String): String = try {
        read(url)
    } catch (e: SSLException) {
        read(url.replaceFirst("https://", "http://"))
    }

    private fun read(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 10_000
        return try {
            c.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            c.disconnect()
        }
    }
}
