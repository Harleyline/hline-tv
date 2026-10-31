// WifiConfiguration, and the scan and network APIs around it, are what a third party has before
// Android 10 (see WifiNetworks): the new ones are suggestions the system may take up, not a list.
@file:Suppress("DEPRECATION")

package app.orchard.tv.system

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.getSystemService

/**
 * A Wi-Fi network the TV can see, as Control Center's Wi-Fi page lists it.
 *
 * @param level 0..1, from the signal strength, as the status pill's.
 * @param open one with no password: it is joined without asking.
 * @param saved one the TV already knows, so its password was given before.
 * @param current the one the TV is on.
 * @param id its network id in the TV's saved list, -1 when it is not saved.
 * @param closed one Aerial cannot join with a password alone: WPA-Enterprise, or WPA3 on a
 *   TV too old for it (Android 9). They are still listed; the TV's own page joins them.
 */
data class WifiNetwork(
    val ssid: String,
    val level: Float,
    val open: Boolean,
    val saved: Boolean,
    val current: Boolean,
    val id: Int,
    val closed: Boolean,
)

/**
 * The Wi-Fi networks around the TV, and the way onto one of them.
 *
 * A launcher is not the system, and since Android 10 the system is the only one allowed to add a
 * network: [WifiManager.addNetwork] answers -1 to any app built for 10 or later (the documentation
 * says so plainly). So Aerial's own list is what it is on every TV — it shows what the Wi-Fi sees,
 * and where the platform lets a third party in (Android 9 and older, which is what most of these
 * TVs run), it joins from here; elsewhere a network one taps opens the TV's own page, which is
 * where that network was always joined anyway.
 *
 * The scan needs the TV's location to be on, as Android has asked since 6: without it
 * [WifiManager.getScanResults] hands back nothing at all (with the permission granted and all), and
 * the list would read as though there were no Wi-Fi in the house.
 */
object WifiNetworks {

    /** The SSID the TV is on, without the quotes the platform puts around it; null when none. */
    fun current(context: Context): String? {
        val wm = context.applicationContext.getSystemService<WifiManager>() ?: return null
        return runCatching { wm.connectionInfo?.ssid?.trim('"') }.getOrNull()
            ?.takeUnless { it.isBlank() || it == "<unknown ssid>" }
    }

    /** Whether this TV lets an app add a network of its own: before Android 10 (see above). */
    val canJoin: Boolean get() = Build.VERSION.SDK_INT < 29

    /** Tells the Wi-Fi to look, as an app may. Throttled by the system: it is a request, not an order. */
    fun startScan(context: Context) {
        val wm = context.applicationContext.getSystemService<WifiManager>() ?: return
        runCatching { wm.startScan() }.onFailure { DiagLog.i("wifi: the TV refused a scan") }
    }

    /**
     * The networks around, strongest first, the one the TV is on at the top; a saved network out
     * of range is listed too, so it can be joined again the moment it is back.
     */
    fun scan(context: Context): List<WifiNetwork> {
        val wm = context.applicationContext.getSystemService<WifiManager>() ?: return emptyList()
        val on = current(context)
        val known = runCatching { wm.configuredNetworks }.getOrDefault(null).orEmpty()
            .associateBy { it.SSID.trim('"') }
        val seen = runCatching { wm.scanResults }.getOrDefault(emptyList())
            .filter { !it.SSID.isNullOrBlank() }
            .let { results ->
                // One row per network, the strongest of its access points.
                results.groupBy { it.SSID.trim('"') }.map { (ssid, aps) ->
                    val best = aps.maxBy { it.level }
                    val caps = aps.joinToString("") { it.capabilities.orEmpty() }
                    val knownNetwork = known[ssid]
                    WifiNetwork(
                        ssid = ssid,
                        level = level(wm, best.level),
                        open = !secured(caps),
                        saved = knownNetwork != null,
                        current = ssid == on,
                        id = knownNetwork?.networkId ?: -1,
                        closed = caps.contains("EAP") || caps.contains("SAE"),
                    )
                }
            }
        // Saved but out of sight, so a network the TV was on at home can be joined again.
        val missing = known.values.filter { c ->
            val ssid = c.SSID.trim('"')
            ssid != on && seen.none { it.ssid == ssid }
        }.map { c ->
            // Its key was given before, so it is never asked for again: [open] only decides
            // whether a new network needs a password typed.
            WifiNetwork(c.SSID.trim('"'), 0f, open = false, saved = true, current = false, id = c.networkId, closed = false)
        }
        return (seen + missing).sortedWith(compareByDescending<WifiNetwork> { it.current }.thenByDescending { it.level }.thenBy { it.ssid })
    }

    /**
     * Joins [network], with [password] where it has one, and says whether the TV took it.
     *
     * A saved network is enabled again by its id; a new one is added to the TV's list first, and
     * it is that list's own entry the TV then turns to. Only the one network is told to connect:
     * [WifiManager.enableNetwork]'s second argument is what kept Aerial from dropping the network
     * the owner was watching a film on.
     */
    fun join(context: Context, network: WifiNetwork, password: String): Boolean {
        val wm = context.applicationContext.getSystemService<WifiManager>() ?: return false
        if (!canJoin) return false
        return runCatching {
            val id = if (network.saved) network.id else {
                val config = WifiConfiguration().apply {
                    SSID = "\"${network.ssid}\""
                    if (network.open) {
                        allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                    } else {
                        // WPA2-PSK, as every home network is: the key wrapped in quotes tells the
                        // platform it is the typed phrase, not its hex.
                        preSharedKey = "\"$password\""
                        allowedProtocols.set(WifiConfiguration.Protocol.RSN)
                        allowedProtocols.set(WifiConfiguration.Protocol.WPA)
                        allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                        allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP)
                        allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP)
                        allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP)
                        allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP)
                    }
                }
                wm.addNetwork(config)
            }
            if (id < 0) {
                DiagLog.i("wifi: the TV would not add ${network.ssid}")
                false
            } else {
                wm.enableNetwork(id, false)
                wm.reconnect()
                DiagLog.i("wifi: joining ${network.ssid}${if (network.saved) " (saved)" else ""}")
                true
            }
        }.getOrDefault(false)
    }

    private fun level(wm: WifiManager, rssi: Int): Float =
        if (Build.VERSION.SDK_INT >= 30) {
            wm.calculateSignalLevel(rssi) / wm.maxSignalLevel.coerceAtLeast(1).toFloat()
        } else {
            WifiManager.calculateSignalLevel(rssi, 4) / 3f
        }

    /** Whether the access point asks for a key: open ones advertise nothing but [ESS]. */
    private fun secured(capabilities: String) = capabilities.contains("WPA") ||
        capabilities.contains("WEP") ||
        capabilities.contains("PSK") ||
        capabilities.contains("SAE") ||
        capabilities.contains("EAP")
}
