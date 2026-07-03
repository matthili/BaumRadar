package at.mafue.baumradar.webgis.loader;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Gesamte Loader-Konfiguration aus Umgebungsvariablen (12-Factor-Stil).
 * Defaults passen zum docker-compose-Setup; für lokale Läufe außerhalb
 * von Compose zeigen sie auf localhost-Ports.
 *
 * @param publicKeyBase64 Ed25519-Public-Key (X.509/SPKI, Base64) — Trust-Anchor,
 *                        identisch zum in der Android-App eingebauten Schlüssel.
 */
public record Config(
        String catalogUrl,
        String publicKeyBase64,
        String pgUrl,
        String pgDb,
        String pgUser,
        String pgPassword,
        String geoserverUrl,
        String geoserverUser,
        String geoserverPassword,
        String geoserverPgHost,
        int geoserverPgPort,
        Set<String> cityFilter,
        boolean skipGeoserver,
        int maxParallelImports
) {
    /** Eingebauter Public Key — muss zu docs/data/public_key.b64 passen. */
    static final String DEFAULT_PUBLIC_KEY =
            "MCowBQYDK2VwAyEAEb9KGg1K77SqnuTv78CTcdLyKEZd7xr1EbE4PnUF3Yc=";

    static final String DEFAULT_CATALOG_URL =
            "https://raw.githubusercontent.com/matthili/BaumRadar/master/docs/data/catalog.json";

    public static Config fromEnv() {
        return new Config(
                env("CATALOG_URL", DEFAULT_CATALOG_URL),
                env("PUBLIC_KEY_BASE64", DEFAULT_PUBLIC_KEY),
                env("PG_URL", "jdbc:postgresql://localhost:5433/baumradar"),
                env("PG_DB", "baumradar"),
                env("PG_USER", "baumradar"),
                env("PG_PASSWORD", "baumradar"),
                env("GEOSERVER_URL", "http://localhost:8081/geoserver"),
                env("GEOSERVER_USER", "admin"),
                env("GEOSERVER_PASSWORD", "geoserver"),
                env("GEOSERVER_PG_HOST", "postgis"),
                Integer.parseInt(env("GEOSERVER_PG_PORT", "5432")),
                parseCityFilter(env("CITY_FILTER", "")),
                Boolean.parseBoolean(env("SKIP_GEOSERVER", "false")),
                Integer.parseInt(env("MAX_PARALLEL_IMPORTS", "4"))
        );
    }

    /** Kommagetrennte Stadt-IDs, case-insensitiv; leer/blank = keine Einschränkung. */
    static Set<String> parseCityFilter(String raw) {
        if (raw == null || raw.isBlank()) return Set.of();
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : v;
    }
}
