package app.orchard.tv.system

import android.content.Context
import java.lang.reflect.Method

/**
 * The panel backlight on MiTV firmware whose TvService transactions answer nothing (MiTV-MFTP0
 * `alita`, Android 9: `getBacklightStatus` through transaction 43 returns -1, so [MitvPanel] can
 * never verify itself there).
 *
 * The TV's own settings app (com.xiaomi.mitv.settings, MiTVSettings3) moves the backlight through
 * `mitv.display.PictureSettingsManager`, a class from the system's shared library
 * `com.xiaomi.mitv.api` (/system/framework/MitvAPIImpl.jar, whose classes ship in
 * mitvmiddlewareimpl.jar). The manifest declares that library, so the class is on this app's class
 * loader; it is reached by reflection because the library's stub jar is empty and cannot be
 * compiled against.
 *
 * Nothing here is trusted by name alone: the manager is found through the factory shapes the
 * firmware could offer, and the backlight getter and setter are picked by shape (no argument
 * returning an int; one int argument) from the methods that mention "backlight". A pair is logged
 * through [DiagLog] either way — this was written from a firmware's API it could not be run
 * against, and the first run's log is what says whether the guess held.
 */
object MitvPicture {
    private const val CLASS = "mitv.display.PictureSettingsManager"

    private var tried = false
    private var manager: Any? = null
    private var getter: Method? = null
    private var setter: Method? = null

    private fun resolve(context: Context): Boolean {
        if (tried) return getter != null
        tried = true
        val cls = runCatching { Class.forName(CLASS) }
            .recoverCatching { Class.forName(CLASS, false, frameworkLoader(context)) }
            .getOrElse {
                DiagLog.i("mitv picture: $CLASS not on this TV")
                return false
            }
        val app = context.applicationContext
        var madeAs = ""
        manager = runCatching { cls.getMethod("getInstance", Context::class.java).invoke(null, app).also { madeAs = "getInstance(Context)" } }
            .recoverCatching { cls.getMethod("getInstance").invoke(null).also { madeAs = "getInstance()" } }
            .recoverCatching { cls.getConstructor(Context::class.java).newInstance(app).also { madeAs = "new(Context)" } }
            .recoverCatching { cls.getConstructor().newInstance().also { madeAs = "new()" } }
            .getOrNull()
        if (manager != null) DiagLog.i("mitv picture: manager by $madeAs, class ${manager!!::class.java.name}")
        val all = runCatching { cls.methods.toList() }.getOrDefault(emptyList())
        val backlight = all.filter { it.name.contains("acklight") || it.name.contains("ackLight") }
        if (manager == null) {
            DiagLog.i(
                "mitv picture: no instance; backlight methods " +
                    backlight.joinToString { "${it.name}/${it.parameterTypes.size}" },
            )
            return false
        }
        // getBacklight()/setBacklight(int) by name first: the manager also carries projector,
        // temporary and safety variants, which must not be taken for the panel's own level.
        fun intReturn(m: Method) = m.returnType == Int::class.javaPrimitiveType || m.returnType == Integer::class.java
        fun intArg(m: Method) = m.parameterCount == 1 &&
            (m.parameterTypes[0] == Int::class.javaPrimitiveType || m.parameterTypes[0] == Integer::class.java)
        getter = backlight.firstOrNull { it.parameterCount == 0 && intReturn(it) && it.name.equals("getBacklight", true) }
            ?: backlight.firstOrNull {
                it.parameterCount == 0 && intReturn(it) &&
                    !it.name.contains("Projector", true) && !it.name.contains("Mode", true)
            }
        setter = backlight.firstOrNull { intArg(it) && it.name.equals("setBacklight", true) }
            ?: backlight.firstOrNull {
                intArg(it) && !it.name.contains("Projector", true) && !it.name.contains("Mode", true) &&
                    !it.name.contains("temp", true) && !it.name.contains("Max", true) && !it.name.contains("Safety", true)
            }
        DiagLog.i(
            "mitv picture: " + backlight.joinToString { m ->
                m.name + "(" + m.parameterTypes.joinToString(",") { it.simpleName } + "):" + m.returnType.simpleName
            } + " -> getter=${getter?.name} setter=${setter?.name}",
        )
        return getter != null
    }

    /**
     * The system's mitv jars as a class loader of their own. The manifest's optional
     * `<uses-library>` for `com.xiaomi.mitv.api` is listed by the package manager yet resolves to
     * no file (usesLibraryFiles stays empty on MiTV-MFTP0), so the class is not on this app's
     * loader; both jars sit readable in /system/framework, and loading them here is the same
     * classes by another road.
     */
    private var loader: ClassLoader? = null

    private fun frameworkLoader(context: Context): ClassLoader? {
        loader?.let { return it }
        val files = listOf("mitvmiddlewareimpl.jar", "MitvAPIImpl.jar", "mitvmiddleware.jar")
            .map { java.io.File("/system/framework/$it") }
            .filter { it.exists() }
        if (files.isEmpty()) return null
        loader = runCatching {
            dalvik.system.DexClassLoader(
                files.joinToString(java.io.File.pathSeparator) { it.path },
                null,
                null,
                context.classLoader,
            )
        }.onFailure { DiagLog.i("mitv picture: loader failed: $it") }.getOrNull()
        return loader
    }

    /** Panel level 1–100, or null when this TV has no such class or no getter to read it with. */
    fun level(context: Context): Int? {
        if (!resolve(context)) return null
        val call = runCatching { getter?.invoke(manager) as? Number }
        val raw = call.getOrNull()?.toInt()
        if (!readLogged) {
            readLogged = true
            val cause = (call.exceptionOrNull() as? java.lang.reflect.InvocationTargetException)?.targetException
                ?: call.exceptionOrNull()
            DiagLog.i(
                "mitv picture: panel reads " + (raw ?: "nothing") +
                    (cause?.let {
                        " (${it::class.java.name}: ${it.message}) " +
                            it.stackTrace.take(3).joinToString(" < ") { f -> "${f.className}.${f.methodName}:${f.lineNumber}" }
                    } ?: ""),
            )
        }
        return raw?.takeIf { it in 1..100 }
    }

    private var readLogged = false

    /** Puts the panel at [percent]; false when there is no setter or the firmware refuses it. */
    fun set(context: Context, percent: Int): Boolean {
        if (!resolve(context)) return false
        val m = setter ?: return false
        return runCatching { m.invoke(manager, percent.coerceIn(1, 100)) }.isSuccess
    }
}
