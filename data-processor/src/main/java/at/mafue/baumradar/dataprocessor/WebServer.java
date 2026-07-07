package at.mafue.baumradar.dataprocessor;

import at.mafue.baumradar.dataprocessor.providers.CityProvider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A dependency-free local Web-UI for the publishing pipeline, built on the JDK's
 * {@link com.sun.net.httpserver.HttpServer}. Serves one page (city checkboxes,
 * select-all/none, start) and streams per-city progress via Server-Sent Events.
 *
 * <p><b>Lifecycle:</b> the server idles until a run is started. When the run
 * finishes it pushes a final {@code done} event so an open tab shows the summary,
 * writes a self-contained {@code last_run.html} to the repo root (viewable even
 * after shutdown / on refresh), and then exits the JVM. The browser's SSE
 * connection closing after {@code done} is treated by the page as the planned end.
 */
public class WebServer {
    private static final Logger logger = LoggerFactory.getLogger(WebServer.class);

    private final List<CityProvider> allProviders;
    private final File outDir;
    private final String baseUrl;
    private final int port;

    private final ObjectMapper mapper = new ObjectMapper();
    private final CopyOnWriteArrayList<OutputStream> sseClients = new CopyOnWriteArrayList<>();
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);
    private final AtomicBoolean runStarted = new AtomicBoolean(false);
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    private String logoDataUri = "";
    private String faviconDataUri = "";

    public WebServer(List<CityProvider> allProviders, File outDir, String baseUrl, int port) {
        this.allProviders = allProviders;
        this.outDir = outDir;
        this.baseUrl = baseUrl;
        this.port = port;
    }

    public void start() throws IOException {
        logoDataUri = dataUri("/webui/logo.png", "image/png");
        faviconDataUri = dataUri("/webui/favicon.png", "image/png");

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/api/cities", this::handleCities);
        server.createContext("/api/run", this::handleRun);
        server.createContext("/api/progress", this::handleProgress);
        server.createContext("/api/shutdown", this::handleShutdown);
        server.createContext("/", this::handleRoot);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        String url = "http://127.0.0.1:" + port + "/";
        logger.info("BaumRadar Runner UI läuft auf {}  (schließt sich nach dem Lauf selbst)", url);
        openBrowser(url);
    }

    // ---- Routes -------------------------------------------------------------

    private void handleRoot(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if ("/".equals(path) || "/index.html".equals(path)) {
            String html = readResourceText("/webui/index.html")
                    .replace("{{LOGO}}", logoDataUri)
                    .replace("{{FAVICON}}", faviconDataUri);
            sendText(ex, 200, "text/html; charset=utf-8", html);
        } else if ("/favicon.ico".equals(path) || "/favicon.png".equals(path)) {
            sendBytes(ex, 200, "image/png", readResourceBytes("/webui/favicon.png"));
        } else {
            sendText(ex, 404, "text/plain; charset=utf-8", "Not found");
        }
    }

    private void handleCities(HttpExchange ex) throws IOException {
        List<CityProvider> sorted = new ArrayList<>(allProviders);
        sorted.sort(Comparator.comparing(CityProvider::getCountry).thenComparing(CityProvider::getName));
        ArrayNode arr = mapper.createArrayNode();
        for (CityProvider p : sorted) {
            ObjectNode o = arr.addObject();
            o.put("id", p.getCityId());
            o.put("name", p.getName());
            o.put("country", p.getCountry());
        }
        sendText(ex, 200, "application/json; charset=utf-8", arr.toString());
    }

    private void handleRun(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendText(ex, 405, "text/plain", "POST only");
            return;
        }
        if (!runStarted.compareAndSet(false, true)) {
            sendText(ex, 409, "application/json", "{\"error\":\"already running\"}");
            return;
        }
        JsonNode body = mapper.readTree(ex.getRequestBody());
        List<String> ids = new ArrayList<>();
        body.path("cities").forEach(n -> ids.add(n.asText()));
        java.util.Set<String> geoIds = new java.util.HashSet<>();
        body.path("geocoderCities").forEach(n -> geoIds.add(n.asText()));
        Map<String, String> overrides = new HashMap<>();
        body.path("overrides").fields().forEachRemaining(e -> overrides.put(e.getKey(), e.getValue().asText()));

        if (ids.isEmpty() && geoIds.isEmpty()) {
            runStarted.set(false);
            sendText(ex, 400, "application/json", "{\"error\":\"nothing selected\"}");
            return;
        }

        sendText(ex, 202, "application/json", "{\"started\":true}");

        Thread t = new Thread(() -> runPipeline(ids, geoIds, overrides), "pipeline");
        t.setDaemon(true);
        t.start();
    }

    /** Quit from the UI without running anything (or after the user is done). */
    private void handleShutdown(HttpExchange ex) throws IOException {
        sendText(ex, 202, "application/json", "{\"stopping\":true}");
        logger.info("Beenden aus dem UI angefordert.");
        scheduleShutdown(600);
    }

    private void handleProgress(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("Connection", "keep-alive");
        ex.sendResponseHeaders(200, 0); // 0 → chunked / open-ended stream
        OutputStream os = ex.getResponseBody();
        os.write(": connected\n\n".getBytes(StandardCharsets.UTF_8));
        os.flush();
        sseClients.add(os);
        // Hold the connection open until the JVM shuts down after the run completes.
        try {
            shutdownLatch.await();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        try { ex.close(); } catch (Exception ignored) { }
    }

    // ---- Pipeline driver ----------------------------------------------------

    private void runPipeline(List<String> ids, java.util.Set<String> geoIds, Map<String, String> overrides) {
        List<CityProvider> toProcess = new ArrayList<>();
        for (CityProvider p : allProviders) {
            if (ids.contains(p.getCityId())) toProcess.add(p);
        }
        WebListener listener = new WebListener();
        try {
            Pipeline.run(toProcess, allProviders, outDir, baseUrl, overrides, geoIds, listener);
        } catch (Exception e) {
            logger.error("Pipeline failed: {}", e.getMessage(), e);
            sendSse("done", "{\"processed\":0,\"failed\":" + toProcess.size() + ",\"fatal\":true}");
            scheduleShutdown(2500);
        }
    }

    /** Bridges pipeline callbacks to SSE events and finalises the run (report + auto-exit). */
    private class WebListener implements PipelineListener {
        /** cityId → [name, status, trees, error]. */
        private final Map<String, Object[]> results = new ConcurrentHashMap<>();
        private volatile int geoDone = 0;
        private volatile int geoFailed = 0;

        @Override public void onCityStart(String id, String name) {
            sendSse("city", cityJson(id, name, "running", 0, null));
        }
        @Override public void onCityDone(String id, String name, long trees) {
            results.put(id, new Object[]{name, "done", trees, null});
            sendSse("city", cityJson(id, name, "done", trees, null));
        }
        @Override public void onCityError(String id, String name, String error) {
            results.put(id, new Object[]{name, "error", 0L, error});
            sendSse("city", cityJson(id, name, "error", 0, error));
        }
        @Override public void onNote(String message) {
            ObjectNode o = mapper.createObjectNode();
            o.put("msg", message);
            sendSse("note", o.toString());
        }
        @Override public void onGeoCityDone(String id, String name, long places, long bytes) {
            geoDone++;
            results.put(id + "::geo", new Object[]{name + " (Geocoder)", "done", places, null});
            ObjectNode o = mapper.createObjectNode();
            o.put("id", id);
            o.put("status", "done");
            o.put("places", places);
            o.put("mb", Math.round(bytes / 1024.0 / 1024.0 * 10) / 10.0);
            sendSse("geo", o.toString());
        }
        @Override public void onGeoCityError(String id, String name, String error) {
            geoFailed++;
            results.put(id + "::geo", new Object[]{name + " (Geocoder)", "error", 0L, error});
            ObjectNode o = mapper.createObjectNode();
            o.put("id", id);
            o.put("status", "error");
            sendSse("geo", o.toString());
        }
        @Override public void onFinished(int processed, int failed) {
            try {
                writeStaticReport(results, processed, failed);
            } catch (Exception e) {
                logger.warn("Could not write last_run.html: {}", e.getMessage());
            }
            sendSse("done", "{\"processed\":" + processed + ",\"failed\":" + failed
                    + ",\"geoDone\":" + geoDone + ",\"geoFailed\":" + geoFailed + "}");
            scheduleShutdown(2500);
        }
    }

    private String cityJson(String id, String name, String status, long trees, String error) {
        ObjectNode o = mapper.createObjectNode();
        o.put("id", id);
        o.put("name", name);
        o.put("status", status);
        if ("done".equals(status)) o.put("trees", trees);
        if (error != null) o.put("error", error);
        return o.toString();
    }

    // ---- SSE ----------------------------------------------------------------

    private synchronized void sendSse(String event, String data) {
        byte[] payload = ("event: " + event + "\ndata: " + data + "\n\n").getBytes(StandardCharsets.UTF_8);
        for (OutputStream os : sseClients) {
            try {
                os.write(payload);
                os.flush();
            } catch (IOException e) {
                sseClients.remove(os);
            }
        }
    }

    /**
     * Beendet den Prozess verzögert. Die Verzögerung gibt dem Browser Zeit, das
     * abschließende SSE-Ereignis (z. B. {@code done}) noch zu empfangen, bevor
     * {@code System.exit(0)} die Verbindung kappt — sonst bliebe das UI im
     * "läuft"-Zustand hängen. 2500 ms nach Pipeline-Ende, 600 ms beim manuellen
     * UI-Shutdown (da wartet kein längerer Datenstrom mehr).
     */
    private void scheduleShutdown(long delayMs) {
        if (!shuttingDown.compareAndSet(false, true)) return; // nur einmal auslösen
        Thread t = new Thread(() -> {
            try { Thread.sleep(delayMs); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            logger.info("Runner beendet sich.");
            shutdownLatch.countDown();
            System.exit(0);
        }, "shutdown");
        t.setDaemon(false);
        t.start();
    }

    // ---- Static report ------------------------------------------------------

    private void writeStaticReport(Map<String, Object[]> results, int processed, int failed) throws IOException {
        File repoRoot = outDir.getAbsoluteFile().getParentFile().getParentFile();
        File report = new File(repoRoot, "last_run.html");
        String when = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));

        StringBuilder rows = new StringBuilder();
        List<String> ids = new ArrayList<>(results.keySet());
        ids.sort(Comparator.naturalOrder());
        for (String id : ids) {
            Object[] r = results.get(id);
            String name = esc(String.valueOf(r[0]));
            String status = String.valueOf(r[1]);
            long trees = (Long) r[2];
            boolean ok = "done".equals(status);
            rows.append("<tr><td>").append(name).append("</td><td class='")
                .append(ok ? "ok" : "err").append("'>")
                .append(ok ? "✓ fertig" : "✗ Fehler").append("</td><td class='num'>")
                .append(ok ? String.format("%,d", trees) : "—").append("</td></tr>\n");
        }

        String html = "<!doctype html><html lang=de><head><meta charset=utf-8>"
            + "<title>BaumRadar — Letzter Lauf</title><link rel=icon href=\"" + faviconDataUri + "\">"
            + "<style>body{font-family:system-ui,Segoe UI,Roboto,sans-serif;background:#f4f7f4;color:#1b2a1e;"
            + "max-width:640px;margin:0 auto;padding:28px 20px}header{display:flex;align-items:center;gap:12px}"
            + "img{width:44px;height:44px;border-radius:9px}h1{font-size:1.3rem;margin:0}"
            + ".sub{color:#5b6b5e;font-size:.9rem;margin:4px 0 0}"
            + "table{width:100%;border-collapse:collapse;margin-top:18px;background:#fff;border:1px solid #d8e3d9;border-radius:12px;overflow:hidden}"
            + "td,th{padding:9px 12px;border-top:1px solid #eef3ee;text-align:left}th{background:#e8f5e9;color:#1b5e20}"
            + ".num{text-align:right;font-variant-numeric:tabular-nums}.ok{color:#1b5e20}.err{color:#b3261e}"
            + ".done{background:#e8f5e9;border:1px solid #b6ddb9;color:#1b5e20;border-radius:12px;padding:12px 14px;margin-top:18px}</style></head><body>"
            + "<header><img src=\"" + logoDataUri + "\" alt=BaumRadar><div><h1>BaumRadar — Letzter Lauf</h1>"
            + "<p class=sub>" + when + "</p></div></header>"
            + "<div class=done>✓ Abgeschlossen: " + processed + " Stadt/Städte verarbeitet"
            + (failed > 0 ? (", <b>" + failed + " fehlgeschlagen</b>") : "")
            + ". Der Runner hat sich danach selbst beendet.</div>"
            + "<table><tr><th>Stadt</th><th>Status</th><th class=num>Datensätze</th></tr>\n" + rows + "</table>"
            + "</body></html>\n";

        try (FileWriter w = new FileWriter(report, StandardCharsets.UTF_8)) {
            w.write(html);
        }
        logger.info("Übersicht geschrieben: {}", report.getAbsolutePath());
    }

    // ---- Helpers ------------------------------------------------------------

    private void openBrowser(String url) {
        if (System.getenv("BAUMRADAR_NO_BROWSER") != null) {
            logger.info("Bitte im Browser öffnen: {}", url);
            return;
        }
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (Exception ignored) { }
        logger.info("Bitte im Browser öffnen: {}", url);
    }

    private String dataUri(String resource, String mime) {
        try {
            byte[] bytes = readResourceBytes(resource);
            return "data:" + mime + ";base64," + java.util.Base64.getEncoder().encodeToString(bytes);
        } catch (Exception e) {
            return "";
        }
    }

    private String readResourceText(String resource) throws IOException {
        return new String(readResourceBytes(resource), StandardCharsets.UTF_8);
    }

    private byte[] readResourceBytes(String resource) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            if (in == null) throw new IOException("Resource not found: " + resource);
            return in.readAllBytes();
        }
    }

    private void sendText(HttpExchange ex, int code, String contentType, String body) throws IOException {
        sendBytes(ex, code, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    private void sendBytes(HttpExchange ex, int code, String contentType, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
