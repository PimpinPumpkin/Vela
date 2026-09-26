package app.vela.diag

import android.content.Context
import android.os.Handler
import android.os.Looper
import app.vela.core.net.GoogleUsage
import org.json.JSONObject
import java.time.LocalDate

/**
 * Keeps [GoogleUsage]'s per-day counts on the phone across restarts (`google_usage` in its own
 * prefs file, [GoogleUsage.KEEP_DAYS] days) and summarizes them for Settings > Privacy and the
 * diagnostics export. Nothing here leaves the phone.
 */
object GoogleUsageStore {
    private const val PREFS = "vela_google_usage"
    private const val KEY = "days"
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var pending = false

    fun init(context: Context) {
        val app = context.applicationContext
        runCatching {
            val o = JSONObject(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "{}") ?: "{}")
            val saved = o.keys().asSequence().associateWith { day ->
                val k = o.getJSONObject(day)
                k.keys().asSequence().associateWith { k.getInt(it) }
            }
            GoogleUsage.restore(saved)
        }
        // Saved at most every 15 s: a busy map settle can make dozens of requests in a second.
        GoogleUsage.onChange = {
            if (!pending) {
                pending = true
                main.postDelayed({ pending = false; save(app) }, 15_000)
            }
        }
    }

    private fun save(context: Context) {
        val o = JSONObject()
        GoogleUsage.snapshot().forEach { (day, kinds) -> o.put(day, JSONObject(kinds)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, o.toString()).apply()
    }

    /** Today's counts by kind, largest first. */
    fun today(): List<Pair<String, Int>> =
        (GoogleUsage.snapshot()[GoogleUsage.today()] ?: emptyMap()).entries.sortedByDescending { it.value }.map { it.key to it.value }

    /** The last 7 days (today included) summed by kind, largest first. */
    fun week(): List<Pair<String, Int>> {
        val from = LocalDate.now().minusDays(6).toString()
        val sum = HashMap<String, Int>()
        GoogleUsage.snapshot().filterKeys { it >= from }.values.forEach { k -> k.forEach { (kind, n) -> sum[kind] = (sum[kind] ?: 0) + n } }
        return sum.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }

    /** Average requests per day over the days in the last week that have any. */
    fun weekDailyAverage(): Int {
        val from = LocalDate.now().minusDays(6).toString()
        val totals = GoogleUsage.snapshot().filterKeys { it >= from }.values.map { it.values.sum() }.filter { it > 0 }
        return if (totals.isEmpty()) 0 else totals.sum() / totals.size
    }

    /** The whole kept history as JSON, for the diagnostics export (counts only, no URLs). */
    fun exportJson(): String {
        val o = JSONObject()
        GoogleUsage.snapshot().forEach { (day, kinds) -> o.put(day, JSONObject(kinds)) }
        return o.toString()
    }
}
