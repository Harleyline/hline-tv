package app.orchard.tv.weather

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import app.orchard.tv.system.DiagLog
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Where the TV stands, from the location service the TV runs: GPS, the network's own fix or the
 * fused one. Not the address its traffic leaves by — behind a proxy that address is the proxy's,
 * and a home in Hangzhou reads as Tokyo (the owner, 2.07). A TV with no location of its own says
 * so, and the weather then falls back to the address as it did before.
 *
 * Off the main thread; [device] waits no longer than [WAIT_MS] for a fresh fix.
 */
object Where {
    /** A place the TV found for itself: where, by what, how old and how sure. */
    class Fix(val lat: Double, val lon: Double, val how: String, val accuracy: Float, val age: Long)

    /** A fix older than this is asked for again. */
    private const val FRESH_MS = 30 * 60 * 1000L
    /** How long to wait for one when the last known is missing or stale. */
    private const val WAIT_MS = 15_000L
    // The fused one first: it is the one Android answers from, GPS and the network behind it. A TV
    // with no GPS hardware and no network location has the fused one alone, and it answers nothing.
    private val PROVIDERS = listOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)

    /** The TV's own fix, or null when it has none. */
    suspend fun device(context: Context): Fix? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null) {
            DiagLog.i("weather: no location service on this TV; falling back to the address")
            return null
        }
        if (!allowed(context)) {
            DiagLog.i("weather: location not granted; falling back to the address")
            return null
        }
        val now = System.currentTimeMillis()
        val known = PROVIDERS.mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull()?.let { p to it } }
        val last = known.map { it.second }.minByOrNull { score(it, now) }
        if (last != null && now - last.time <= FRESH_MS) {
            return report(last, last.provider ?: "?", now)
        }
        val fresh = runCatching { askForFix(lm) }.onFailure { DiagLog.i("weather: could not ask for a fix: ${it.message}") }.getOrNull()
        val use = fresh?.first ?: last
        val how = fresh?.second ?: last?.provider ?: "?"
        if (use == null) {
            DiagLog.i("weather: no fix of the TV's own (providers: ${providers(lm)}); falling back to the address")
            return null
        }
        if (fresh == null) DiagLog.i("weather: no fresh fix, taking the last one known")
        return report(use, how, now)
    }

    /** Freshest and surest first: accuracy in metres, plus a minute for every minute of age. */
    private fun score(l: Location, now: Long): Float =
        (if (l.accuracy <= 0f) 10_000f else l.accuracy) + (now - l.time).coerceAtLeast(0L) / 60_000f

    private fun report(l: Location, how: String, now: Long): Fix {
        val age = (now - l.time).coerceAtLeast(0L)
        DiagLog.i("weather: the TV puts itself at ${fmt(l.latitude)},${fmt(l.longitude)} by $how (±${l.accuracy.toInt()} m, ${age / 1000}s old)")
        return Fix(l.latitude, l.longitude, how, l.accuracy, age)
    }

    private fun fmt(v: Double): String = "%.4f".format(java.util.Locale.US, v)

    /** Which providers the TV says are on, for the log. */
    private fun providers(lm: LocationManager): String =
        PROVIDERS.filter { p -> runCatching { lm.isProviderEnabled(p) }.getOrDefault(false) }.joinToString().ifEmpty { "none" }

    private fun allowed(context: Context): Boolean {
        // ContextCompat, not Context: the latter's check is Android 6 and up, and Aerial runs on 5.
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    /** One fresh fix from whichever provider answers first, or null when none does in [WAIT_MS]. */
    private suspend fun askForFix(lm: LocationManager): Pair<Location, String>? = withTimeoutOrNull(WAIT_MS) {
        suspendCancellableCoroutine { cont ->
            // The passive one takes no request of its own: it only carries what others report.
            val asked = PROVIDERS.filter { it != LocationManager.PASSIVE_PROVIDER }
                .filter { p -> runCatching { lm.isProviderEnabled(p) }.getOrDefault(false) }
            if (asked.isEmpty()) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }
            var answered = false
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (answered) return
                    answered = true
                    runCatching { lm.removeUpdates(this) }
                    if (!cont.isCompleted) cont.resume(location to (location.provider ?: "?"))
                }
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                override fun onProviderEnabled(provider: String) = Unit
                override fun onProviderDisabled(provider: String) = Unit
            }
            var any = false
            asked.forEach { p ->
                runCatching {
                    lm.requestLocationUpdates(p, 0L, 0f, listener, Looper.getMainLooper())
                    any = true
                }.onFailure { DiagLog.i("weather: $p would not take a request: ${it.message}") }
            }
            if (!any) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }
            cont.invokeOnCancellation { runCatching { lm.removeUpdates(listener) } }
        }
    }
}
