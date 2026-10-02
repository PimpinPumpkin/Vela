package app.vela.update

import java.io.File
import java.security.MessageDigest

/** Is a downloaded update the file the release says it is? */
internal object ApkCheck {
    /** GitHub's asset `digest` ("sha256:<hex>") as bare lowercase hex, or null for anything else. */
    fun digestOf(digest: String?): String? =
        digest?.trim()?.lowercase()?.removePrefix("sha256:")?.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { s ->
            val buf = ByteArray(256 * 1024)
            while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * What is wrong with [file] as the update, or null when it checks out: it is a zip (an APK
     * is one), it is [expectBytes] long when the release gives a size, and its SHA-256 is
     * [expectSha256] when the release gives one. The hash is read last, it is the slow one.
     */
    fun problem(file: File, expectBytes: Long, expectSha256: String?): String? {
        if (!file.exists() || file.length() <= 4) return "empty"
        val magic = ByteArray(2).also { m -> file.inputStream().use { it.read(m) } }
        if (magic[0] != 'P'.code.toByte() || magic[1] != 'K'.code.toByte()) return "not an APK"
        if (expectBytes > 0 && file.length() != expectBytes) return "size ${file.length()}, the release says $expectBytes"
        if (expectSha256 != null && sha256(file) != expectSha256) return "checksum does not match the release"
        return null
    }
}
