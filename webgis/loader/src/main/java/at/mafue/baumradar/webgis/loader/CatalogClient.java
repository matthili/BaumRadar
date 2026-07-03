package at.mafue.baumradar.webgis.loader;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Lädt und parst den Stadtkatalog. */
public final class CatalogClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CatalogClient() {}

    public static Catalog fetch(Config cfg) throws IOException, InterruptedException {
        // Cache-Buster wie in der App: raw.githubusercontent cached aggressiv.
        String url = cfg.catalogUrl() + (cfg.catalogUrl().contains("?") ? "&" : "?")
                + "t=" + System.currentTimeMillis();
        try (HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(20))
                .build()) {
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new IOException("Katalog-Download fehlgeschlagen: HTTP " + resp.statusCode());
            }
            return parse(resp.body());
        }
    }

    /** Getrennt vom HTTP-Teil, damit das Parsing rein-JVM testbar ist. */
    static Catalog parse(String json) throws IOException {
        return MAPPER.readValue(json, Catalog.class);
    }
}
