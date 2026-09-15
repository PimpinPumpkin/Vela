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
        // Settings > Diagnostics > "Redact places in exports" (issue #507): the same file with
        // the searches, destinations, links and names gone, for a report the user wants public.
        val redact = context.getSharedPreferences("vela_settings", Context.MODE_PRIVATE).getBoolean(REDACT_PREF, false)

        val json = buildString {
            append("{\"app\":\"Vela\",\"schema\":1,\"exportedAt\":").append(System.currentTimeMillis())
            append(",\"version\":").append(quote("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"))
            append(",\"android\":").append(quote("API ${Build.VERSION.SDK_INT} — ${Build.MANUFACTURER} ${Build.MODEL}"))
            append(",\"note\":").append(quote(DiagScrub.note(redact)))
            append(",\"count\":").append(events.size).append(",\"events\":[")
            events.forEachIndexed { i, e ->
                if (i > 0) append(',')
                append("{\"t\":").append(e.epochMs)
                append(",\"kind\":").append(quote(e.kind))
                append(",\"summary\":").append(quote(DiagScrub.summary(e.summary, redact)))
                DiagScrub.detail(e.kind, e.detail, redact)?.let { d -> append(",\"detail\":").append(quote(d)) }
                append('}')
            }
            append("]}")
        }

        val dir = File(context.cacheDir, "diag").apply { mkdirs() }
        val file = File(dir, "vela-diag-${java.text.SimpleDateFormat("yyyy-MM-dd-HHmm", java.util.Locale.US).format(java.util.Date())}.json")
        file.writeText(json)

        return shareFileIntent(
            context, file,
            mime = "application/json",
            subject = "Vela debug session (${events.size} events)",
            text = "Attached: a Vela diagnostics export to help debug an issue.",
            title = "Share debug session",
        )
    }

    companion object {
        /** vela_settings boolean read at export time; the toggle lives in DiagnosticsSettings. */
        const val REDACT_PREF = "diag_redact"
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
