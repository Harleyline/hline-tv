package app.orchard.tv.system

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * Whether this TV is one of the slow ones, for the Effects setting's Auto (AerialSettings.lite).
 * Read from what the system says about itself, not from a list of models:
 * - the firmware's own low-RAM flag (`ro.config.low_ram`): Xiaomi MiTV-MFTP0, Konka U86V9, Coocaa
 *   C10 and OPPO K9 set it; the system already trims itself there;
 * - under 1.8 GB of memory: the 1.5-1.7 GB Xiaomi MiTV4 / ASTP0 boards and old boxes;
 * - Android 6 and older: these are all several years old (Xiaomi MiTV4, Letv, Konka, Whaley);
 * - two cores or fewer.
 * TVs with 2-4 GB on Android 9-14 (Xiaomi MFTR0, TCL MT5879, Hisense) are not counted.
 */
object DeviceClass {
    @Volatile private var known: Boolean? = null
    @Volatile private var fast: Boolean? = null

    fun lowEnd(context: Context): Boolean = known ?: run {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        val mb = info.totalMem / (1 shl 20)
        (am.isLowRamDevice || mb < 1800 || Build.VERSION.SDK_INT < 24 || Runtime.getRuntime().availableProcessors() <= 2)
            .also { known = it }
    }

    /**
     * Whether this TV has the headroom for the most expensive thing Aerial can ask of it: running
     * Home at twice the refresh rate (MainActivity.preferSmoothestRefreshRate). A 120 Hz panel on a
     * two-core box drops every other frame instead of looking smoother, so the rate is only raised
     * where the GPU can keep the budget: not a low-end set, at least 2 GB, four cores and OpenGL ES 3
     * (the glass and the tile's light need it; TV GPUs without it are the ones Aerial draws Lite for).
     * Read from the system, like [lowEnd], not from a list of models.
     */
    fun capable(context: Context): Boolean = fast ?: run {
        if (lowEnd(context)) return false.also { fast = it }
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mb = ActivityManager.MemoryInfo().also(am::getMemoryInfo).totalMem / (1 shl 20)
        val gles = runCatching { am.deviceConfigurationInfo.reqGlEsVersion }.getOrDefault(0)
        val cores = Runtime.getRuntime().availableProcessors()
        (mb >= 2048 && cores >= 4 && gles >= 0x30000).also { fast = it }
    }

    /** One line for diagnostics: what the check saw. */
    fun describe(context: Context): String {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        val gles = runCatching { am.deviceConfigurationInfo.reqGlEsVersion }.getOrDefault(0)
        return "low-end=${lowEnd(context)} capable=${capable(context)} " +
            "(low-RAM flag ${am.isLowRamDevice}, ${info.totalMem / (1 shl 20)} MB, " +
            "${Runtime.getRuntime().availableProcessors()} cores, GLES ${gles shr 16}.${(gles shr 8) and 0xFF}, " +
            "memory class ${am.memoryClass} MB)"
    }
}
