package app.orchard.tv.system

import android.content.Context
import android.content.Intent

/**
 * The strip on the back of the set: Xiaomi's 幻彩随影, driven by `com.xiaomi.mitv.light` over the
 * CH340 USB-serial adapter that shows up as "USB Serial" (vid 0x1a86, pid 0x7523).
 *
 * That app already knows when to put the strip out — its receiver listens for
 * `mitv.action.SCREEN_TURN_OFF` and `mitv.action.SCREEN_TURN_ON` and pauses or resumes the flow on
 * them (turning `set_available` and `set_tv_power` over the serial link). On this firmware the
 * firmware's own delay does not reach the light, and Aerial puts the set to standby by writing that
 * delay (see [IdlePower.forceSleep]), so the strip stayed lit behind a dark panel (MiTV-MFTP0).
 *
 * Sending the same two broadcasts from here is the whole fix: no root, no USB permission, no
 * protocol of our own. Nothing is sent when nothing changes, so twenty key presses are one
 * broadcast.
 */
object TvLight {
    private const val PKG = "com.xiaomi.mitv.light"
    private const val TURN_OFF = "mitv.action.SCREEN_TURN_OFF"
    private const val TURN_ON = "mitv.action.SCREEN_TURN_ON"

    /** What the strip was last told. It comes up lit, so the first standby is the one that sends. */
    @Volatile
    private var out = false

    /**
     * Puts the strip out ([off] true) or brings it back, at most once per change. [force] sends
     * either way, for the ADB channel and for checking the link from a computer.
     */
    fun put(context: Context, off: Boolean, force: Boolean = false) {
        if (!force && out == off) return
        out = off
        val app = context.applicationContext
        val sent = runCatching { app.sendBroadcast(Intent(if (off) TURN_OFF else TURN_ON).setPackage(PKG)) }.isSuccess
        DiagLog.i("led: ${if (off) "out" else "lit"} ${if (sent) "told to $PKG" else "could not be sent"}")
    }
}
