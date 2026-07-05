package at.mafue.baumradar.webgis.loader;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests für den {@link Downloader} gegen einen eingebetteten JDK-HttpServer.
 * Wichtigster Fall: der <b>Chunk-Zusammenbau</b> — der Pfad läuft in Produktion
 * nur, wenn eine Stadt-Datenbank 50 MB überschreitet, und darf deshalb nicht
 * erst dann zum ersten Mal ausprobiert werden.
 */
class DownloaderTest {

    @TempDir
    Path tempDir;

    private HttpServer server;
    private String base;
    /** Pfad → zuletzt empfangene Query (für den Cache-Buster-Nachweis). */
    private final Map<String, String> lastQuery = new ConcurrentHashMap<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    /** Registriert einen Pfad, der {@code body} liefert (oder {@code status} ohne Body). */
    private void serve(String path, byte[] body, int status) {
        server.createContext(path, ex -> {
            lastQuery.put(path, String.valueOf(ex.getRequestURI().getQuery()));
            ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                ex.getResponseBody().write(body);
            }
            ex.close();
        });
    }

    private static Catalog.City city(String dbUrl, List<String> chunks) {
        return new Catalog.City("teststadt", "Teststadt", "Testland",
                new double[]{1, 2, 3, 4}, dbUrl, dbUrl + ".sig", chunks, "v1");
    }

    @Test
    void downloadsSingleFileByteExact() throws Exception {
        byte[] data = "gzip-bytes ".repeat(1000).getBytes(StandardCharsets.UTF_8);
        serve("/single.db.gz", data, 200);

        try (Downloader d = new Downloader()) {
            Path gz = d.downloadDb(city(base + "/single.db.gz", null), tempDir);
            assertArrayEquals(data, Files.readAllBytes(gz));
        }
    }

    @Test
    void reassemblesChunksInOrder() throws Exception {
        // Eine "Datei" in drei Byte-Abschnitte zerlegt — exakt das Publish-Format.
        byte[] whole = "AAAA-BBBB-CCCC-Ende".repeat(500).getBytes(StandardCharsets.UTF_8);
        int cut1 = whole.length / 3;
        int cut2 = 2 * whole.length / 3;
        serve("/db.001", Arrays.copyOfRange(whole, 0, cut1), 200);
        serve("/db.002", Arrays.copyOfRange(whole, cut1, cut2), 200);
        serve("/db.003", Arrays.copyOfRange(whole, cut2, whole.length), 200);

        try (Downloader d = new Downloader()) {
            Path gz = d.downloadDb(
                    city(base + "/ignoriert.db.gz",
                            List.of(base + "/db.001", base + "/db.002", base + "/db.003")),
                    tempDir);
            assertArrayEquals(whole, Files.readAllBytes(gz),
                    "Chunks müssen in Reihenfolge zur Originaldatei zusammengesetzt werden");
        }
    }

    @Test
    void httpErrorThrowsInsteadOfWritingPartialData() {
        serve("/kaputt.db.gz", new byte[0], 404);
        try (Downloader d = new Downloader()) {
            IOException ex = assertThrows(IOException.class,
                    () -> d.downloadDb(city(base + "/kaputt.db.gz", null), tempDir));
            assertTrue(ex.getMessage().contains("404"), "Statuscode gehört in die Fehlermeldung");
        }
    }

    @Test
    void appendsCacheBusterToEveryRequest() throws Exception {
        serve("/sig", "unterschrift".getBytes(StandardCharsets.UTF_8), 200);
        try (Downloader d = new Downloader()) {
            d.fetchBytes(base + "/sig");
        }
        String query = lastQuery.get("/sig");
        assertNotNull(query);
        assertTrue(query.matches(".*\\bt=\\d+.*"),
                "raw.githubusercontent cached aggressiv — jeder Abruf braucht den t=-Parameter, war: " + query);
    }
}
