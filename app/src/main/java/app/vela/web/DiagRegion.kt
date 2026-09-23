package app.vela.web

import android.content.Context
import android.telephony.TelephonyManager
import java.util.Locale

/** Where Google is likely to think the phone is, for diagnostics lines: the cell network's country,
 *  else the SIM's, else the locale's (the same order the scrape's gl= bias uses). A two-letter code,
 *  never a coordinate, so a report says which country without anyone asking for a location. */
object DiagRegion {
    fun of(ctx: Context): String = (runCatching {
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        tm?.networkCountryIso?.takeIf { it.isNotBlank() } ?: tm?.simCountryIso?.takeIf { it.isNotBlank() }
    }.getOrNull() ?: Locale.getDefault().country).uppercase()
}
