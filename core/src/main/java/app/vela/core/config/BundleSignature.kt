package app.vela.core.config

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Verifies the detached signature on a remote bundle (calibration / transforms).
 * ECDSA-P256 / SHA-256 - exactly what `openssl dgst -sha256 -sign <ec.key>`
 * produces, so the dev-side signing step is a one-liner. Pure JVM crypto (no
 * Android types), so it's unit-testable.
 */
object BundleSignature {
    /** True iff [sigBase64] is a valid signature of [content] under [publicKeyB64]
     * (an EC P-256 SPKI public key, base64). Any error → false (reject). */
    fun verify(content: ByteArray, sigBase64: String, publicKeyB64: String): Boolean = runCatching {
        val pub = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyB64)))
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(pub)
            update(content)
            verify(Base64.getDecoder().decode(sigBase64.trim()))
        }
    }.getOrDefault(false)
}
