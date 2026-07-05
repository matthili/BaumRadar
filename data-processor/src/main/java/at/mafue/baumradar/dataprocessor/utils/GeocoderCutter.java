package at.mafue.baumradar.dataprocessor.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.luben.zstd.ZstdInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.GZIPOutputStream;

/**
 * Schneidet aus dem offiziellen Photon-Planet-Dump ({@code .jsonl.zst}, ~26 GB)
 * die Geocoder-Daten der BaumRadar-Städte heraus — <b>pro Stadt eine Datei</b>
 * ({@code geocoder_&lt;stadt&gt;.jsonl.gz}), analog zu den Baum-Datenbanken.
 *
 * <p>Dump-Format (verifiziert am echten Dump): eine Kopfzeile
 * ({@code {"type":"NominatimDumpFile",…}}), eine Länder-Zeile
 * ({@code {"type":"CountryInfo",…}}) und danach Batch-Zeilen
 * ({@code {"type":"Place","content":[…viele Orte…]}}). Jeder Ort trägt
 * {@code "centroid":[lon,lat]}. Der Cutter filtert die Batches gegen die
 * (um einen Rand erweiterten) Stadt-BBoxen und re-emittiert pro Stadt gültige
 * Dump-Dateien (Kopf + CountryInfo + gefilterte Batches), die Photon per
 * {@code -import-file} direkt importieren kann. Ein Ort im Überlappungsbereich
 * zweier Städte landet bewusst in beiden Dateien (jede Datei ist eigenständig);
 * beim Import mehrerer Städte in eine Instanz wird über {@code place_id}
 * dedupliziert (Photon-Dokument-IDs sind stabil).
 */
public final class GeocoderCutter {

    /** Quelle des Planet-Dumps (Photon 0.7–1.x JSONL, wöchentlich aktualisiert). */
    public static final String DUMP_URL =
            "https://download1.graphhopper.com/public/photon-dump-planet-1.0-latest.jsonl.zst";

    /** Dateiname des lokalen Dumps im git-ignorierten {@code geocoder/}-Ordner. */
    public static final String DUMP_FILENAME = "photon-dump-planet-1.0-latest.jsonl.zst";

    /** Rand um jede Stadt-BBox in Metern — findet auch „Flughafen vor der Stadt". */
    public static final double MARGIN_METERS = 15_000;

    /** Fortschrittsmeldung alle N gescannte Orte. */
    private static final long PROGRESS_EVERY = 20_000_000;

    private GeocoderCutter() {
    }

    /** Ergebnis je Stadt: Ausgabedatei (vor dem Chunking), Ortszahl und Inhalts-Version. */
    public record Result(File file, long places, long bytes, String version) {
    }

    /** Stadt-BBox, bereits um den Rand erweitert (Grenzen in Grad). */
    public record CityBox(String cityId, double minLat, double minLon, double maxLat, double maxLon) {

        /** Erzeugt die Box aus einer Katalog-BBox {@code [minLat,minLon,maxLat,maxLon]} + Rand. */
        public static CityBox of(String cityId, double[] boundingBox) {
            double midLat = (boundingBox[0] + boundingBox[2]) / 2.0;
            double dLat = MARGIN_METERS / 111_320.0;
            double dLon = MARGIN_METERS / (111_320.0 * Math.max(0.2, Math.cos(Math.toRadians(midLat))));
            return new CityBox(cityId,
                    boundingBox[0] - dLat, boundingBox[1] - dLon,
                    boundingBox[2] + dLat, boundingBox[3] + dLon);
        }

        boolean contains(double lon, double lat) {
            return lat >= minLat && lat <= maxLat && lon >= minLon && lon <= maxLon;
        }
    }

    /**
     * Stellt sicher, dass der Planet-Dump lokal vorliegt; lädt ihn sonst mit
     * Range-Resume herunter (ein abgebrochener Download wird fortgesetzt).
     */
    public static void ensureDump(File dumpFile, Consumer<String> note) throws IOException, InterruptedException {
        if (dumpFile.exists()) {
            note.accept(String.format("Planet-Dump vorhanden (%,d MB) — kein Download nötig.",
                    dumpFile.length() / (1024 * 1024)));
            return;
        }
        dumpFile.getParentFile().mkdirs();
        File part = new File(dumpFile.getParentFile(), dumpFile.getName() + ".part");
        long have = part.exists() ? part.length() : 0;
        note.accept(have > 0
                ? String.format("Setze Dump-Download bei %,d MB fort …", have / (1024 * 1024))
                : "Lade Planet-Dump (~26 GB) — das dauert eine Weile …");

        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(DUMP_URL));
        if (have > 0) req.header("Range", "bytes=" + have + "-");
        HttpResponse<InputStream> resp = client.send(req.build(), HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200 && resp.statusCode() != 206) {
            throw new IOException("Dump-Download fehlgeschlagen: HTTP " + resp.statusCode());
        }
        boolean append = resp.statusCode() == 206;
        long written = append ? have : 0;
        long nextNote = written + 512L * 1024 * 1024;
        try (InputStream in = resp.body();
             OutputStream out = new BufferedOutputStream(new FileOutputStream(part, append), 1 << 20)) {
            byte[] buf = new byte[1 << 16];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
                written += len;
                if (written >= nextNote) {
                    note.accept(String.format("Dump-Download: %,d MB …", written / (1024 * 1024)));
                    nextNote += 512L * 1024 * 1024;
                }
            }
        }
        if (!part.renameTo(dumpFile)) {
            throw new IOException("Konnte " + part + " nicht nach " + dumpFile + " umbenennen");
        }
        note.accept(String.format("Dump-Download abgeschlossen (%,d MB).", dumpFile.length() / (1024 * 1024)));
    }

    /**
     * Ein Streaming-Durchlauf über den Dump: filtert alle {@code cities} gleichzeitig
     * und schreibt pro Stadt {@code geocoder_<id>.jsonl.gz} nach {@code outDir}.
     *
     * @return Ergebnis je Stadt-ID (Städte ohne einen einzigen Treffer fehlen)
     */
    public static Map<String, Result> cut(File dumpFile, List<CityBox> cities, File outDir,
                                          Consumer<String> note) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, CityWriter> writers = new LinkedHashMap<>();
        byte[][] preamble = new byte[2][]; // [0] Kopfzeile, [1] CountryInfo

        long scanned = 0;
        long nextProgress = PROGRESS_EVERY;
        try (InputStream fin = new BufferedInputStream(new FileInputStream(dumpFile), 1 << 20);
             ZstdInputStream zin = new ZstdInputStream(fin)) {
            MappingIterator<ObjectNode> it = mapper.readerFor(ObjectNode.class).readValues(zin);
            while (it.hasNext()) {
                ObjectNode root = it.next();
                String type = root.path("type").asText("");
                if ("NominatimDumpFile".equals(type)) {
                    preamble[0] = mapper.writeValueAsBytes(root);
                    continue;
                }
                if ("CountryInfo".equals(type)) {
                    preamble[1] = mapper.writeValueAsBytes(root);
                    continue;
                }
                if (!"Place".equals(type)) continue;

                JsonNode content = root.path("content");
                if (!content.isArray()) continue;

                // Treffer je Stadt für DIESEN Batch sammeln (meist leer → billig).
                Map<String, ArrayNode> hits = null;
                for (JsonNode place : content) {
                    scanned++;
                    JsonNode centroid = place.path("centroid");
                    if (!centroid.isArray() || centroid.size() < 2) continue;
                    double lon = centroid.get(0).asDouble();
                    double lat = centroid.get(1).asDouble();
                    for (CityBox box : cities) {
                        if (box.contains(lon, lat)) {
                            if (hits == null) hits = new HashMap<>();
                            hits.computeIfAbsent(box.cityId(), k -> mapper.createArrayNode()).add(place);
                        }
                    }
                }
                if (scanned >= nextProgress) {
                    note.accept(String.format("Geocoder-Schnitt: %,d Mio Orte gescannt …", scanned / 1_000_000));
                    nextProgress += PROGRESS_EVERY;
                }
                if (hits == null) continue;

                for (Map.Entry<String, ArrayNode> e : hits.entrySet()) {
                    CityWriter w = writers.get(e.getKey());
                    if (w == null) {
                        w = new CityWriter(new File(outDir, "geocoder_" + e.getKey() + ".jsonl.gz"), preamble);
                        writers.put(e.getKey(), w);
                    }
                    ObjectNode line = mapper.createObjectNode();
                    line.put("type", "Place");
                    line.set("content", e.getValue());
                    w.writeLine(mapper.writeValueAsBytes(line));
                    w.places += e.getValue().size();
                }
            }
        }

        Map<String, Result> results = new LinkedHashMap<>();
        for (Map.Entry<String, CityWriter> e : writers.entrySet()) {
            CityWriter w = e.getValue();
            w.close();
            results.put(e.getKey(), new Result(w.file, w.places, w.uncompressedBytes, w.version()));
        }
        note.accept(String.format("Geocoder-Schnitt fertig: %,d Mio Orte gescannt, %d Stadt-Dateien.",
                scanned / 1_000_000, results.size()));
        return results;
    }

    /** Gzip-Ausgabe einer Stadt inkl. laufender SHA-256 über die unkomprimierten Bytes. */
    private static final class CityWriter {
        final File file;
        final OutputStream out;
        final MessageDigest digest;
        long places = 0;
        long uncompressedBytes = 0;

        CityWriter(File file, byte[][] preamble) throws IOException {
            this.file = file;
            if (file.exists()) file.delete();
            this.out = new GZIPOutputStream(new BufferedOutputStream(new FileOutputStream(file), 1 << 18));
            try {
                this.digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 unavailable", e);
            }
            // Jede Stadt-Datei ist ein eigenständiger, Photon-importierbarer Dump:
            // Kopfzeile + CountryInfo müssen vorangehen.
            if (preamble[0] != null) writeLine(preamble[0]);
            if (preamble[1] != null) writeLine(preamble[1]);
        }

        void writeLine(byte[] jsonBytes) throws IOException {
            out.write(jsonBytes);
            out.write('\n');
            digest.update(jsonBytes);
            digest.update((byte) '\n');
            uncompressedBytes += jsonBytes.length + 1;
        }

        void close() throws IOException {
            out.close();
        }

        /** Inhalts-Version: 16 Hex-Zeichen der SHA-256 über die unkomprimierten Zeilen. */
        String version() {
            String hex = new BigInteger(1, digest.digest()).toString(16);
            while (hex.length() < 16) hex = "0" + hex;
            return hex.substring(0, 16);
        }
    }

    /** Baut die (um den Rand erweiterten) Boxen für die angefragten Städte. */
    public static List<CityBox> boxesFor(List<at.mafue.baumradar.dataprocessor.providers.CityProvider> providers) {
        List<CityBox> boxes = new ArrayList<>();
        for (var p : providers) {
            boxes.add(CityBox.of(p.getCityId(), p.getBoundingBox()));
        }
        return boxes;
    }
}
