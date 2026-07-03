package at.mafue.baumradar.webgis.loader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * Kompletter Import-Ablauf für eine Stadt:
 * Download → Ed25519-Signaturprüfung → gunzip → PostGIS-Import.
 * Läuft auf einem Virtual Thread; alle temporären Dateien werden am Ende entfernt.
 */
final class CityImporter {

    private final Config cfg;
    private final PostGis pg;

    CityImporter(Config cfg, PostGis pg) {
        this.cfg = cfg;
        this.pg = pg;
    }

    ImportResult run(Catalog.City city) {
        Path tmpDir = null;
        try (Downloader downloader = new Downloader()) {
            tmpDir = Files.createTempDirectory("baumradar-" + city.id() + "-");

            System.out.printf("  [%s] lade %s ...%n", city.id(), city.dbUrl());
            Path gz = downloader.downloadDb(city, tmpDir);
            byte[] sig = downloader.fetchBytes(city.sigUrl());

            if (!SignatureVerifier.verify(gz, sig, cfg.publicKeyBase64())) {
                return ImportResult.failed(city.id(), "Ed25519-Signatur ungültig — Daten verworfen");
            }

            Path db = gunzip(gz);
            int[] counts = pg.importCity(city, db);
            System.out.printf("  [%s] importiert: %,d Bäume, %,d Zonen%n", city.id(), counts[0], counts[1]);
            return ImportResult.imported(city.id(), counts[0], counts[1]);
        } catch (Exception e) {
            return ImportResult.failed(city.id(),
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            deleteQuietly(tmpDir);
        }
    }

    private static Path gunzip(Path gz) throws IOException {
        Path out = gz.resolveSibling(gz.getFileName().toString().replaceFirst("\\.gz$", ""));
        try (InputStream in = new GZIPInputStream(Files.newInputStream(gz))) {
            Files.copy(in, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return out;
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Temp-Aufräumen ist Best-Effort
                }
            });
        } catch (IOException ignored) {
        }
    }
}
