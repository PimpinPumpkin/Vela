package app.vela.ui

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.font.FontFamily
import java.io.File

/**
 * The typeface Vela's own UI is drawn in (issue #252).
 *
 * **Vela ships no branded font and cannot.** The font people usually mean here is Google Sans,
 * which is proprietary: not on Google Fonts, not licensed for third-party use, and impossible to
 * redistribute in a GPLv3 app. Android also has no API to list the fonts a user has installed, and
 * the download-a-font-on-demand mechanism is served by Play Services, which our users do not have.
 *
 * What IS possible, and is what this does: the user hands us a font file from their own storage and
 * we render with it. Vela never distributes it, never fetches it, and never uploads it - the file is
 * copied into app storage and read locally. Whatever someone is licensed to have on their own
 * device, they can use here.
 *
 * **This changes the app's UI text only, NOT map labels.** Those are drawn by MapLibre from
 * pre-generated signed-distance-field glyph atlases (see MapFonts - the Roboto-over-Noto set on
 * Pages), not from a system typeface: turning an arbitrary TTF into those atlases means rendering
 * every glyph range on-device, which is a different project entirely. So a custom font restyles the
 * sheets, search, settings and nav cards; the street names on the map stay as they are.
 */
object AppFont {

    /** The family to draw UI text in, or null for the platform default. Read in the theme. */
    val family = mutableStateOf<FontFamily?>(null)

    /** File name the user picked, for display. Null when on the system font. */
    val customName = mutableStateOf<String?>(null)

    /** A font file is a few hundred KB; anything far past that is not a font we want to load. */
    private const val MAX_BYTES = 12L * 1024 * 1024

    fun init(context: Context) {
        val name = prefs(context).getString(KEY_NAME, null) ?: return
        val f = fontFile(context)
        if (!f.exists()) { clear(context); return }
        val fam = load(f)
        if (fam == null) { clear(context); return } // unreadable now (corrupt copy) - fall back quietly
        family.value = fam
        customName.value = name
    }

    /**
     * Adopt the font at [uri]. Returns false when it is not a usable font file, in which case
     * nothing changes - the file is validated by actually LOADING it before it is adopted, because
     * a rejected font that silently leaves the app in the system face reads as "the button did
     * nothing", and a corrupt one adopted blind would render every screen in the fallback face.
     */
    fun setCustom(context: Context, uri: Uri, displayName: String): Boolean {
        val tmp = File(context.filesDir, "fonts/incoming.tmp")
        tmp.parentFile?.mkdirs()
        val copied = runCatching {
            context.contentResolver.openInputStream(uri).use { input ->
                if (input == null) return false
                var total = 0L
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        total += n
                        if (total > MAX_BYTES) return@runCatching false
                        out.write(buf, 0, n)
                    }
                }
                total > 0
            }
        }.getOrDefault(false)
        if (!copied) { tmp.delete(); return false }
        val fam = load(tmp)
        if (fam == null) { tmp.delete(); return false }
        val dest = fontFile(context)
        dest.delete()
        if (!tmp.renameTo(dest)) { tmp.delete(); return false }
        family.value = fam
        customName.value = displayName
        prefs(context).edit().putString(KEY_NAME, displayName).apply()
        return true
    }

    /** Back to the platform font. */
    fun clear(context: Context) {
        family.value = null
        customName.value = null
        fontFile(context).delete()
        prefs(context).edit().remove(KEY_NAME).apply()
    }

    /** null when the file is not a font the platform can parse. */
    private fun load(f: File): FontFamily? = runCatching {
        FontFamily(androidx.compose.ui.text.font.Font(f))
    }.getOrNull()

    private fun fontFile(c: Context) = File(c.filesDir, "fonts/ui.ttf")
    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY_NAME = "ui_font_name"
}
