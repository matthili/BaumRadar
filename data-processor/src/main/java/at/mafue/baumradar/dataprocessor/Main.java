package at.mafue.baumradar.dataprocessor;

import at.mafue.baumradar.dataprocessor.providers.*;
import at.mafue.baumradar.dataprocessor.providers.austria.*;
import at.mafue.baumradar.dataprocessor.providers.germany.*;
import at.mafue.baumradar.dataprocessor.providers.switzerland.*;
import at.mafue.baumradar.dataprocessor.models.*;
import at.mafue.baumradar.dataprocessor.utils.*;

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

/**
 * Entry point for the BaumRadar data-processing pipeline.
 *
 * <p>This batch job downloads urban tree inventories from multiple European
 * open-data portals (one {@link at.mafue.baumradar.dataprocessor.providers.CityProvider}
 * per city), converts each into a per-city SQLite database, and publishes the
 * resulting artifacts (compressed databases, cryptographic signatures, and a
 * discovery catalog) to the {@code docs/data/} directory for static hosting.
 *
 * <p>Processing steps executed in order:
 * <ol>
 *   <li>Load or generate an Ed25519 key pair for database integrity signing.</li>
 *   <li>Process all city providers <em>in parallel</em>, each writing its own
 *       SQLite file, compressing it with GZIP, and signing the archive.</li>
 *   <li>Generate {@code catalog.json} so the Android app can discover available
 *       city databases at runtime.</li>
 * </ol>
 */
public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);
    /** Filename for the Base64-encoded public key distributed alongside the databases. */
    private static final String PUB_KEY_FILE = "public_key.b64";
    /**
     * Base URL under which the compiled data files are published.
     * The catalog references this URL so the Android app knows where to
     * download individual city databases and signature files.
     */
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
            new HamburgProvider(),
            new RostockProvider(),
            new WuerzburgProvider(),
            new ZugProvider(),
            new LeipzigProvider(),
            new InnsbruckProvider(),
            new FrankfurtProvider(),
            new GrazProvider(),
            new KoelnProvider(),
            new StuttgartProvider(),
            new GelsenkirchenProvider(),
            new BonnProvider()
        );
        
        try {
            logger.info("1. Cryptographic Setup...");
            CryptoManager cryptoManager = new CryptoManager();
            File privKeyDest = new File(outDir, "private_key.b64");
            File pubKeyDest = new File(outDir, PUB_KEY_FILE);
            cryptoManager.loadOrGenerateKeyPair(privKeyDest, pubKeyDest);
            logger.info("   Public key saved/loaded at {}", pubKeyDest.getAbsolutePath());

            logger.info("2. Processing Cities in Parallel...");
            // Each city provider runs in its own thread so that network I/O from
            // different open-data portals overlaps, significantly reducing wall-clock time.
            ExecutorService executor = Executors.newFixedThreadPool(providers.size());

            // Per-city content fingerprint (id-independent), written into the catalog as
            // "dataVersion" so the app can detect when a downloaded city's data went stale.
            java.util.Map<String, String> dataVersions = new java.util.concurrent.ConcurrentHashMap<>();
            
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

                        // Snapshot the id-independent content fingerprint before closing.
                        dataVersions.put(provider.getCityId(), exporter.getContentVersion());

                        exporter.close();
                        
                        // Compress DB to .gz (retry on transient Windows file locks)
                        File gzFile = new File(outDir, dbFileName + ".gz");
                        java.io.IOException gzErr = null;
                        for (int attempt = 1; attempt <= 5; attempt++) {
                            try {
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
                                gzErr = null;
                                break;
                            } catch (java.io.IOException e) {
                                gzErr = e;
                                try { Thread.sleep(500L * attempt); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                            }
                        }
                        if (gzErr != null) throw gzErr;
                        
                        // Delete uncompressed DB
                        dbFile.delete();
                        
                        // Sign DB (the .gz file)
                        File sigFile = new File(outDir, dbFileName + ".gz.sig");
                        cryptoManager.signFile(gzFile, sigFile);
                        logger.info("[{}] Finished pipeline! Created {}.gz and .sig successfully.", provider.getName(), dbFileName);

                        // GitHub Pages and many CDNs limit individual file sizes.
                        // Archives exceeding 50 MB are split into numbered chunks
                        // (e.g. berlin.db.gz.001, .002, …) that the app reassembles.
                        long maxSize = 50 * 1024 * 1024; // 50 MB threshold
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
                            // If a city's data shrank below the chunk threshold between runs,
                            // stale chunk files from the previous build must be removed to
                            // avoid the app downloading outdated partial files.
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
            CatalogBuilder.build(catalogFile, providers, BASE_URL, dataVersions);
            logger.info("   Catalog created at {}", catalogFile.getAbsolutePath());

            logger.info("Done! All cities processed and ready in {}", outDir.getAbsolutePath());
            
        } catch (Exception e) {
            logger.error("Fatal error in Main processor thread: {}", e.getMessage(), e);
        }
    }
}

