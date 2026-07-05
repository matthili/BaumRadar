package at.mafue.baumradar.dataprocessor.utils;

import com.github.luben.zstd.ZstdOutputStream;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.junit.Assert.*;

/**
 * Tests für den {@link GeocoderCutter}: Batch-Filterung gegen Stadt-BBoxen,
 * eigenständige (Photon-importierbare) Stadt-Dateien mit Kopf + CountryInfo,
 * und deterministische Inhalts-Versionen.
 */
public class GeocoderCutterTest {

    private static final String HEADER =
            "{\"type\":\"NominatimDumpFile\",\"content\":{\"version\":\"0.1.0\",\"generator\":\"photon\"}}";
    private static final String COUNTRY_INFO =
            "{\"type\":\"CountryInfo\",\"content\":[{\"country_code\":\"at\"},{\"country_code\":\"ch\"}]}";

    private static String place(String name, double lon, double lat) {
        return "{\"place_id\":\"" + Math.abs(name.hashCode()) + "\",\"osm_key\":\"place\"," +
                "\"name\":{\"name\":\"" + name + "\"},\"centroid\":[" + lon + "," + lat + "]}";
    }

    /** Schreibt einen synthetischen Dump im verifizierten Planet-Format. */
    private static File writeDump(File dir) throws Exception {
        File dump = new File(dir, "dump.jsonl.zst");
        String batch1 = "{\"type\":\"Place\",\"content\":[" +
                place("Stephansplatz", 16.372, 48.208) + "," +
                place("Zytturm", 8.515, 47.168) + "," +
                place("Irgendwo", 10.0, 50.0) + "]}";
        String batch2 = "{\"type\":\"Place\",\"content\":[" +
                place("Praterstern", 16.392, 48.218) + "]}";
        try (ZstdOutputStream out = new ZstdOutputStream(new FileOutputStream(dump))) {
            for (String line : new String[]{HEADER, COUNTRY_INFO, batch1, batch2}) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
                out.write('\n');
            }
        }
        return dump;
    }

    private static List<GeocoderCutter.CityBox> boxes() {
        List<GeocoderCutter.CityBox> boxes = new ArrayList<>();
        boxes.add(GeocoderCutter.CityBox.of("wien", new double[]{48.12, 16.18, 48.32, 16.58}));
        boxes.add(GeocoderCutter.CityBox.of("zug", new double[]{47.13, 8.45, 47.25, 8.60}));
        return boxes;
    }

    private static List<String> gunzipLines(File f) throws Exception {
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(new FileInputStream(f)), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) lines.add(line);
        }
        return lines;
    }

    @Test
    public void cutsPerCityWithPreambleAndCorrectPlaces() throws Exception {
        File dir = Files.createTempDirectory("cutter").toFile();
        File dump = writeDump(dir);
        Map<String, GeocoderCutter.Result> results =
                GeocoderCutter.cut(dump, boxes(), dir, msg -> { });

        assertEquals(2, results.size());
        assertEquals(2, results.get("wien").places());  // Stephansplatz + Praterstern
        assertEquals(1, results.get("zug").places());   // Zytturm

        List<String> wien = gunzipLines(results.get("wien").file());
        assertEquals(4, wien.size());                               // Kopf + CountryInfo + 2 Batches
        assertTrue(wien.get(0).contains("NominatimDumpFile"));      // Photon-importierbarer Kopf
        assertTrue(wien.get(1).contains("CountryInfo"));
        assertTrue(wien.get(2).contains("Stephansplatz"));
        assertFalse("fremde Stadt bleibt draußen", String.join("", wien).contains("Zytturm"));
        assertFalse("Nicht-Treffer bleibt draußen", String.join("", wien).contains("Irgendwo"));

        List<String> zug = gunzipLines(results.get("zug").file());
        assertEquals(3, zug.size());
        assertTrue(zug.get(2).contains("Zytturm"));
    }

    @Test
    public void versionIsDeterministicAcrossRuns() throws Exception {
        File dirA = Files.createTempDirectory("cutterA").toFile();
        File dirB = Files.createTempDirectory("cutterB").toFile();
        File dump = writeDump(dirA);

        Map<String, GeocoderCutter.Result> a = GeocoderCutter.cut(dump, boxes(), dirA, m -> { });
        Map<String, GeocoderCutter.Result> b = GeocoderCutter.cut(dump, boxes(), dirB, m -> { });

        assertEquals(16, a.get("wien").version().length());
        assertEquals(a.get("wien").version(), b.get("wien").version());
        assertEquals(a.get("zug").version(), b.get("zug").version());
        assertNotEquals("verschiedene Städte, verschiedene Versionen",
                a.get("wien").version(), a.get("zug").version());
    }

    @Test
    public void marginExpandsBoxes() {
        // Stadtgrenze bei 48.32 N — ein Ort 5 km nördlich liegt im 15-km-Rand.
        GeocoderCutter.CityBox box = GeocoderCutter.CityBox.of("wien",
                new double[]{48.12, 16.18, 48.32, 16.58});
        assertTrue(box.contains(16.37, 48.36));   // ~4,5 km außerhalb der BBox
        assertFalse(box.contains(16.37, 48.60));  // ~31 km außerhalb
    }
}
