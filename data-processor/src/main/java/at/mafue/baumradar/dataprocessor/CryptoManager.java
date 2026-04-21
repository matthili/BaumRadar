package at.mafue.baumradar.dataprocessor;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CryptoManager {
    private static final Logger logger = LoggerFactory.getLogger(CryptoManager.class);

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    private PrivateKey privateKey;
    private PublicKey publicKey;

    public void generateKeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519", "BC");
        KeyPair kp = kpg.generateKeyPair();
        this.privateKey = kp.getPrivate();
        this.publicKey = kp.getPublic();
    }

    public void savePublicKey(File file) throws Exception {
        byte[] encoded = publicKey.getEncoded();
        String base64 = Base64.getEncoder().encodeToString(encoded);
        Files.writeString(file.toPath(), base64);
        logger.debug("Successfully exported Public Key base64 string.");
    }

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
        Files.write(sigFile.toPath(), signatureBytes);
        logger.debug("Successfully generated Ed25519 signature.");
    }
}
