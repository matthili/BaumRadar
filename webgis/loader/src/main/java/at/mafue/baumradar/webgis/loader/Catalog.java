package at.mafue.baumradar.webgis.loader;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Abbild von docs/data/catalog.json — dem zentralen Stadtkatalog auf GitHub Pages.
 * Unbekannte Felder werden ignoriert, damit Katalog-Erweiterungen den Loader
 * nicht brechen.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Catalog(int version, List<City> cities) {

    /**
     * Ein Stadteintrag. {@code dbUrlChunks} ist nur bei Städten gesetzt, deren
     * Datenbank wegen der GitHub-Dateigrößen-Grenze in Teile gesplittet wurde
     * (die Teile sind einfach aneinanderzuhängende Byte-Abschnitte der .db.gz).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record City(
            String id,
            String name,
            String country,
            double[] boundingBox,
            String dbUrl,
            String sigUrl,
            List<String> dbUrlChunks,
            String dataVersion
    ) {}
}
