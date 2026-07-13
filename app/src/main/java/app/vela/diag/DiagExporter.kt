package app.vela.diag

import android.content.Context
import android.content.Intent
import android.os.Build
import app.vela.BuildConfig
import app.vela.core.diag.DiagLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns the opt-in [DiagLog] ring into a shareable bug-report bundle: a small
 * JSON file the user hands to a dev via the system share sheet (email, Signal,
 * Files…). **User-initiated and user-routed** — Vela never uploads it; the user
 * sees it's a file and chooses where it goes. This is the no-backend debugging
 * path from the telemetry plan; crowd-sourced upload is a later, separate phase.
 */
@Singleton
class DiagExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val diag: DiagLog,
) {
    /** A share [Intent] for the current session's breadcrumbs, or null if there's
     *  nothing recorded yet (caller can tell the user). */
    fun buildShareIntent(): Intent? {
        val events = diag.snapshot()
        if (events.isEmpty()) return null

        val json = buildString {
            append("{\"app\":\"Vela\",\"schema\":1,\"exportedAt\":").append(System.currentTimeMillis())
            append(",\"version\":").append(quote("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"))
            append(",\"android\":").append(quote("API ${Build.VERSION.SDK_INT} — ${Build.MANUFACTURER} ${Build.MODEL}"))
            append(",\"note\":").append(quote("coordinates rounded to ~1 km for privacy"))
            append(",\"count\":").append(events.size).append(",\"events\":[")
            events.forEachIndexed { i, e ->
                if (i > 0) append(',')
                append("{\"t\":").append(e.epochMs)
                append(",\"kind\":").append(quote(e.kind))
                append(",\"summary\":").append(quote(scrub(e.summary)))
                e.detail?.let { d -> append(",\"detail\":").append(quote(scrub(d))) }
                append('}')
            }
            append("]}")
        }

        val dir = File(context.cacheDir, "diag").apply { mkdirs() }
        val file = File(dir, "vela-diag-${System.currentTimeMillis()}.json")
        file.writeText(json)

        return shareFileIntent(
            context, file,
            mime = "application/json",
            subject = "Vela debug session (${events.size} events)",
            text = "Attached: a Vela diagnostics export to help debug an issue.",
            title = "Share debug session",
        )
    }

    /** Round coordinate-looking decimals to 2 places (~1 km) so the exported file is safe to post
     *  publicly - breadcrumbs carry raw lat/lng (search bias, off-route fixes, viewport boxes), which
     *  would otherwise pinpoint the reporter. ~1 km still tells a dev WHICH AREA a bug happened in
     *  without saying which building. Epochs/counts have no decimal point and zoom levels have only
     *  one decimal, so the 3+-decimals requirement can't touch them. */
    private val coordLike = Regex("""-?\d{1,3}\.\d{3,}""")
    private fun scrub(s: String): String = coordLike.replace(s) { m ->
        m.value.toDoubleOrNull()?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: m.value
    }

    private fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2).append('"')
        for (c in s) when (c) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        return sb.append('"').toString()
    }
}
