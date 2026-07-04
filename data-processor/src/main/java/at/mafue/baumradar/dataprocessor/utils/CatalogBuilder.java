package at.mafue.baumradar.dataprocessor.utils;

import at.mafue.baumradar.dataprocessor.providers.*;
import at.mafue.baumradar.dataprocessor.providers.austria.*;
import at.mafue.baumradar.dataprocessor.providers.germany.*;
import at.mafue.baumradar.dataprocessor.providers.switzerland.*;
import at.mafue.baumradar.dataprocessor.models.*;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates the {@code catalog.json} discovery manifest consumed by the Android app.
 *
 * <p>The catalog lists every available city with its download URLs, signature
 * URL, bounding box, and — when the compressed database was split into
 * numbered parts — an array of chunk URLs.  The Android app fetches this
 * single JSON file on startup to determine which city databases are
 * available and where to download them.
 *
 * <p>JSON is assembled manually via {@link StringBuilder} to avoid adding
 * a runtime dependency on a JSON serialization library in the data-processor
 * module.
 *
 * <p><b>Selective runs:</b> {@link #build} always writes an entry for every
 * provider that has published files on disk — regardless of whether it was
 * (re)processed in the current run — so a single-city run does not drop the
 * other cities from the catalog. The per-city {@code dataVersion} is taken from
 * the {@code dataVersions} map, which the pipeline seeds from the previous
 * catalog (via {@link #readDataVersions}) and overwrites only for the cities it
 * actually reprocessed.
 */
public class CatalogBuilder {

    /** Regex over our own catalog format: pairs each {@code "id"} with its following {@code "dataVersion"}. */
    private static final Pattern ID_VERSION = Pattern.compile(
            "\"id\"\\s*:\\s*\"([^\"]+)\"[\\s\\S]*?\"dataVersion\"\\s*:\\s*\"([^\"]*)\"");

    /**
     * Reads {@code cityId → dataVersion} from an existing catalog file so that a
     * selective run can preserve the versions of cities it does not reprocess.
     * Returns an empty map if the file is missing or unreadable (first run).
     */
    public static Map<String, String> readDataVersions(File catalogFile) {
        Map<String, String> out = new HashMap<>();
        if (catalogFile == null || !catalogFile.exists()) return out;
        try {
            String json = Files.readString(catalogFile.toPath(), StandardCharsets.UTF_8);
            Matcher m = ID_VERSION.matcher(json);
            while (m.find()) out.put(m.group(1), m.group(2));
        } catch (IOException e) {
            // No prior versions available — treat as first run.
        }
        return out;
    }

    /**
     * Builds and writes the catalog JSON file over all {@code providers} that
     * currently have published files on disk.
     *
     * @param outputFile   the target catalog JSON file
     * @param providers    the full list of city providers (metadata source)
     * @param baseUrl      base URL prefix prepended to all file references
     * @param dataVersions per-city content fingerprint ({@code cityId → version});
     *                     emitted as {@code dataVersion} so the app can detect stale data
     * @throws IOException if writing the file fails
     */
    public static void build(File outputFile, List<CityProvider> providers, String baseUrl,
                             Map<String, String> dataVersions) throws IOException {
        File outDir = outputFile.getParentFile();
        List<String> blocks = new ArrayList<>();
        for (CityProvider p : providers) {
            // Only list cities that actually have published data on disk. This keeps
            // a never-run (or failed) provider out of the catalog instead of pointing
            // the app at a missing download.
            if (!cityHasData(outDir, p.getCityId())) continue;
            blocks.add(cityBlock(p, baseUrl, outDir, dataVersions));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"version\": 1,\n");
        sb.append("  \"cities\": [\n");
        sb.append(String.join(",\n", blocks));
        sb.append("\n  ]\n");
        sb.append("}\n");

        try (FileWriter writer = new FileWriter(outputFile)) {
            writer.write(sb.toString());
        }
    }

    /** True if the city has a published archive ({@code .db.gz}) or its first chunk. */
    private static boolean cityHasData(File outDir, String cityId) {
        return new File(outDir, cityId + ".db.gz").exists()
                || new File(outDir, cityId + ".db.gz.001").exists();
    }

    /** Renders one city's JSON object (indented, without trailing comma/newline). */
    private static String cityBlock(CityProvider p, String baseUrl, File outDir,
                                    Map<String, String> dataVersions) {
        String dbUrl = baseUrl + p.getCityId() + ".db.gz";
        String sigUrl = baseUrl + p.getCityId() + ".db.gz.sig";
        double[] box = p.getBoundingBox();

        // Detect numbered chunk files (e.g. wien.db.gz.001, .002, …) that were
        // produced when the compressed database exceeded 50 MB. Stops at the first gap.
        List<String> chunks = new ArrayList<>();
        for (int j = 1; j < 100; j++) {
            File chunkFile = new File(outDir, String.format("%s.db.gz.%03d", p.getCityId(), j));
            if (chunkFile.exists()) {
                chunks.add(baseUrl + chunkFile.getName());
            } else {
                break;
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("    {\n");
        sb.append("      \"id\": \"").append(p.getCityId()).append("\",\n");
        sb.append("      \"name\": \"").append(p.getName()).append("\",\n");
        sb.append("      \"country\": \"").append(p.getCountry()).append("\",\n");

        sb.append("      \"boundingBox\": [");
        if (box != null && box.length == 4) {
            sb.append(box[0]).append(", ").append(box[1]).append(", ")
              .append(box[2]).append(", ").append(box[3]);
        }
        sb.append("],\n");

        sb.append("      \"dbUrl\": \"").append(dbUrl).append("\",\n");
        if (!chunks.isEmpty()) {
            sb.append("      \"dbUrlChunks\": [");
            for (int c = 0; c < chunks.size(); c++) {
                sb.append("\"").append(chunks.get(c)).append("\"");
                if (c < chunks.size() - 1) sb.append(", ");
            }
            sb.append("],\n");
        }
        sb.append("      \"sigUrl\": \"").append(sigUrl).append("\",\n");
        String dataVersion = dataVersions == null ? null : dataVersions.get(p.getCityId());
        sb.append("      \"dataVersion\": \"").append(dataVersion == null ? "" : dataVersion).append("\"\n");
        sb.append("    }");
        return sb.toString();
    }
}
