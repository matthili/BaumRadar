package at.mafue.baumradar.webgis.loader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;

/**
 * Lädt Katalog-Artefakte (Datenbanken, Signaturen) herunter.
 * Unterstützt gechunkte Datenbanken (dbUrlChunks): die Teile sind schlichte
 * Byte-Abschnitte der .db.gz und werden in Reihenfolge aneinandergehängt —
 * dasselbe Verfahren wie in der Android-App.
 */
final class Downloader implements AutoCloseable {

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    /** Lädt die (ggf. gechunkte) .db.gz einer Stadt in das Zielverzeichnis. */
    Path downloadDb(Catalog.City city, Path targetDir) throws IOException, InterruptedException {
        Path gz = targetDir.resolve(city.id() + ".db.gz");
        // Chunks sind Byte-Abschnitte EINER gz-Datei → in Reihenfolge in dieselbe
        // Datei streamen; ohne Chunks ist die Liste einfach die eine dbUrl.
        List<String> urls = (city.dbUrlChunks() != null && !city.dbUrlChunks().isEmpty())
                ? city.dbUrlChunks()
                : List.of(city.dbUrl());
        try (OutputStream out = Files.newOutputStream(gz,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (String url : urls) {
                streamTo(url, out);
            }
        }
        return gz;
    }

    /** Kleine Artefakte (Signaturen, Katalog) komplett in den Speicher. */
    byte[] fetchBytes(String url) throws IOException, InterruptedException {
        HttpResponse<byte[]> resp = http.send(
                HttpRequest.newBuilder(URI.create(cacheBusted(url))).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200) {
            throw new IOException("Download fehlgeschlagen (HTTP " + resp.statusCode() + "): " + url);
        }
        return resp.body();
    }

    private void streamTo(String url, OutputStream out) throws IOException, InterruptedException {
        HttpResponse<InputStream> resp = http.send(
                HttpRequest.newBuilder(URI.create(cacheBusted(url))).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            throw new IOException("Download fehlgeschlagen (HTTP " + resp.statusCode() + "): " + url);
        }
        try (InputStream in = resp.body()) {
            in.transferTo(out);
        }
    }

    /** raw.githubusercontent cached aggressiv — ein Zeitstempel-Parameter umgeht das. */
    private static String cacheBusted(String url) {
        return url + (url.contains("?") ? "&" : "?") + "t=" + System.currentTimeMillis();
    }

    @Override
    public void close() {
        http.close();
    }
}
