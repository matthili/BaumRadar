package at.mafue.baumradar.dataprocessor;

import at.mafue.baumradar.dataprocessor.providers.CityProvider;
import at.mafue.baumradar.dataprocessor.utils.CatalogBuilder;
import at.mafue.baumradar.dataprocessor.utils.Chunks;
import at.mafue.baumradar.dataprocessor.utils.CryptoManager;
import at.mafue.baumradar.dataprocessor.utils.DatabaseExporter;
import at.mafue.baumradar.dataprocessor.utils.GeocoderCutter;
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

    /**
     * Runs the pipeline for {@code toProcess} and rebuilds the catalog over {@code allProviders}.
     *
     * @param toProcess       the cities to (re)process this run (a subset of, or equal to, allProviders;
     *                        may be empty for a geocoder-only run)
     * @param allProviders    the full provider list — metadata source for the catalog
     * @param outDir          the {@code docs/data} output directory
     * @param baseUrl         base URL prefix for catalog file references
     * @param urlOverrides    optional {@code cityId → source URL} overrides (may be {@code null})
     * @param geocoderCityIds cities whose geocoder data (Photon dump cut) should be refreshed
     *                        (may be {@code null}/empty — the usual case)
     * @param listener        progress callbacks (may be {@code null} → {@link PipelineListener#NOOP})
     */
    public static void run(List<CityProvider> toProcess, List<CityProvider> allProviders,
                           File outDir, String baseUrl, Map<String, String> urlOverrides,
                           java.util.Set<String> geocoderCityIds,
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
        // Geocoder-Versionen analog: nicht neu geschnittene Städte behalten ihren Stand.
        Map<String, String> geocoderVersions = new ConcurrentHashMap<>();
        geocoderVersions.putAll(CatalogBuilder.readVersions(catalogFile, "geocoderVersion"));

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

        // Geocoder-Daten (Photon-Dump-Schnitt) für die angeforderten Städte erneuern.
        if (geocoderCityIds != null && !geocoderCityIds.isEmpty()) {
            runGeocoderPhase(geocoderCityIds, allProviders, outDir, cryptoManager, geocoderVersions, l);
        }

        logger.info("3. Generating Catalog...");
        CatalogBuilder.build(catalogFile, allProviders, baseUrl, dataVersions, geocoderVersions);
        logger.info("   Catalog created at {}", catalogFile.getAbsolutePath());

        int ok = toProcess.size() - failed.get();
        logger.info("Done! {} of {} selected cities processed ({} failed) in {}",
                ok, toProcess.size(), failed.get(), outDir.getAbsolutePath());
        l.onFinished(ok, failed.get());
    }

    /**
     * Geocoder-Phase: stellt den Planet-Dump sicher (Download mit Resume), schneidet
     * die angeforderten Städte in einem Streaming-Durchlauf, signiert und chunkt die
     * Ergebnisse und aktualisiert die {@code geocoderVersion}s für den Katalog.
     * Fehler brechen den Lauf nicht ab — die Baumdaten sind bereits publiziert.
     */
    private static void runGeocoderPhase(java.util.Set<String> geocoderCityIds,
                                         List<CityProvider> allProviders, File outDir,
                                         CryptoManager cryptoManager,
                                         Map<String, String> geocoderVersions,
                                         PipelineListener l) {
        List<CityProvider> geoProviders = allProviders.stream()
                .filter(p -> geocoderCityIds.contains(p.getCityId()))
                .toList();
        java.util.function.Consumer<String> note = msg -> {
            logger.info("[geocoder] {}", msg);
            l.onNote(msg);
        };
        try {
            File repoRoot = outDir.getAbsoluteFile().getParentFile().getParentFile();
            File dump = new File(repoRoot, "geocoder/" + GeocoderCutter.DUMP_FILENAME);
            GeocoderCutter.ensureDump(dump, note);

            Map<String, GeocoderCutter.Result> results =
                    GeocoderCutter.cut(dump, GeocoderCutter.boxesFor(geoProviders), outDir, note);

            for (CityProvider p : geoProviders) {
                GeocoderCutter.Result r = results.get(p.getCityId());
                if (r == null) {
                    l.onGeoCityError(p.getCityId(), p.getName(), "keine Orte im Stadtgebiet gefunden");
                    continue;
                }
                long compressedBytes = r.file().length();
                File sigFile = new File(outDir, r.file().getName() + ".sig");
                cryptoManager.signFile(r.file(), sigFile);
                int chunks = Chunks.splitIfNeeded(r.file());
                if (chunks > 0) {
                    logger.info("[geocoder] {} über 50MB — in {} Chunks geteilt.", r.file().getName(), chunks);
                }
                geocoderVersions.put(p.getCityId(), r.version());
                l.onGeoCityDone(p.getCityId(), p.getName(), r.places(), compressedBytes);
            }
        } catch (Exception e) {
            logger.error("[geocoder] Phase fehlgeschlagen: {}", e.getMessage(), e);
            for (CityProvider p : geoProviders) {
                l.onGeoCityError(p.getCityId(), p.getName(), String.valueOf(e.getMessage()));
            }
        }
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
        // 50 MB are split into numbered chunks (e.g. berlin.db.gz.001, .002, …);
        // shrunken cities get their stale chunks cleaned up.
        int chunks = Chunks.splitIfNeeded(gzFile);
        if (chunks > 0) {
            logger.info("[{}] GZ exceeded 50MB — split into {} chunks.", provider.getName(), chunks);
        }

        return treeCount;
    }
}
