package at.mafue.baumradar.webgis.loader;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Parst ein realitätsgetreues Katalog-Beispiel (Format von docs/data/catalog.json). */
class CatalogClientTest {

    @Test
    void parsesRealWorldCatalogShape() throws Exception {
        String json;
        try (var in = getClass().getResourceAsStream("/catalog-sample.json")) {
            assertNotNull(in, "Testressource catalog-sample.json fehlt");
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        Catalog catalog = CatalogClient.parse(json);

        assertEquals(1, catalog.version());
        assertEquals(2, catalog.cities().size());

        Catalog.City wien = catalog.cities().get(0);
        assertEquals("wien", wien.id());
        assertEquals("Österreich", wien.country());
        assertEquals("b251aa80a5bfa32b", wien.dataVersion());
        assertEquals(4, wien.boundingBox().length);
        assertNull(wien.dbUrlChunks(), "ohne Chunks muss das Feld null bleiben");

        Catalog.City chunked = catalog.cities().get(1);
        assertEquals(2, chunked.dbUrlChunks().size());
    }

    @Test
    void unknownFieldsAreIgnored() throws Exception {
        String json = """
                {"version": 1, "zukunftsfeld": true,
                 "cities": [{"id": "x", "name": "X", "country": "Y",
                             "boundingBox": [1,2,3,4],
                             "dbUrl": "u", "sigUrl": "s", "dataVersion": "v",
                             "nochEinNeuesFeld": 42}]}""";
        Catalog catalog = CatalogClient.parse(json);
        assertEquals("x", catalog.cities().get(0).id());
    }
}
