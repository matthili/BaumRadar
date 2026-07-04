package at.mafue.baumradar.dataprocessor;

import at.mafue.baumradar.dataprocessor.providers.CityProvider;
import at.mafue.baumradar.dataprocessor.utils.CatalogBuilder;
import at.mafue.baumradar.dataprocessor.utils.CryptoManager;
import at.mafue.baumradar.dataprocessor.utils.DatabaseExporter;
import at.mafue.baumradar.dataprocessor.utils.HarmonizationReport;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Core of the BaumRadar publishing pipeline, decoupled from any UI so it can be
 * driven by the CLI ({@link Main}) and — later — a local Web-UI.
 *
 * <p>{@link #run} processes a <em>selectable subset</em> of city providers in
 * parallel (each: SQLite → GZIP → Ed25519 signature, split if &gt; 50&nbsp;MB),
 * then rebuilds {@code catalog.json} over <em>all</em> providers. Cities not in
 * this run keep their files untouched and their {@code dataVersion} is carried
 * over from the previous catalog — so a single-city run never drops the other
 * cities. Progress is reported through a {@link PipelineListener}.
 */
public class Pipeline {
    private static final Logger logger = LoggerFactory.getLogger(Pipeline.class);

    /** Archives larger than this are split into numbered chunks for GitHub Pages / CDNs. */
    private static final long MAX_SIZE = 50L * 1024 * 1024;

    /**
     * Runs the pipeline for {@code toProcess} and rebuilds the catalog over {@code allProviders}.
     *
     * @param toProcess    the cities to (re)process this run (a subset of, or equal to, allProviders)
     * @param allProviders the full provider list — metadata source for the catalog
     * @param outDir       the {@code docs/data} output directory
     * @param baseUrl      base URL prefix for catalog file references
     * @param urlOverrides optional {@code cityId → source URL} overrides (may be {@code null})
     * @param listener     progress callbacks (may be {@code null} → {@link PipelineListener#NOOP})
     */
    public static void run(List<CityProvider> toProcess, List<CityProvider> allProviders,
                           File outDir, String baseUrl, Map<String, String> urlOverrides,
                           PipelineListener listener) throws Exception {
        final PipelineListener l = listener == null ? PipelineListener.NOOP : listener;
        if (!outDir.exists()) outDir.mkdirs();

        logger.info("1. Cryptographic Setup...");
        CryptoManager cryptoManager = new CryptoManager();
        File privKeyDest = new File(outDir, "private_key.b64");
        File pubKeyDest = new File(outDir, "public_key.b64");
        cryptoManager.loadOrGenerateKeyPair(privKeyDest, pubKeyDest);
        logger.info("   Public key saved/loaded at {}", pubKeyDest.getAbsolutePath());

        // Per-city content fingerprint written into the catalog as "dataVersion".
        // Seeded from the previous catalog so cities NOT reprocessed this run keep
        // their version; reprocessed cities overwrite their entry below.
        File catalogFile = new File(outDir, "catalog.json");
        Map<String, String> dataVersions = new ConcurrentHashMap<>();
        dataVersions.putAll(CatalogBuilder.readDataVersions(catalogFile));

        l.onStart(toProcess.stream().map(CityProvider::getName).toList());
        logger.info("2. Processing {} of {} cities in parallel...", toProcess.size(), allProviders.size());
        ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, toProcess.size()));
        AtomicInteger failed = new AtomicInteger();

        for (CityProvider provider : toProcess) {
            if (urlOverrides != null) {
                String ov = urlOverrides.get(provider.getCityId());
                if (ov != null && !ov.isBlank()) {
                    provider.setSourceUrlOverride(ov);
                    logger.info("[{}] Using source URL override.", provider.getName());
                }
            }
            executor.submit(() -> {
                try {
                    l.onCityStart(provider.getCityId(), provider.getName());
                    logger.info("[{}] Starting background import task...", provider.getName());
                    long trees = processCity(provider, outDir, cryptoManager, dataVersions);
                    logger.info("[{}] Finished pipeline! Created {}.db.gz and .sig successfully.",
                            provider.getName(), provider.getCityId());
                    l.onCityDone(provider.getCityId(), provider.getName(), trees);
                } catch (Exception e) {
                    failed.incrementAndGet();
                    logger.error("[{}] Failed completely with error: {}", provider.getName(), e.getMessage(), e);
                    l.onCityError(provider.getCityId(), provider.getName(), String.valueOf(e.getMessage()));
                }
            });
        }

        executor.shutdown();
        executor.awaitTermination(2, TimeUnit.HOURS);

        // Harmonisierungs-Report (Schicht 3): Arbeitsliste für spätere Alias-Pflege.
        // Landet im Repo-Root (git-ignoriert), nicht in docs/ (das ist GitHub Pages).
        try {
            File reportFile = new File(outDir.getAbsoluteFile().getParentFile().getParentFile(),
                    "harmonization_report.txt");
            HarmonizationReport.shared().writeTo(reportFile);
            logger.info("   Harmonization report written to {}", reportFile.getAbsolutePath());
        } catch (Exception e) {
            logger.warn("   Could not write harmonization report: {}", e.getMessage());
        }

        logger.info("3. Generating Catalog...");
        CatalogBuilder.build(catalogFile, allProviders, baseUrl, dataVersions);
        logger.info("   Catalog created at {}", catalogFile.getAbsolutePath());

        int ok = toProcess.size() - failed.get();
        logger.info("Done! {} of {} selected cities processed ({} failed) in {}",
                ok, toProcess.size(), failed.get(), outDir.getAbsolutePath());
        l.onFinished(ok, failed.get());
    }

    /**
     * Processes a single city end-to-end: SQLite build → snapshot content version →
     * GZIP (with retry for transient Windows file locks) → delete raw DB → Ed25519
     * signature → split into chunks if the archive exceeds {@link #MAX_SIZE}.
     *
     * @return the number of trees written for this city
     */
    private static long processCity(CityProvider provider, File outDir, CryptoManager cryptoManager,
                                    Map<String, String> dataVersions) throws Exception {
        String dbFileName = provider.getCityId() + ".db";
        File dbFile = new File(outDir, dbFileName);
        if (dbFile.exists()) dbFile.delete();

        DatabaseExporter exporter = new DatabaseExporter(dbFile.getAbsolutePath());
        exporter.open();
        exporter.createTable();

        provider.processData(exporter);

        // Snapshot the id-independent content fingerprint and tree count before closing.
        dataVersions.put(provider.getCityId(), exporter.getContentVersion());
        long treeCount = exporter.getInsertedCount();
        exporter.close();

        // Compress DB to .gz (retry on transient Windows file locks).
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

        // Delete uncompressed DB.
        dbFile.delete();

        // Sign the .gz archive.
        File sigFile = new File(outDir, dbFileName + ".gz.sig");
        cryptoManager.signFile(gzFile, sigFile);

        // GitHub Pages and many CDNs limit individual file sizes. Archives exceeding
        // 50 MB are split into numbered chunks (e.g. berlin.db.gz.001, .002, …).
        if (gzFile.length() > MAX_SIZE) {
            logger.info("[{}] GZ file exceeds 50MB ({} bytes). Splitting into chunks...",
                    provider.getName(), gzFile.length());
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
                    if (currentChunkSize >= MAX_SIZE) {
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
            // If a city's data shrank below the chunk threshold between runs, stale chunk
            // files from the previous build must be removed to avoid the app downloading
            // outdated partial files.
            for (int i = 1; i < 100; i++) {
                File oldChunk = new File(outDir, String.format("%s.%03d", gzFile.getName(), i));
                if (oldChunk.exists()) oldChunk.delete(); else break;
            }
        }

        return treeCount;
    }
}
