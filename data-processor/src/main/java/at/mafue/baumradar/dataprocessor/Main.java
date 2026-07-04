package at.mafue.baumradar.dataprocessor;

import at.mafue.baumradar.dataprocessor.providers.*;
import at.mafue.baumradar.dataprocessor.providers.austria.*;
import at.mafue.baumradar.dataprocessor.providers.germany.*;
import at.mafue.baumradar.dataprocessor.providers.switzerland.*;

import java.io.File;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Command-line entry point for the BaumRadar data-processing pipeline.
 *
 * <p>Downloads urban tree inventories from multiple European open-data portals
 * (one {@link CityProvider} per city), converts each into a per-city SQLite
 * database, and publishes the artifacts (compressed databases, Ed25519
 * signatures and a discovery catalog) to {@code docs/data/} for static hosting.
 *
 * <p><b>Selective runs:</b> pass one or more city ids to reprocess only those,
 * e.g. {@code --args="basel dortmund"}. Ids may be space- or comma-separated.
 * With no arguments all cities are processed. Either way {@code catalog.json} is
 * rebuilt over <em>all</em> cities (see {@link Pipeline}), so a partial run keeps
 * the others intact. The heavy lifting lives in {@link Pipeline}; this class only
 * resolves the output directory and turns the arguments into a provider subset.
 */
public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    /**
     * Base URL under which the compiled data files are published. The catalog
     * references this so the Android app knows where to download city databases.
     */
    private static final String BASE_URL =
            "https://raw.githubusercontent.com/matthili/BaumRadar/master/docs/data/";

    /** The full set of city providers. Order is irrelevant; the catalog lists every one with data. */
    static List<CityProvider> allProviders() {
        return Arrays.asList(
            new ViennaProvider(),
            new LinzProvider(),
            new BerlinProvider(),
            new BaselProvider(),
            new ZurichProvider(),
            new FreiburgProvider(),
            new DortmundProvider(),
            new HamburgProvider(),
            new RostockProvider(),
            new WuerzburgProvider(),
            new ZugProvider(),
            new LeipzigProvider(),
            new InnsbruckProvider(),
            new FrankfurtProvider(),
            new GrazProvider(),
            new KoelnProvider(),
            new StuttgartProvider(),
            new GelsenkirchenProvider(),
            new BonnProvider()
        );
    }

    public static void main(String[] args) {
        logger.info("Starting BaumRadar Data Processor Background Job...");

        File outDir = resolveOutputDir();
        List<CityProvider> all = allProviders();

        List<CityProvider> toProcess = selectProviders(all, args);
        if (toProcess == null) {
            System.exit(2); // unknown city id(s) — message already logged
            return;
        }

        try {
            // The CLI relies on the pipeline's own SLF4J logging for progress.
            Pipeline.run(toProcess, all, outDir, BASE_URL, PipelineListener.NOOP);
        } catch (Exception e) {
            logger.error("Fatal error in pipeline: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    /**
     * Resolves the {@code docs/data} output directory whether the job is started
     * from the project root (Android Studio) or the module directory (Gradle).
     */
    static File resolveOutputDir() {
        File outDir;
        if (new File("data-processor").exists() && new File("app").exists()) {
            outDir = new File("docs/data");            // project root
        } else {
            outDir = new File("../docs/data");          // data-processor module dir
        }
        if (!outDir.exists()) outDir.mkdirs();
        return outDir;
    }

    /**
     * Turns the command-line arguments into the subset of providers to process.
     * Empty arguments select all cities. Unknown ids abort the run (returns
     * {@code null}) after logging the offending ids and the list of known ones.
     */
    static List<CityProvider> selectProviders(List<CityProvider> all, String[] args) {
        Set<String> requested = new LinkedHashSet<>();
        for (String a : args) {
            if (a == null) continue;
            for (String tok : a.split("[,\\s]+")) {
                tok = tok.trim().toLowerCase();
                if (!tok.isEmpty()) requested.add(tok);
            }
        }
        if (requested.isEmpty()) return all; // no filter → all cities

        Set<String> known = all.stream().map(CityProvider::getCityId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<String> unknown = requested.stream().filter(r -> !known.contains(r)).collect(Collectors.toList());
        if (!unknown.isEmpty()) {
            logger.error("Unknown city id(s): {}. Known ids: {}", unknown, known);
            return null;
        }

        List<CityProvider> selected = all.stream()
                .filter(p -> requested.contains(p.getCityId()))
                .collect(Collectors.toList());
        logger.info("Selective run — processing only: {}",
                selected.stream().map(CityProvider::getCityId).collect(Collectors.toList()));
        return selected;
    }
}
