package app.vela.offline

import java.io.File

/**
 * The GraphHopper graphs installed before the obf cutover (`filesDir/graphs/<id>/`, retired
 * 2026-09-15): nothing reads them anymore, so the first launch after the update reclaims the
 * space and tells the user to download their regions again from Settings > Offline maps.
 */
object LegacyGraphs {
    /** Delete the old graph tree; returns the region ids it held (empty when there was none). */
    fun purge(filesDir: File): List<String> {
        val root = File(filesDir, "graphs")
        if (!root.exists()) return emptyList()
        val ids = root.listFiles()?.filter { it.isDirectory && File(it, "properties").exists() }?.map { it.name }.orEmpty()
        runCatching { root.deleteRecursively() }
        return ids
    }
}
