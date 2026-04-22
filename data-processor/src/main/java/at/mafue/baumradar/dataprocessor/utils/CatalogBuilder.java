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
import java.util.stream.Collectors;

public class CatalogBuilder {
    
    public static void build(File outputFile, List<CityProvider> providers, String baseUrl) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"version\": 1,\n");
        sb.append("  \"cities\": [\n");
        
        for (int i = 0; i < providers.size(); i++) {
            CityProvider p = providers.get(i);
            String dbUrl = baseUrl + p.getCityId() + ".db.gz";
            String sigUrl = baseUrl + p.getCityId() + ".db.gz.sig";
            double[] box = p.getBoundingBox();

            // Find chunks
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
            sb.append("      \"sigUrl\": \"").append(sigUrl).append("\"\n");
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

