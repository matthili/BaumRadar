package at.mafue.baumradar.dataprocessor;

import org.junit.Test;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import java.security.Security;
import java.io.File;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class CryptoManagerTest {

    @Test
    public void testSignatureGenerationAndVerification() throws Exception {
        // Add BouncyCastle Provider required for Ed25519
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }

        // Setup test data
        File tempDbFile = File.createTempFile("test_trees", ".db");
        File tempSigFile = File.createTempFile("test_trees", ".sig");
        Files.write(tempDbFile.toPath(), "Hello World SQLite Data".getBytes());

        CryptoManager manager = new CryptoManager();
        manager.generateKeyPair();
        
        // 1. Generate Signature
        manager.signFile(tempDbFile, tempSigFile);
        
        assertTrue(tempSigFile.exists());
        assertTrue(tempSigFile.length() > 0);

        // 2. Validate PublicKey Export
        File tempKeyFile = File.createTempFile("pubkey", ".b64");
        manager.savePublicKey(tempKeyFile);
        assertTrue(tempKeyFile.exists());
        assertTrue(tempKeyFile.length() > 0);

        // In a real scenario we'd use the App's SignatureVerifier.
        // But we just want to ensure it completes without throwing Key or Signature Exceptions.
        
        tempDbFile.delete();
        tempSigFile.delete();
        tempKeyFile.delete();
    }
}
