package app.vela.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/**
 * On-device contact → postal-address lookup for search suggestions (issue #243). The whole set of
 * address-bearing contacts is loaded ONCE into memory (they're rare — most contacts carry no postal
 * row — so this is dozens of entries, not thousands) and the per-keystroke match runs against that
 * cache: the search page's local suggestions are computed synchronously on the main thread, and a
 * ContactsProvider binder query per keystroke would be exactly the blocking-IPC-in-composition class
 * of jank the Settings voice-engine list already hit. Load/reload happens off the main thread (VM
 * init + the Settings toggle flipping on). Nothing here ever leaves the phone; picking a suggestion
 * searches the ADDRESS string like any typed query.
 */
object ContactAddresses {
    /** One postal row of one contact. [type] is the address book's own label for the row ("Home",
     *  "Work", or a custom one), already localized by the platform; [photoUri] is the contact's
     *  thumbnail, a content: URI that only resolves while the permission is held. */
    data class Entry(val name: String, val address: String, val type: String? = null, val photoUri: String? = null)

    @Volatile private var cache: List<Entry> = emptyList()
    @Volatile private var loaded = false

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /** (Re)query the contacts provider. Call off the main thread. Missing permission → empty. */
    fun reload(context: Context) {
        if (!hasPermission(context)) {
            cache = emptyList(); loaded = true; return
        }
        val out = ArrayList<Entry>()
        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.StructuredPostal.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS,
                    ContactsContract.CommonDataKinds.StructuredPostal.TYPE,
                    ContactsContract.CommonDataKinds.StructuredPostal.LABEL,
                    ContactsContract.CommonDataKinds.StructuredPostal.PHOTO_THUMBNAIL_URI,
                ),
                null, null,
                ContactsContract.CommonDataKinds.StructuredPostal.DISPLAY_NAME,
            )?.use { c ->
                // 500 = a runaway-provider backstop, far above any real address book.
                while (c.moveToNext() && out.size < 500) {
                    val name = c.getString(0)?.trim().orEmpty()
                    val addr = c.getString(1)?.replace('\n', ' ')?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
                    // The row's own label, in the platform's words (so "Home" is already "Casa" on
                    // a Spanish phone); a custom label comes through as typed.
                    val type = if (c.isNull(2)) null else runCatching {
                        ContactsContract.CommonDataKinds.StructuredPostal.getTypeLabel(
                            context.resources, c.getInt(2), c.getString(3),
                        ).toString().trim().takeIf { it.isNotEmpty() }
                    }.getOrNull()
                    val photo = c.getString(4)?.takeIf { it.isNotBlank() }
                    if (name.isNotEmpty() && addr.isNotEmpty()) out.add(Entry(name, addr, type, photo))
                }
            }
        }
        cache = out.distinct()
        loaded = true
    }

    fun ensureLoaded(context: Context) {
        if (!loaded) reload(context)
    }

    /** Contacts whose NAME contains [term], case-insensitive, against the in-memory cache. */
    fun matches(term: String, limit: Int = 3): List<Entry> {
        if (term.isBlank()) return emptyList()
        val t = term.lowercase()
        return cache.asSequence().filter { it.name.lowercase().contains(t) }.take(limit).toList()
    }
}
