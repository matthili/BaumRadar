package at.mafue.baumradar.dataprocessor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPOutputStream;
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

        // Determine the correct output directory depending on the current working directory
        File outDir;
        if (new File("data-processor").exists() && new File("app").exists()) {
            // Running from project root (e.g., via Android Studio Run button)
            outDir = new File("docs/data");
        } else {
            // Running from data-processor directory (e.g., via Gradle)
            outDir = new File("../docs/data");
        }

        if (!outDir.exists()) {
            outDir.mkdirs();
        }
        
        // Setup Providers
        List<CityProvider> providers = Arrays.asList(
            new ViennaProvider(),
            new LinzProvider(),
            new BerlinProvider(),
            new BaselProvider(),
            new ZurichProvider(),
            new FreiburgProvider(),
            new DortmundProvider(),
            new HamburgProvider()
        );
        
        try {
            logger.info("1. Cryptographic Setup...");
            CryptoManager cryptoManager = new CryptoManager();
            File privKeyDest = new File(outDir, "private_key.b64");
            File pubKeyDest = new File(outDir, PUB_KEY_FILE);
            cryptoManager.loadOrGenerateKeyPair(privKeyDest, pubKeyDest);
            logger.info("   Public key saved/loaded at {}", pubKeyDest.getAbsolutePath());

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
                        
                        // Compress DB to .gz
                        File gzFile = new File(outDir, dbFileName + ".gz");
                        if (gzFile.exists()) gzFile.delete();
                        try (FileInputStream fis = new FileInputStream(dbFile);
                             FileOutputStream fos = new FileOutputStream(gzFile);
                             GZIPOutputStream gzos = new GZIPOutputStream(fos)) {
                            byte[] buffer = new byte[8192];
                            int len;
                            while ((len = fis.read(buffer)) > 0) {
                                gzos.write(buffer, 0, len);
                            }
                        }
                        
                        // Delete uncompressed DB
                        dbFile.delete();
                        
                        // Sign DB (the .gz file)
                        File sigFile = new File(outDir, dbFileName + ".gz.sig");
                        cryptoManager.signFile(gzFile, sigFile);
                        logger.info("[{}] Finished pipeline! Created {}.gz and .sig successfully.", provider.getName(), dbFileName);

                        // Chunking for >50MB
                        long maxSize = 50 * 1024 * 1024; // 50MB
                        if (gzFile.length() > maxSize) {
                            logger.info("[{}] GZ file exceeds 50MB ({} bytes). Splitting into chunks...", provider.getName(), gzFile.length());
                            int chunkIndex = 1;
                            try (FileInputStream fis = new FileInputStream(gzFile)) {
                                byte[] buffer = new byte[8192];
                                int len;
                                long currentChunkSize = 0;
                                FileOutputStream chunkFos = null;
                                while ((len = fis.read(buffer)) > 0) {
                                    if (chunkFos == null) {
                                        String chunkExt = String.format(".%03d", chunkIndex);
                                        File chunkFile = new File(outDir, gzFile.getName() + chunkExt);
                                        chunkFos = new FileOutputStream(chunkFile);
                                        currentChunkSize = 0;
                                    }
                                    chunkFos.write(buffer, 0, len);
                                    currentChunkSize += len;
                                    if (currentChunkSize >= maxSize) {
                                        chunkFos.close();
                                        chunkFos = null;
                                        chunkIndex++;
                                    }
                                }
                                if (chunkFos != null) {
                                    chunkFos.close();
                                }
                            }
                            // Delete the un-chunked file so we don't commit it!
                            gzFile.delete();
                        } else {
                            // Ensure old chunks are deleted if it was previously chunked
                            for (int i=1; i<100; i++) {
                                File oldChunk = new File(outDir, String.format("%s.%03d", gzFile.getName(), i));
                                if (oldChunk.exists()) oldChunk.delete(); else break;
                            }
                        }
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
