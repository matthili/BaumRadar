package at.mafue.baumradar.dataprocessor;

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
            String dbUrl = baseUrl + p.getCityId() + ".db";
            String sigUrl = baseUrl + p.getCityId() + ".db.sig";
            double[] box = p.getBoundingBox();

            sb.append("    {\n");
            sb.append("      \"id\": \"").append(p.getCityId()).append("\",\n");
            sb.append("      \"name\": \"").append(p.getName()).append("\",\n");
            
            sb.append("      \"boundingBox\": [");
            if (box != null && box.length == 4) {
                sb.append(box[0]).append(", ").append(box[1]).append(", ")
                  .append(box[2]).append(", ").append(box[3]);
            }
            sb.append("],\n");
            
            sb.append("      \"dbUrl\": \"").append(dbUrl).append("\",\n");
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
