package at.mafue.baumradar.dataprocessor;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);
    private static final String PUB_KEY_FILE = "public_key.b64";
    // Default URL configured for GitHub Pages / Raw from user's repository
    private static final String BASE_URL = "https://raw.githubusercontent.com/matthili/BaumRadar/master/docs/data/";
    
    public static void main(String[] args) {
        logger.info("Starting BaumRadar Data Processor Background Job...");

        File outDir = new File("../docs/data");
        if (!outDir.exists()) {
            outDir.mkdirs();
        }
        
        // Setup Providers
        List<CityProvider> providers = Arrays.asList(
            new ViennaProvider(),
            new LinzProvider(),
            new BerlinProvider()
        );
        
        try {
            logger.info("1. Cryptographic Setup...");
            CryptoManager cryptoManager = new CryptoManager();
            cryptoManager.generateKeyPair();
            File pubKeyDest = new File(outDir, PUB_KEY_FILE);
            cryptoManager.savePublicKey(pubKeyDest);
            logger.info("   Public key saved to {}", pubKeyDest.getAbsolutePath());

            logger.info("2. Processing Cities in Parallel...");
            // Use a Thread Pool to process cities simultaneously
            ExecutorService executor = Executors.newFixedThreadPool(providers.size());
            
            for (CityProvider provider : providers) {
                executor.submit(() -> {
                    try {
                        logger.info("[{}] Starting background import task...", provider.getName());
                        String dbFileName = provider.getCityId() + ".db";
                        File dbFile = new File(outDir, dbFileName);
                        if (dbFile.exists()) dbFile.delete();
                        
                        DatabaseExporter exporter = new DatabaseExporter(dbFile.getAbsolutePath());
                        exporter.open();
                        exporter.createTable();
                        
                        provider.processData(exporter);
                        
                        exporter.close();
                        
                        // Sign DB
                        File sigFile = new File(outDir, dbFileName + ".sig");
                        cryptoManager.signFile(dbFile, sigFile);
                        logger.info("[{}] Finished pipeline! Created {} and .sig successfully.", provider.getName(), dbFileName);
                    } catch (Exception e) {
                        logger.error("[{}] Failed completely with error: {}", provider.getName(), e.getMessage(), e);
                    }
                });
            }
            
            executor.shutdown();
            executor.awaitTermination(2, TimeUnit.HOURS); // Wait for all threads to finish

            logger.info("3. Generating Catalog...");
            File catalogFile = new File(outDir, "catalog.json");
            CatalogBuilder.build(catalogFile, providers, BASE_URL);
            logger.info("   Catalog created at {}", catalogFile.getAbsolutePath());

            logger.info("Done! All cities processed and ready in {}", outDir.getAbsolutePath());
            
        } catch (Exception e) {
            logger.error("Fatal error in Main processor thread: {}", e.getMessage(), e);
        }
    }
}
