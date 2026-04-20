package at.mafue.baumradar.dataprocessor;

import java.io.File;
import java.util.Arrays;
import java.util.List;

public class Main {
    private static final String PUB_KEY_FILE = "public_key.b64";
    // Default URL configured for GitHub Pages / Raw from user's repository
    private static final String BASE_URL = "https://raw.githubusercontent.com/matthili/BaumRadar/master/docs/data/";
    
    public static void main(String[] args) {
        System.out.println("Starting BaumRadar Data Processor...");

        File outDir = new File("../docs/data");
        if (!outDir.exists()) {
            outDir.mkdirs();
        }
        
        // Setup Providers
        List<CityProvider> providers = Arrays.asList(
            new ViennaProvider(),
            new LinzProvider()
        );
        
        try {
            System.out.println("1. Cryptographic Setup...");
            CryptoManager cryptoManager = new CryptoManager();
            cryptoManager.generateKeyPair();
            File pubKeyDest = new File(outDir, PUB_KEY_FILE);
            cryptoManager.savePublicKey(pubKeyDest);
            System.out.println("   Public key saved to " + pubKeyDest.getAbsolutePath());

            System.out.println("2. Processing Cities...");
            for (CityProvider provider : providers) {
                System.out.println("   -> Processing " + provider.getName() + "...");
                String dbFileName = provider.getCityId() + ".db";
                File dbFile = new File(outDir, dbFileName);
                if (dbFile.exists()) dbFile.delete();
                
                DatabaseExporter exporter = new DatabaseExporter(dbFile.getAbsolutePath());
                exporter.open();
                exporter.createTable();
                
                provider.processData(exporter);
                
                exporter.close();
                
                // Sign DB
                File sigFile = new File(outDir, dbFileName + ".sig");
                cryptoManager.signFile(dbFile, sigFile);
                System.out.println("   -> Created " + dbFileName + " and " + sigFile.getName());
            }
            
            System.out.println("3. Generating Catalog...");
            File catalogFile = new File(outDir, "catalog.json");
            CatalogBuilder.build(catalogFile, providers, BASE_URL);
            System.out.println("   Catalog created at " + catalogFile.getAbsolutePath());

            System.out.println("Done! Data is ready in " + outDir.getAbsolutePath());
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
