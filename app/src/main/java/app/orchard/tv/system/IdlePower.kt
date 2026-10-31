package app.orchard.tv.system

import android.content.Context
import android.provider.Settings
import app.orchard.tv.settings.AerialSettings

/**
 * What the desktop does when nobody touches the remote: after the minutes in [AerialSettings] the
 * TV goes to standby, and any key arms the delay again.
 *
 * The standby is the TV's: the system's screen-off delay is written to the mark, so the firmware
 * suspends itself exactly as it does after its idle period. A launcher cannot call
 * PowerManager.goToSleep (that needs DEVICE_POWER), and drawing a black rectangle over the desktop
 * would save nothing: on an LCD the backlight is the power.
 *
 * Aerial used to dim the picture on the way there. It stopped: the dim was two things at once, the
 * window's own brightness override and wherever this TV keeps its backlight, and neither moved the
 * panel on the TVs that matter — the override alone was recorded by the power service and still
 * left the picture exactly as bright as before (MiTV-NSOP0, 2026-10-29 00:39), because these
 * firmwares drive the backlight through their own TV service. So the desktop keeps the brightness
 * the owner set and simply sleeps when it is due (the owner, 1.74).
 */
object IdlePower {
    /**
     * The delay left in the settings after [forceSleep]: short enough that the next wake must undo
     * it, long enough that the undo has room to happen.
     */
    private const val SPENT_DELAY_MS = 15_000

    /** True while the settings hold [SPENT_DELAY_MS], so the next key puts the real delay back. */
    @Volatile
    private var spent = false

    /**
     * Writes the TV's screen-off delay so the system itself does the standby. Needs
     * WRITE_SETTINGS, which install.sh grants as an app-op; without it the picture still dims, and
     * the TV only sleeps when the owner presses the power key.
     */
    fun arm(context: Context) {
        spent = false
        val r = context.contentResolver
        // "Stay awake while charging" is always true on a TV — it is always on mains — and it makes
        // PowerManagerService hold the screen bright whatever the delay says (MiTV-NSOP0 shipped
        // with it at 1 and never slept). Turns the whole policy off, so it goes.
        val stayOn = runCatching { Settings.Global.getInt(r, Settings.Global.STAY_ON_WHILE_PLUGGED_IN) }.getOrDefault(0)
        if (stayOn != 0) {
            val cleared = runCatching { Settings.Global.putInt(r, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 0) }.isSuccess
            DiagLog.i("idle power: stay-awake was $stayOn, ${if (cleared) "cleared" else "could not be cleared"}")
        }
        val delay = AerialSettings.standbyAfterMs.toInt()
        val current = runCatching { Settings.System.getInt(r, Settings.System.SCREEN_OFF_TIMEOUT) }.getOrNull()
        if (current == delay) return
        val ok = runCatching { Settings.System.putInt(r, Settings.System.SCREEN_OFF_TIMEOUT, delay) }.isSuccess
        DiagLog.i("idle power: screen-off delay ${if (ok) "set to ${delay / 1000} s" else "refused (was $current)"}")
    }

    /** [arm], but only after a [forceSleep] left the settings spent. Called on every key. */
    fun armIfSpent(context: Context) {
        if (spent) arm(context)
    }

    /**
     * Puts the TV to standby now that its delay has run out.
     *
     * Releasing the screen on its own does not do it: PowerManagerService only looks at the delay
     * again when the setting is written, and with keep-screen-on gone it otherwise sat awake past
     * the timeout until the next key (MiTV-MFTP0, measured 2026-10-29 00:27 — 90 s past due, still
     * Awake). Writing a fresh, tiny delay makes it recalculate at once, find the minutes gone and
     * suspend; [arm] puts the real delay back on the next key.
     */
    fun forceSleep(context: Context) {
        spent = true
        val ok = runCatching {
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, SPENT_DELAY_MS)
        }.isSuccess
        DiagLog.i("idle power: standby now (delay spent: $ok)")
    }
}
