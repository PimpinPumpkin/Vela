package app.vela.voice

import android.content.Context
import java.io.File

/**
 * The English dictionary Vela's Piper voices read with: eSpeak NG's own plus about 3,500 place
 * names said the local way (collected from English Wikipedia for the Alyo reader, CC BY-SA 4.0;
 * built by `scripts/build-espeak-dict.sh` from `tools/pronunciation/places.tsv`). It replaces the
 * `en_dict` inside an English voice's `espeak-ng-data`, once per voice and again whenever the
 * dictionary in the app changes. The voice's own file is kept beside it as `en_dict.stock`, and
 * the dial `placeDictionary` (0) puts it back.
 */
object PlaceDictionary {
    private const val ASSET = "pronunciation/en_dict"
    private const val MARKER = "en_dict.vela"
    private const val STOCK = "en_dict.stock"

    /** [dataDir] is the voice's `espeak-ng-data` folder. Call before the engine loads it. */
    fun install(context: Context, voiceId: String, dataDir: String) {
        if (!voiceId.startsWith("en_")) return
        val data = File(dataDir).takeIf { it.isDirectory } ?: return
        val dict = File(data, "en_dict")
        val stock = File(data, STOCK)
        val marker = File(data, MARKER)
        try {
            if (app.vela.ui.AppTune.value("placeDictionary", 1.0) < 0.5) {
                if (marker.exists() && stock.exists()) { stock.copyTo(dict, overwrite = true); marker.delete() }
                return
            }
            val version = context.assets.open("$ASSET.version").bufferedReader().use { it.readText().trim() }
            if (marker.exists() && marker.readText() == version) return
            if (!stock.exists() && dict.exists()) dict.copyTo(stock)
            val fresh = File(data, "en_dict.new")
            context.assets.open(ASSET).use { input -> fresh.outputStream().use { input.copyTo(it) } }
            if (fresh.renameTo(dict)) marker.writeText(version)
            android.util.Log.i("PiperSynth", "place-name dictionary $version installed for $voiceId")
        } catch (e: java.io.IOException) {
            // The voice still speaks, with the dictionary it came with.
            android.util.Log.w("PiperSynth", "place-name dictionary not installed: ${e.message}")
        }
    }
}
