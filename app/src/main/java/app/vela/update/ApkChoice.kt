package app.vela.update

/**
 * Which APK on a release fits this phone (2026-09-23). A release carries either one all-in-one
 * APK (every build before the per-chip split, and the fallback after it) or one APK per chip type
 * named `<prefix>-<tag>.apk` next to `<prefix>-all.apk`. The all-in-one name sorts FIRST on
 * purpose: GitHub lists release assets alphabetically and updaters older than this file take the
 * first `.apk`, so they keep installing something that runs on every phone.
 */
object ApkChoice {
    /** Android ABI name to the tag in the file name, in the digit order of the versionCode. */
    val TAGS = linkedMapOf(
        "armeabi-v7a" to "armv7",
        "arm64-v8a" to "arm64",
        "x86" to "x86",
        "x86_64" to "x86_64",
    )

    /** The asset to download from [names], trying [supportedAbis] in the phone's own order
     *  (`Build.SUPPORTED_ABIS`), then the all-in-one APK, then null when there is no APK. */
    fun pick(names: List<String>, supportedAbis: List<String>): String? {
        val apks = names.filter { it.endsWith(".apk") }
        for (abi in supportedAbis) {
            val tag = TAGS[abi] ?: continue
            apks.firstOrNull { it.endsWith("-$tag.apk") }?.let { return it }
        }
        return apks.firstOrNull { name -> TAGS.values.none { name.endsWith("-$it.apk") } }
    }
}

/** A versionCode folded onto the legacy `2000 + run` scale: codes from the per-chip scheme,
 *  `(2000 + run) * 10 + digit`, are 20000 and up; anything below is already legacy (or a local
 *  dev build, kept under 1000). */
fun legacyCode(versionCode: Int): Int = if (versionCode >= 20000) versionCode / 10 else versionCode

/**
 * The app's release tags out of a tag-refs listing, as run number to tag, newest run first. The
 * tag is kept as GitHub names it, so a release is fetched by its real tag and a new version line
 * (`v0.5.<run>`) needs no change here. A run that appears under two lines keeps the higher one.
 */
fun appReleaseTags(refs: List<String>): List<Pair<Int, String>> =
    refs.mapNotNull { ref ->
        val m = Regex("""^refs/tags/(v0\.(\d+)\.(\d+))$""").find(ref) ?: return@mapNotNull null
        Triple(m.groupValues[3].toIntOrNull() ?: return@mapNotNull null, m.groupValues[2].toIntOrNull() ?: 0, m.groupValues[1])
    }
        .sortedWith(compareByDescending<Triple<Int, Int, String>> { it.first }.thenByDescending { it.second })
        .distinctBy { it.first }
        .map { it.first to it.third }

