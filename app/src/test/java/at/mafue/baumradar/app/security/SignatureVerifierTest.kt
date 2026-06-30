package at.mafue.baumradar.app.security

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Security
import java.security.Signature
import java.util.Base64

/**
 * Round-trip tests for [SignatureVerifier]. A freshly generated Ed25519 key pair
 * signs test data; the verifier must accept the genuine signature and reject any
 * tampering, a foreign key, or a malformed signature. Fully self-contained — no
 * fixture files and no dependency on the project's own key.
 *
 * These tests run on a plain JVM (no Robolectric/emulator) because
 * [SignatureVerifier] now uses {@code java.util.Base64} instead of the
 * framework-only {@code android.util.Base64}.
 */
class SignatureVerifierTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun registerBouncyCastle() {
            if (Security.getProvider("BC") == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }
    }

    private fun newKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("Ed25519", "BC").generateKeyPair()

    /** X.509 SubjectPublicKeyInfo, Base64 — exactly the form the verifier expects. */
    private fun publicKeyBase64(kp: KeyPair): String =
        Base64.getEncoder().encodeToString(kp.public.encoded)

    private fun writeTemp(prefix: String, bytes: ByteArray): File {
        val f = File.createTempFile(prefix, ".bin")
        f.deleteOnExit()
        f.writeBytes(bytes)
        return f
    }

    private fun sign(kp: KeyPair, data: ByteArray): ByteArray {
        val signer = Signature.getInstance("Ed25519", "BC")
        signer.initSign(kp.private)
        signer.update(data)
        return signer.sign()
    }

    @Test
    fun acceptsGenuineSignature() {
        val kp = newKeyPair()
        val data = "BaumRadar Test-Nutzlast äöü 🌳".toByteArray()
        val dataFile = writeTemp("data", data)
        val sigFile = writeTemp("sig", sign(kp, data))

        assertTrue(SignatureVerifier.verifyFile(dataFile, sigFile, publicKeyBase64(kp)))
    }

    @Test
    fun rejectsTamperedData() {
        val kp = newKeyPair()
        val sigFile = writeTemp("sig", sign(kp, "original".toByteArray()))
        // The file on disk no longer matches what was signed (one extra byte).
        val tamperedFile = writeTemp("data", "original!".toByteArray())

        assertFalse(SignatureVerifier.verifyFile(tamperedFile, sigFile, publicKeyBase64(kp)))
    }

    @Test
    fun rejectsSignatureFromDifferentKey() {
        val signerKp = newKeyPair()
        val otherKp = newKeyPair()
        val data = "payload".toByteArray()
        val dataFile = writeTemp("data", data)
        val sigFile = writeTemp("sig", sign(signerKp, data))

        // Genuine signature, but verified against an unrelated public key → must fail.
        assertFalse(SignatureVerifier.verifyFile(dataFile, sigFile, publicKeyBase64(otherKp)))
    }

    @Test
    fun rejectsGarbageSignature() {
        val kp = newKeyPair()
        val dataFile = writeTemp("data", "payload".toByteArray())
        val sigFile = writeTemp("sig", ByteArray(64) { 0x42 }) // 64 bytes, but not a real signature

        assertFalse(SignatureVerifier.verifyFile(dataFile, sigFile, publicKeyBase64(kp)))
    }
}
