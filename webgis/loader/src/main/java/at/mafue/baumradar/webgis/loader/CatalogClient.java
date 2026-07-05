package at.mafue.baumradar.webgis.loader;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Lädt und parst den Stadtkatalog. */
public final class CatalogClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CatalogClient() {}

    public static Catalog fetch(Config cfg) throws IOException, InterruptedException {
        // HTTP (inkl. Cache-Busting gegen raw.githubusercontent) macht der Downloader —
        // eine Stelle für alle Katalog-Artefakte statt zweier HttpClient-Implementierungen.
        try (Downloader downloader = new Downloader()) {
            return parse(new String(downloader.fetchBytes(cfg.catalogUrl()), StandardCharsets.UTF_8));
        }
    }

    /** Getrennt vom HTTP-Teil, damit das Parsing rein-JVM testbar ist. */
    static Catalog parse(String json) throws IOException {
        return MAPPER.readValue(json, Catalog.class);
    }
}
