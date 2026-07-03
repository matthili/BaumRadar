package at.mafue.baumradar.webgis.loader;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Ed25519-Signaturprüfung der heruntergeladenen .db.gz-Dateien.
 *
 * Fachlich identisch zur App-Klasse {@code at.mafue.baumradar.app.security.SignatureVerifier},
 * aber ohne BouncyCastle: Ed25519 ist seit JDK 15 Teil der Standard-JCE — die App
 * braucht die Extra-Dependency nur wegen alter Android-API-Level.
 * Signaturdatei = rohe 64 Signatur-Bytes; Public Key = Base64-kodiertes
 * X.509-SubjectPublicKeyInfo.
 */
public final class SignatureVerifier {

    private SignatureVerifier() {}

    public static boolean verify(Path dataFile, byte[] signatureBytes, String publicKeyBase64) {
        try {
            byte[] keyBytes = Base64.getMimeDecoder().decode(publicKeyBase64.trim());
            PublicKey publicKey = KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(keyBytes));

            Signature sig = Signature.getInstance("Ed25519");
            sig.initVerify(publicKey);

            // Streaming in 8-KB-Blöcken — Datenbanken können mehrere 10 MB groß sein.
            byte[] buffer = new byte[8192];
            int len;
            try (InputStream in = Files.newInputStream(dataFile)) {
                while ((len = in.read(buffer)) != -1) {
                    sig.update(buffer, 0, len);
                }
            }
            return sig.verify(signatureBytes);
        } catch (Exception e) {
            // Jede Abweichung (kaputter Key, kaputte Datei, ...) => nicht vertrauen.
            return false;
        }
    }
}
