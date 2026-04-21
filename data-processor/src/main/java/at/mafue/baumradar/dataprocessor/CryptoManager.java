package at.mafue.baumradar.dataprocessor;

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

public class CryptoManager {
    private static final Logger logger = LoggerFactory.getLogger(CryptoManager.class);

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    private PrivateKey privateKey;
    private PublicKey publicKey;

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
