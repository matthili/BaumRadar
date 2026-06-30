package at.mafue.baumradar.dataprocessor.utils;

import at.mafue.baumradar.dataprocessor.providers.*;
import at.mafue.baumradar.dataprocessor.providers.austria.*;
import at.mafue.baumradar.dataprocessor.providers.germany.*;
import at.mafue.baumradar.dataprocessor.providers.switzerland.*;
import at.mafue.baumradar.dataprocessor.models.*;
import at.mafue.baumradar.dataprocessor.utils.*;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
 */
public class CatalogBuilder {
    
    /**
     * Builds and writes the catalog JSON file.
     *
     * <p>For each {@link CityProvider} the method emits a JSON object containing:
     * <ul>
     *   <li>{@code id} / {@code name} / {@code country} — display metadata</li>
     *   <li>{@code boundingBox} — initial map viewport for the city</li>
     *   <li>{@code dbUrl} / {@code sigUrl} — download and signature URLs</li>
     *   <li>{@code dbUrlChunks} (optional) — ordered list of chunk URLs when
     *       the archive was split by {@link at.mafue.baumradar.dataprocessor.Main}</li>
     * </ul>
     *
     * @param outputFile   the target catalog JSON file
     * @param providers    list of city providers whose metadata is included
     * @param baseUrl      base URL prefix prepended to all file references
     * @param dataVersions per-city content fingerprint ({@code cityId → version});
     *                     emitted as {@code dataVersion} so the app can detect stale data
     * @throws IOException if writing the file fails
     */
    public static void build(File outputFile, List<CityProvider> providers, String baseUrl,
                             Map<String, String> dataVersions) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"version\": 1,\n");
        sb.append("  \"cities\": [\n");
        
        for (int i = 0; i < providers.size(); i++) {
            CityProvider p = providers.get(i);
            String dbUrl = baseUrl + p.getCityId() + ".db.gz";
            String sigUrl = baseUrl + p.getCityId() + ".db.gz.sig";
            double[] box = p.getBoundingBox();

            // Detect numbered chunk files (e.g. wien.db.gz.001, .002, …) that
            // were produced when the compressed database exceeded 50 MB.
            // Scanning stops at the first missing index.
            java.util.List<String> chunks = new java.util.ArrayList<>();
            File outDir = outputFile.getParentFile();
            for (int j = 1; j < 100; j++) {
                File chunkFile = new File(outDir, String.format("%s.db.gz.%03d", p.getCityId(), j));
                if (chunkFile.exists()) {
                    chunks.add(baseUrl + chunkFile.getName());
                } else {
                    break;
                }
            }

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
            if (i < providers.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }
        
        sb.append("  ]\n");
        sb.append("}\n");
        
        try (FileWriter writer = new FileWriter(outputFile)) {
            writer.write(sb.toString());
        }
    }
}

