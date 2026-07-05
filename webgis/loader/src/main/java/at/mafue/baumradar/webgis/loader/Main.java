package at.mafue.baumradar.webgis.loader;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/**
 * Einstiegspunkt des BaumRadar-WebGIS-Loaders.
 *
 * Ablauf: Schema anlegen → Katalog laden → Städte parallel importieren
 * (Virtual Threads, gedrosselt über eine Semaphore) → GeoServer provisionieren.
 * Idempotent: Städte, deren {@code dataVersion} bereits importiert ist, werden
 * übersprungen — ein erneutes {@code docker compose up} ist damit billig.
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        Config cfg = Config.fromEnv();
        System.out.println("BaumRadar WebGIS-Loader");
        System.out.println("  Katalog:  " + cfg.catalogUrl());
        System.out.println("  PostGIS:  " + cfg.pgUrl());

        PostGis pg = new PostGis(cfg);
        pg.initSchema();

        Catalog catalog = CatalogClient.fetch(cfg);
        List<Catalog.City> cities = catalog.cities().stream()
                .filter(c -> cfg.cityFilter().isEmpty()
                        || cfg.cityFilter().contains(c.id().toLowerCase(Locale.ROOT)))
                .toList();
        System.out.printf("  Städte:   %d im Katalog, %d ausgewählt%n",
                catalog.cities().size(), cities.size());

        // Import-Stand einmalig vorab lesen (Map ist danach read-only in den Threads).
        Map<String, String> state = pg.fetchImportState();

        List<ImportResult> results = new ArrayList<>();
        Semaphore gate = new Semaphore(cfg.maxParallelImports());
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ImportResult>> futures = new ArrayList<>();
            for (Catalog.City city : cities) {
                futures.add(exec.submit(() -> {
                    if (city.dataVersion() != null
                            && city.dataVersion().equals(state.get(city.id()))) {
                        System.out.printf("  [%s] unverändert (dataVersion %s) — übersprungen%n",
                                city.id(), city.dataVersion());
                        return ImportResult.skipped(city.id());
                    }
                    gate.acquire();
                    try {
                        return new CityImporter(cfg, pg).run(city);
                    } finally {
                        gate.release();
                    }
                }));
            }
            for (Future<ImportResult> f : futures) {
                results.add(f.get());
            }
        }

        long imported = results.stream().filter(r -> r.status() == ImportResult.Status.IMPORTED).count();
        long skipped = results.stream().filter(r -> r.status() == ImportResult.Status.SKIPPED).count();
        List<ImportResult> failed = results.stream()
                .filter(r -> r.status() == ImportResult.Status.FAILED).toList();
        int totalTrees = results.stream().mapToInt(ImportResult::trees).sum();
        int totalZones = results.stream().mapToInt(ImportResult::zones).sum();

        System.out.println("--------------------------------------------------");
        System.out.printf("  Importiert: %d Städte (%,d Bäume, %,d Zonen), übersprungen: %d, fehlgeschlagen: %d%n",
                imported, totalTrees, totalZones, skipped, failed.size());
        for (ImportResult r : failed) {
            System.out.printf("  FEHLER [%s]: %s%n", r.cityId(), r.error());
        }

        int genera = pg.refreshGenusStats();
        int generaCity = pg.refreshGenusStatsCity();
        int species = pg.refreshSpeciesStats();
        System.out.printf("  Statistik: %d Gattungen (%d Stadt-Gattung-Paare), %d Art-Tupel%n",
                genera, generaCity, species);

        if (cfg.skipGeoserver()) {
            System.out.println("  GeoServer-Provisionierung übersprungen (SKIP_GEOSERVER=true)");
        } else {
            new GeoServerProvisioner(cfg).provision();
        }

        // Teilfehler sichtbar machen: Exit-Code 1, wenn mindestens eine Stadt scheiterte.
        if (!failed.isEmpty()) {
            System.exit(1);
        }
    }
}
