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

object SignatureVerifier {

    init {
        // Ensure BouncyCastle is added for Ed25519 support on all API levels
        Security.removeProvider("BC")
        Security.addProvider(BouncyCastleProvider())
    }

    /**
     * Verifies the file against the signature file using the base64 encoded public key.
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
