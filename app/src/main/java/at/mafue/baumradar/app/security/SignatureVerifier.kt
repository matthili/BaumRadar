package at.mafue.baumradar.app.security

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.File
import java.io.FileInputStream
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Security
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import android.util.Base64

/**
 * Verifiziert die kryptographische Integrität heruntergeladener Datenbank-Dateien.
 *
 * Verwendet Ed25519-Signaturen über BouncyCastle. Ed25519 wurde gewählt, weil:
 * - Kompakte Schlüssel und Signaturen (32 bzw. 64 Bytes)
 * - Schnelle Verifizierung, ideal für mobile Geräte
 * - Keine Parameter-Wahl nötig (im Gegensatz zu RSA oder ECDSA)
 *
 * BouncyCastle wird als expliziter Security-Provider eingebunden, da die
 * Android-interne JCE-Implementierung Ed25519 erst ab API 33 unterstützt.
 */
object SignatureVerifier {

    init {
        // BouncyCastle muss als Provider explizit registriert werden.
        // removeProvider() + addProvider() stellt sicher, dass keine alte Version
        // kollidiert (kann bei manchen Android-ROMs vorkommen).
        Security.removeProvider("BC")
        Security.addProvider(BouncyCastleProvider())
    }

    /**
     * Verifiziert eine Datei gegen ihre Signaturdatei mit dem gegebenen öffentlichen Schlüssel.
     *
     * Die Datei wird in 8-KB-Blöcken gelesen (Streaming), um den Speicherverbrauch
     * bei großen Datenbanken gering zu halten. Die Signatur-Bytes werden komplett
     * in den Speicher geladen (typischerweise nur 64 Bytes bei Ed25519).
     *
     * @param dataFile       Die zu verifizierende Datei (z. B. die komprimierte Datenbank)
     * @param sigFile        Die Datei mit der Ed25519-Signatur (Raw-Bytes)
     * @param publicKeyBase64 Der öffentliche Schlüssel als Base64-kodierter X.509-SubjectPublicKeyInfo
     * @return true, wenn die Signatur gültig ist; false bei ungültiger Signatur oder Fehler
     */
    fun verifyFile(
        dataFile: File,
        sigFile: File,
        publicKeyBase64: String
    ): Boolean {
        return try {
            val decodedKey = Base64.decode(publicKeyBase64.trim(), Base64.DEFAULT)
            val keySpec = X509EncodedKeySpec(decodedKey)
            val keyFactory = KeyFactory.getInstance("Ed25519", "BC")
            val publicKey: PublicKey = keyFactory.generatePublic(keySpec)

            val sig = Signature.getInstance("Ed25519", "BC")
            sig.initVerify(publicKey)

            // Read data and update signature
            val buffer = ByteArray(8192)
            var len: Int
            FileInputStream(dataFile).use { input ->
                while (input.read(buffer).also { len = it } != -1) {
                    sig.update(buffer, 0, len)
                }
            }

            // Read signature bytes
            val signatureBytes = sigFile.readBytes()

            // Verify
            sig.verify(signatureBytes)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
