package app.orchard.tv.system

import android.content.Context
import android.os.Build
import app.orchard.tv.BuildConfig
import app.orchard.tv.settings.AerialSettings
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * The two things Aerial sends out, and nothing else:
 *
 * - an **install count**: one line per app version, per TV, so the owner knows how many sets run
 *   Aerial and which brands they are. A random name made on first start stands for the TV; the
 *   brand, model, Android version and Aerial version go with it.
 * - a **feedback log**, only when someone chooses Send Feedback Log in Personalize: the same line
 *   plus the diagnostics and log that [Diagnostics.report] puts together (addresses already masked).
 *
 * Nothing else leaves the TV, and nothing leaves it at all unless two things are true: an address
 * was given at build time (BuildConfig.REPORT_URL, from local/report.properties or -PreportUrl) and
 * the owner said yes when Personalize first asked. Until then this object makes no request.
 *
 * The address is the owner's own: a Worker of his (tools/report-worker: counts into KV, logs into
 * GitHub issues), a Feishu or WeCom group bot, or anything that takes a JSON POST. No third-party
 * SDK is involved — this is HttpURLConnection and org.json, as the weather and the updater are.
 */
object UsageReport {

    /** Anything there to send to at all. A build without an address is silent by construction. */
    val configured: Boolean get() = BuildConfig.REPORT_URL.isNotBlank()

    const val KIND_INSTALL = "install"
    const val KIND_FEEDBACK = "feedback"

    /** A GitHub issue's body limit; longer logs are cut, not refused. */
    private const val MAX_REPORT = 40_000

    private const val PREFS = "report"
    private const val KEY_ID = "install_id"
    private const val KEY_SENT = "sent_version"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * A random name for this TV, made once and never derived from anything about it: it only lets
     * the same set be counted once, not told apart from another.
     */
    fun installId(context: Context): String {
        prefs(context).getString(KEY_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs(context).edit().putString(KEY_ID, id).apply()
        return id
    }

    /**
     * Sends this version's install line, once, if the owner allowed it. Called at start: it takes a
     * thread of its own and says nothing on failure — the next start tries again.
     */
    fun start(context: Context) {
        if (!configured || AerialSettings.reportConsent != true) return
        val version = "${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
        val app = context.applicationContext
        val p = prefs(app)
        if (p.getBoolean("sent_$version", false)) return
        Thread({
            val sent = runCatching { send(app, KIND_INSTALL, null) }.isSuccess
            DiagLog.i("report: install ${if (sent) "sent" else "not sent"} $version")
            if (sent) p.edit().putBoolean("sent_$version", true).putString(KEY_SENT, version).apply()
        }, "AerialReport").apply { isDaemon = true }.start()
    }

    /** What came of the owner's press on Send Feedback Log. */
    sealed interface Sent {
        /** The other end took it; [link] is what it said it made of it, when it says anything. */
        data class Ok(val link: String?) : Sent
        /** No address in this build: there is nowhere to send to. */
        data object Off : Sent
        /** The TV is offline, or the other end refused it. */
        data object Failed : Sent
    }

    /** Uploads the diagnostics and log. Blocking: call it off the main thread. */
    fun upload(context: Context): Sent = runCatching {
        if (!configured) return Sent.Off
        val answer = send(context, KIND_FEEDBACK, Diagnostics.report(context))
        val link = runCatching { JSONObject(answer).optString("url").takeIf { it.isNotBlank() } }.getOrNull()
        Sent.Ok(link)
    }.getOrElse {
        DiagLog.e("report: feedback failed", it)
        Sent.Failed
    }

    private fun send(context: Context, kind: String, report: String?): String {
        val url = BuildConfig.REPORT_URL.trim().trimEnd('/')
        val body = if (isBot(url)) botBody(context, kind, report) else apiBody(context, kind, report)
        return post(if (isBot(url)) url else "$url/v1/$kind", body)
    }

    /** A group bot (Feishu, WeCom, DingTalk) takes a line of text, not fields. */
    private fun isBot(url: String) =
        url.contains("feishu.cn") || url.contains("qyapi.weixin.qq.com") || url.contains("dingtalk.com")

    private fun botBody(context: Context, kind: String, report: String?): String {
        val text = if (kind == KIND_INSTALL) {
            "HLine TV 安装 · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} " +
                "· ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.FLAVOR} · id ${installId(context)}"
        } else {
            "HLine TV 反馈 · ${Build.MANUFACTURER} ${Build.MODEL} · ${BuildConfig.VERSION_NAME}\n\n" +
                (report ?: "").take(MAX_REPORT)
        }
        return JSONObject()
            .put("msg_type", "text")
            .put("content", JSONObject().put("text", text))
            .toString()
    }

    private fun apiBody(context: Context, kind: String, report: String?): String {
        val body = JSONObject()
            .put("kind", kind)
            .put("id", installId(context))
            .put("brand", Build.BRAND.orEmpty())
            .put("manufacturer", Build.MANUFACTURER.orEmpty())
            .put("model", Build.MODEL.orEmpty())
            .put("device", Build.DEVICE.orEmpty())
            .put("sdk", Build.VERSION.SDK_INT)
            .put("android", Build.VERSION.RELEASE.orEmpty())
            .put("version", BuildConfig.VERSION_NAME)
            .put("code", BuildConfig.VERSION_CODE)
            .put("flavor", BuildConfig.FLAVOR)
            .put("lang", AerialSettings.language.tag)
        if (report != null) {
            body.put("report", report.take(MAX_REPORT))
            if (report.length > MAX_REPORT) body.put("truncated", true)
        }
        return body.toString()
    }

    private fun post(url: String, body: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 8_000
        c.readTimeout = 25_000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        c.setRequestProperty("User-Agent", "HLineTV/${BuildConfig.VERSION_NAME}")
        if (BuildConfig.REPORT_KEY.isNotBlank()) c.setRequestProperty("X-Report-Key", BuildConfig.REPORT_KEY)
        return try {
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code !in 200..299) error("HTTP $code")
            c.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            c.disconnect()
        }
    }
}
