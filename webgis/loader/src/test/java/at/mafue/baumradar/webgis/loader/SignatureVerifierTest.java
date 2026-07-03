package at.mafue.baumradar.webgis.loader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-Trip mit frisch erzeugtem Ed25519-Schlüsselpaar — rein JDK-nativ,
 * kein BouncyCastle. Spiegelt das Format der echten Artefakte: Signatur als
 * rohe Bytes, Public Key als Base64-X.509-SPKI.
 */
class SignatureVerifierTest {

    @TempDir
    Path tempDir;

    private record Fixture(Path dataFile, byte[] signature, String publicKeyBase64) {}

    private Fixture sign(byte[] data) throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Path file = tempDir.resolve("stadt.db.gz");
        Files.write(file, data);

        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(data);

        return new Fixture(file, signer.sign(),
                Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
    }

    @Test
    void validSignatureVerifies() throws Exception {
        Fixture f = sign("BaumRadar-Testdaten ".repeat(5000).getBytes());
        assertTrue(SignatureVerifier.verify(f.dataFile(), f.signature(), f.publicKeyBase64()));
    }

    @Test
    void tamperedDataIsRejected() throws Exception {
        byte[] data = "BaumRadar-Testdaten ".repeat(5000).getBytes();
        Fixture f = sign(data);
        data[0] ^= 0x01; // ein Bit kippen
        Files.write(f.dataFile(), data);
        assertFalse(SignatureVerifier.verify(f.dataFile(), f.signature(), f.publicKeyBase64()));
    }

    @Test
    void wrongKeyIsRejected() throws Exception {
        Fixture f = sign("BaumRadar-Testdaten".getBytes());
        String otherKey = Base64.getEncoder().encodeToString(
                KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic().getEncoded());
        assertFalse(SignatureVerifier.verify(f.dataFile(), f.signature(), otherKey));
    }

    @Test
    void garbageKeyIsRejectedNotThrown() throws Exception {
        Fixture f = sign("BaumRadar-Testdaten".getBytes());
        assertFalse(SignatureVerifier.verify(f.dataFile(), f.signature(), "kein-gueltiger-schluessel"));
    }
}
