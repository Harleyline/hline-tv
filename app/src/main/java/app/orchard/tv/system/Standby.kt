package app.orchard.tv.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import android.util.Log

/**
 * Standby that keeps the network up (快速待机): writes the system's `tv_power_mode` to QUICK, so
 * Mi Home and other LAN control keep working while the TV is off, and wake-up is instant. The
 * viewer's choice, on by default (the owner's call); it can be turned off in Control Center's
 * setup page, which restores whatever the firmware had before.
 *
 * The key is where at least OPPO's ColorOS TV keeps the mode; other firmwares that share the key
 * follow too. Where it is not honored the write is harmless, and the setup page says to pick the
 * mode in the TV's own settings. Needs WRITE_SECURE_SETTINGS, granted over ADB by install.sh.
 */
object Standby {
    private const val TAG = "AerialStandby"
    private const val KEY = "tv_power_mode"
    private const val QUICK = "QUICK"
    private const val PREFS = "system"
    private const val KEY_CHOICE = "quick_standby"
    private const val KEY_PREVIOUS = "prev_power_mode"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The viewer's choice; on unless they turned it off. */
    fun isChosen(context: Context): Boolean = prefs(context).getBoolean(KEY_CHOICE, true)

    /** Whether the system currently says QUICK. */
    fun isQuick(context: Context): Boolean =
        runCatching { Settings.Global.getString(context.contentResolver, KEY) == QUICK }.getOrDefault(false)

    /** Whether we may write the setting at all. */
    fun canWrite(context: Context): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.WRITE_SECURE_SETTINGS,
    ) == PackageManager.PERMISSION_GRANTED

    /** Applies the choice; false when WRITE_SECURE_SETTINGS is missing. */
    fun apply(context: Context): Boolean {
        if (!canWrite(context)) return false
        val resolver = context.contentResolver
        val current = runCatching { Settings.Global.getString(resolver, KEY) }.getOrNull()
        return runCatching {
            if (isChosen(context)) {
                if (current != QUICK) {
                    if (prefs(context).getString(KEY_PREVIOUS, null) == null) {
                        prefs(context).edit().putString(KEY_PREVIOUS, current).apply()
                    }
                    Settings.Global.putString(resolver, KEY, QUICK)
                    Log.i(TAG, "standby set to QUICK (was ${current ?: "unset"})")
                }
            } else if (current == QUICK) {
                val previous = prefs(context).getString(KEY_PREVIOUS, null)
                if (previous != null) {
                    Settings.Global.putString(resolver, KEY, previous)
                    prefs(context).edit().remove(KEY_PREVIOUS).apply()
                    Log.i(TAG, "standby restored to $previous")
                }
            }
            true
        }.onFailure { Log.w(TAG, "could not write $KEY", it) }.getOrDefault(false)
    }

    /** Records the choice and applies it; false when the permission is missing. */
    fun setChosen(context: Context, chosen: Boolean): Boolean {
        prefs(context).edit().putBoolean(KEY_CHOICE, chosen).apply()
        return apply(context)
    }
}
