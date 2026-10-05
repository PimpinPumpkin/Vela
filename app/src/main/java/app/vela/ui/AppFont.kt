package app.vela.ui

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.font.FontFamily
import java.io.File

/**
 * The typeface Vela's own UI is drawn in (issue #252).
 *
 * **Google Sans itself cannot be shipped**: it is proprietary, not on Google Fonts, not licensed
 * for third-party use. Its open sibling CAN: Google Sans Flex was released under the SIL Open Font
 * License on 2025-11-18, and is bundled as a built-in choice ([setBuiltin], the variable TTF in
 * `res/font`, the license in `assets/licenses`). It covers Latin and Vietnamese only, so Cyrillic,
 * Greek, Hebrew and CJK text falls through to the system font glyph by glyph. Android also has no API to list the fonts a user has installed, and
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

    /** The bundled Google Sans Flex is the UI font. */
    val builtin = mutableStateOf(false)

    /** One Font per weight the type scale uses, each pinning the variable font's weight axis. */
    @OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
    private fun flexFamily(): FontFamily = FontFamily(
        listOf(400, 500, 600, 700).map { w ->
            androidx.compose.ui.text.font.Font(
                app.vela.R.font.google_sans_flex,
                weight = androidx.compose.ui.text.font.FontWeight(w),
                variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(
                    androidx.compose.ui.text.font.FontVariation.weight(w),
                ),
            )
        },
    )

    fun setBuiltin(context: Context) {
        family.value = flexFamily()
        builtin.value = true
        customName.value = null
        fontFile(context).delete()
        prefs(context).edit().remove(KEY_NAME).remove(KEY_SYSTEM).apply()
    }

    /** A font file is a few hundred KB; anything far past that is not a font we want to load. */
    private const val MAX_BYTES = 12L * 1024 * 1024

    /** Google Sans Flex unless the user chose otherwise (the default since 2026-09-30): a font
     *  file of their own, or the system font (an explicit pick, [KEY_SYSTEM]). */
    fun init(context: Context) {
        val p = prefs(context)
        val name = p.getString(KEY_NAME, null)
        if (name != null) {
            val f = fontFile(context)
            val fam = if (f.exists()) load(f) else null
            if (fam != null) { family.value = fam; customName.value = name; return }
            // Unreadable now (a corrupt copy): drop the choice and fall through to the default.
            f.delete(); p.edit().remove(KEY_NAME).apply()
        }
        if (p.getBoolean(KEY_SYSTEM, false)) return
        family.value = flexFamily(); builtin.value = true
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
        builtin.value = false
        prefs(context).edit().putString(KEY_NAME, displayName).remove(KEY_SYSTEM).apply()
        return true
    }

    /** The platform font, as an explicit choice (the default is the bundled font). */
    fun clear(context: Context) {
        family.value = null
        customName.value = null
        builtin.value = false
        fontFile(context).delete()
        prefs(context).edit().remove(KEY_NAME).putBoolean(KEY_SYSTEM, true).apply()
    }

    /**
     * null when the file is not a font the platform can parse. Compose's `Font(File)` does NOT
     * throw on bad data on API 26+: the platform builder returns null and Compose only finds out
     * at first draw, with an IllegalStateException in every screen that draws text. A PDF picked
     * by mistake was therefore adopted, persisted, re-adopted at the next launch, and the app
     * could not start until its data was cleared. So the platform builder is asked FIRST, on the
     * same file, and only a font it can actually build is wrapped for Compose.
     */
    private fun load(f: File): FontFamily? = runCatching {
        if (f.length() <= 0L) return null
        android.graphics.Typeface.Builder(f).build() ?: return null
        FontFamily(androidx.compose.ui.text.font.Font(f))
    }.getOrNull()

    private fun fontFile(c: Context) = File(c.filesDir, "fonts/ui.ttf")
    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY_NAME = "ui_font_name"
    private const val KEY_SYSTEM = "ui_font_system"
}
