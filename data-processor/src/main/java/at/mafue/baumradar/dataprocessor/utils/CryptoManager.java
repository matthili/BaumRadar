package at.mafue.baumradar.dataprocessor.utils;

import at.mafue.baumradar.dataprocessor.providers.*;
import at.mafue.baumradar.dataprocessor.providers.austria.*;
import at.mafue.baumradar.dataprocessor.providers.germany.*;
import at.mafue.baumradar.dataprocessor.providers.switzerland.*;
import at.mafue.baumradar.dataprocessor.models.*;
import at.mafue.baumradar.dataprocessor.utils.*;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages Ed25519 key-pair generation, persistence, and file signing.
 *
 * <p>The data-processing pipeline signs every compressed database file so that
 * the Android app can verify download integrity before importing.  Ed25519 is
 * chosen for its compact 64-byte signatures and fast verification, making it
 * well-suited for mobile devices.  Key material is encoded in Base64 for
 * safe text-based storage and distribution via GitHub Pages.
 *
 * <p>BouncyCastle is registered as a JCE provider in a static initializer
 * because the default JDK does not include Ed25519 support on all platforms.
 */
public class CryptoManager {
    private static final Logger logger = LoggerFactory.getLogger(CryptoManager.class);

    // Register BouncyCastle once at class-load time so that KeyFactory and
    // Signature instances can request the "BC" provider by name.
    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    private PrivateKey privateKey;
    private PublicKey publicKey;

    /**
     * Loads an existing Ed25519 key pair from Base64-encoded files, or generates
     * a new pair if the files do not yet exist.
     *
     * <p>Private and public keys are stored in PKCS#8 and X.509 DER format
     * respectively, each Base64-encoded into a single line for portability.
     *
     * @param privFile destination/source for the Base64-encoded private key
     * @param pubFile  destination/source for the Base64-encoded public key
     * @throws Exception if key generation, encoding, or file I/O fails
     */
    public void loadOrGenerateKeyPair(File privFile, File pubFile) throws Exception {
        if (privFile.exists() && pubFile.exists()) {
            logger.info("Loading existing key pair from disk...");
            byte[] privKeyBytes = Base64.getDecoder().decode(Files.readAllBytes(privFile.toPath()));
            byte[] pubKeyBytes = Base64.getDecoder().decode(Files.readAllBytes(pubFile.toPath()));
            
            KeyFactory kf = KeyFactory.getInstance("Ed25519", "BC");
            this.privateKey = kf.generatePrivate(new PKCS8EncodedKeySpec(privKeyBytes));
            this.publicKey = kf.generatePublic(new X509EncodedKeySpec(pubKeyBytes));
        } else {
            logger.info("Generating NEW key pair...");
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519", "BC");
            KeyPair kp = kpg.generateKeyPair();
            this.privateKey = kp.getPrivate();
            this.publicKey = kp.getPublic();
            
            Files.write(privFile.toPath(), Base64.getEncoder().encode(this.privateKey.getEncoded()));
            Files.write(pubFile.toPath(), Base64.getEncoder().encode(this.publicKey.getEncoded()));
        }
    }

    /**
     * Exports the public key to a standalone file as a Base64 string.
     *
     * <p>This file can be bundled with the Android app so that signature
     * verification works without a network round-trip for the key.
     *
     * @param file target file to write
     * @throws Exception if encoding or file I/O fails
     */
    public void savePublicKey(File file) throws Exception {
        byte[] encoded = publicKey.getEncoded();
        String base64 = Base64.getEncoder().encodeToString(encoded);
        Files.writeString(file.toPath(), base64);
        logger.debug("Successfully exported Public Key base64 string.");
    }

    /**
     * Produces an Ed25519 detached signature for the given data file.
     *
     * <p>The file is read in 8 KiB chunks to keep memory usage constant
     * regardless of file size.  The raw 64-byte signature is written to
     * {@code sigFile} without any additional encoding.
     *
     * @param dataFile the file whose content is to be signed
     * @param sigFile  destination for the raw Ed25519 signature bytes
     * @throws Exception if signing or file I/O fails
     */
    public void signFile(File dataFile, File sigFile) throws Exception {
        Signature sig = Signature.getInstance("Ed25519", "BC");
        sig.initSign(privateKey);

        byte[] buffer = new byte[8192];
        int len;
        try (FileInputStream in = new FileInputStream(dataFile)) {
            while ((len = in.read(buffer)) != -1) {
                sig.update(buffer, 0, len);
            }
        }
        
        byte[] signatureBytes = sig.sign();
        // Retry the write: Windows can briefly memory-map a just-created file
        // (ERROR_USER_MAPPED_FILE, e.g. via a virus scanner/indexer). Without a
        // retry this would leave a fresh .db.gz next to a stale .sig.
        java.io.IOException writeErr = null;
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                Files.write(sigFile.toPath(), signatureBytes);
                writeErr = null;
                break;
            } catch (java.io.IOException e) {
                writeErr = e;
                try { Thread.sleep(500L * attempt); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        }
        if (writeErr != null) throw writeErr;
        logger.debug("Successfully generated Ed25519 signature.");
    }
}

