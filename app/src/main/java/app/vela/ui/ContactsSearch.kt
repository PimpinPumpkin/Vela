package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * "Search your contacts" toggle (issue #243, Settings → Search, OFF by default). When on, typing a
 * contact's name in the search box suggests their saved postal address (matched on-device against
 * [app.vela.data.ContactAddresses]; picking one searches the ADDRESS like any typed query — the
 * contact list itself never leaves the phone). READ_CONTACTS is asked at the point of use (flipping
 * this toggle on), never at install/onboarding. Same process-wide reactive holder shape as
 * [VoiceSearch]; init in VelaApp.
 */
object ContactsSearch {
    val enabled = mutableStateOf(false)

    fun init(context: Context) {
        enabled.value = prefs(context).getBoolean(KEY, false)
    }

    fun set(context: Context, on: Boolean) {
        enabled.value = on
        prefs(context).edit().putBoolean(KEY, on).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)

    private const val KEY = "contacts_search"
}
