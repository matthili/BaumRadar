package at.mafue.baumradar.dataprocessor.providers.germany;

import at.mafue.baumradar.dataprocessor.models.TreeRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Mapping tests for the German GeoJSON providers, using real sample features
 * captured from each city's live open-data endpoint. They lock the field-name
 * contract against the actual data so a portal schema change is caught here.
 */
public class GermanGeoJsonProviderTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode feature(String json) throws Exception {
        return mapper.readTree(json);
    }

    @Test
    public void rostockUsesGermanGenusAndDropsPlaceholders() throws Exception {
        String json = "{ \"type\": \"Feature\","
            + " \"properties\": { \"uuid\": \"fa3c43ce-8e5f-46e6-ac9d-aa950bf0f4a9\","
            + " \"gattung_botanisch\": \"Acer\", \"gattung_deutsch\": \"Ahorn\","
            + " \"art_botanisch\": \"Acer species\", \"art_deutsch\": \"Ahorn\" },"
            + " \"geometry\": { \"type\": \"Point\", \"coordinates\": [ 12.1164932, 54.0636702 ] } }";

        TreeRecord t = new RostockProvider().mapFeatureToTree(feature(json));
        assertNotNull(t);
        assertEquals("Ahorn", t.genusDe);
        assertEquals("Maple", t.genusEn);
        assertEquals("", t.speciesDe);   // art_deutsch == genus → dropped
        assertEquals("", t.speciesEn);   // "Acer species" placeholder → dropped
        assertEquals(54.0636702, t.latitude, 1e-7);
        assertEquals(12.1164932, t.longitude, 1e-7);
        assertTrue(t.id.startsWith("rostock_"));
    }

    @Test
    public void wuerzburgDerivesGenusFromLatinAndTitleCasesSpecies() throws Exception {
        String json = "{ \"type\": \"Feature\","
            + " \"geometry\": { \"type\": \"Point\", \"coordinates\": [ 9.9787462725, 49.7863399229 ] },"
            + " \"properties\": { \"baumart\": \"ESCHE RAYWOOD\","
            + " \"baumart_la\": \"Fraxinus angustifolia Raywood\", \"source_id\": \"68890\" } }";

        TreeRecord t = new WuerzburgProvider().mapFeatureToTree(feature(json));
        assertNotNull(t);
        assertEquals("Esche", t.genusDe);
        assertEquals("Ash", t.genusEn);
        assertEquals("Esche Raywood", t.speciesDe);   // ALL CAPS → Title Case
        assertEquals("Fraxinus angustifolia Raywood", t.speciesEn);
        assertTrue(t.id.startsWith("wuerzburg_"));
        assertEquals(49.7863399229, t.latitude, 1e-7);
        assertEquals(9.9787462725, t.longitude, 1e-7);
    }

    @Test
    public void leipzigReprojectsUtm33AndDerivesGenus() throws Exception {
        String json = "{ \"type\": \"Feature\", \"id\": \"Baeume.20230027\","
            + " \"geometry\": { \"type\": \"Point\", \"coordinates\": [ 318838.407366, 5693396.954682 ] },"
            + " \"properties\": { \"objectid\": 20230027, \"gattung\": \"Tilia\","
            + " \"ga_lang_wiss\": \"Tilia cordata 'Greenspire'\", \"ga_lang_deutsch\": \"Stadt-Linde\" } }";

        TreeRecord t = new LeipzigProvider().mapFeatureToTree(feature(json));
        assertNotNull(t);
        assertEquals("Linde", t.genusDe);
        assertEquals("Linden", t.genusEn);
        assertEquals("Stadt-Linde", t.speciesDe);
        assertEquals("Tilia cordata 'Greenspire'", t.speciesEn);
        assertEquals("leipzig_20230027", t.id);
        assertTrue("reprojected lat near Leipzig", t.latitude > 51.2 && t.latitude < 51.5);
        assertTrue("reprojected lon near Leipzig", t.longitude > 12.1 && t.longitude < 12.6);
    }

    @Test
    public void berlinUsesGermanGenusAndWgs84() throws Exception {
        String json = "{ \"type\": \"Feature\","
            + " \"geometry\": { \"type\": \"Point\", \"coordinates\": [ 13.44828415, 52.44315194 ] },"
            + " \"properties\": { \"pitid\": \"00008100:000bbafb\", \"gattung_deutsch\": \"Hainbuche\","
            + " \"gattung\": \"Carpinus\", \"art_dtsch\": \"Pyramiden-Hainbuche\","
            + " \"art_bot\": \"Carpinus betulus 'Fastigiata'\" } }";
        TreeRecord t = new BerlinProvider().mapFeatureToTree(feature(json), "baumbestand:strassenbaeume");
        assertNotNull(t);
        assertEquals("Hainbuche", t.genusDe);
        assertEquals("Hornbeam", t.genusEn);
        assertEquals("Pyramiden-Hainbuche", t.speciesDe);   // species preserved
        assertEquals("Carpinus betulus 'Fastigiata'", t.speciesEn);
        assertEquals("berlin_00008100:000bbafb", t.id);
        assertEquals(52.44315194, t.latitude, 1e-7);         // WGS-84, not UTM
        assertEquals(13.44828415, t.longitude, 1e-7);
    }

    @Test
    public void hamburgUsesGermanGenusAndWgs84MultiPoint() throws Exception {
        String json = "{ \"type\": \"Feature\", \"id\": \"HH_1\","
            + " \"geometry\": { \"type\": \"MultiPoint\", \"coordinates\": [ [ 9.846289287519417, 53.52547419808404 ] ] },"
            + " \"properties\": { \"baumid\": 100000117, \"gattung_deutsch\": \"Pappel\","
            + " \"gattung_latein\": \"Populus\", \"art_deutsch\": \"Kanadische Pappel\","
            + " \"art_latein\": \"Populus canadensis\" } }";
        TreeRecord t = new HamburgProvider().mapFeatureToTree(feature(json));
        assertNotNull(t);
        assertEquals("Pappel", t.genusDe);
        assertEquals("Poplar", t.genusEn);
        assertEquals("Kanadische Pappel", t.speciesDe);
        assertEquals("Populus canadensis", t.speciesEn);
        assertEquals(53.52547419808404, t.latitude, 1e-7);   // WGS-84 taken as-is, not converted
        assertEquals(9.846289287519417, t.longitude, 1e-7);
    }

    @Test
    public void freiburgNormalizesLatinGenus() throws Exception {
        String json = "{ \"type\": \"Feature\","
            + " \"geometry\": { \"type\": \"Point\", \"coordinates\": [ 7.809840219600273, 47.994310613803826 ] },"
            + " \"properties\": { \"baumart_botanisch\": \"Tilia  species\", \"baumart_deutsch\": \"Linde\" } }";
        TreeRecord t = new FreiburgProvider().mapFeatureToTree(feature(json));
        assertNotNull(t);
        assertEquals("Linde", t.genusDe);    // was "Tilia" before harmonization
        assertEquals("Linden", t.genusEn);
        assertEquals("Linde", t.speciesDe);
        assertEquals("Tilia  species", t.speciesEn);
    }

    @Test
    public void dortmundNormalizesUppercaseLatinGenus() throws Exception {
        String json = "{ \"type\": \"Feature\","
            + " \"geometry\": { \"type\": \"Point\", \"coordinates\": [ 7.491273571053557, 51.46985563642768 ] },"
            + " \"properties\": { \"id\": \"00008100:000972d5\", \"art_botani\": \"ACER PSEUDOPLATANUS\","
            + " \"art_deutsc\": \"BERG-AHORN  -  WEISS-AHORN\" } }";
        TreeRecord t = new DortmundProvider().mapFeatureToTree(feature(json));
        assertNotNull(t);
        assertEquals("Ahorn", t.genusDe);    // was "ACER" before harmonization
        assertEquals("Maple", t.genusEn);
        assertEquals("Acer pseudoplatanus", t.speciesEn);
        assertTrue("German species kept & cased", t.speciesDe.startsWith("Berg-Ahorn"));
    }

    @Test
    public void koelnReprojectsUtm32AndDerivesGenus() throws Exception {
        String json = "{ \"type\": \"Feature\","
            + " \"geometry\": { \"type\": \"Point\", \"coordinates\": [ 352668.7715999996, 5652168.7839 ] },"
            + " \"properties\": { \"Botanischer_Name\": \"Fraxinus excelsior \","
            + " \"Deutscher_Name\": \"Gemeine Esche\", \"Baumnummer\": \"66-604-S-0298\" } }";
        TreeRecord t = new KoelnProvider().mapFeatureToTree(feature(json));
        assertNotNull(t);
        assertEquals("Esche", t.genusDe);
        assertEquals("Ash", t.genusEn);
        assertEquals("Gemeine Esche", t.speciesDe);
        assertEquals("Fraxinus excelsior", t.speciesEn);   // trailing space trimmed
        assertTrue(t.id.startsWith("koeln_"));             // UUID (Baumnummer not unique)
        assertTrue("reprojected lat near Cologne", t.latitude > 50.8 && t.latitude < 51.1);
        assertTrue("reprojected lon near Cologne", t.longitude > 6.7 && t.longitude < 7.2);
    }

    @Test
    public void stuttgartDerivesGenusFromBotanicalWfs() throws Exception {
        String json = "{ \"type\": \"Feature\","
            + " \"geometry\": { \"type\": \"Point\", \"coordinates\": [ 9.1534, 48.8411 ] },"
            + " \"properties\": { \"BAID\": 61319, \"BAUMART\": \"Amerikanische Roteiche\","
            + " \"BAUMART_BOT\": \"Quercus rubra\" } }";
        TreeRecord t = new StuttgartProvider().mapFeatureToTree(feature(json));
        assertNotNull(t);
        assertEquals("Eiche", t.genusDe);
        assertEquals("Oak", t.genusEn);
        assertEquals("Amerikanische Roteiche", t.speciesDe);
        assertEquals("Quercus rubra", t.speciesEn);
        assertEquals("stuttgart_61319", t.id);
        assertEquals(48.8411, t.latitude, 1e-7);
        assertEquals(9.1534, t.longitude, 1e-7);
    }

    @Test
    public void gelsenkirchenParsesEsriJsonAttributesAndXy() throws Exception {
        // Esri JSON shape (f=json), NOT GeoJSON: attributes + geometry.x/y
        String json = "{ \"attributes\": { \"OBJECTID\": 1, \"Baumart\": \"Platanus acerifolia\","
            + " \"Baumart_dt\": \"Ahornblättrige Platane\" },"
            + " \"geometry\": { \"x\": 7.0983120821029635, \"y\": 51.50705905127519 } }";
        TreeRecord t = new GelsenkirchenProvider().mapFeatureToTree(feature(json));
        assertNotNull(t);
        assertEquals("Platane", t.genusDe);
        assertEquals("Plane Tree", t.genusEn);
        assertEquals("Ahornblättrige Platane", t.speciesDe);
        assertEquals("Platanus acerifolia", t.speciesEn);
        assertEquals("gelsenkirchen_1", t.id);
        assertEquals(51.50705905127519, t.latitude, 1e-7);   // geometry.y → lat
        assertEquals(7.0983120821029635, t.longitude, 1e-7); // geometry.x → lon
    }

    @Test
    public void bonnTrimsPaddedNamesAndDerivesGenus() throws Exception {
        // Source right-pads string fields heavily; the provider must trim them.
        String json = "{ \"type\": \"Feature\","
            + " \"geometry\": { \"type\": \"Point\", \"coordinates\": [ 7.1636804156, 50.6786305911 ] },"
            + " \"properties\": { \"baum_id\": 2,"
            + " \"lateinischer_name\": \"Sorbus intermedia                    \","
            + " \"deutscher_name\": \"Schwedische Mehlbeere            \", \"alter\": 47 } }";
        TreeRecord t = new BonnProvider().mapFeatureToTree(feature(json));
        assertNotNull(t);
        assertEquals("Mehlbeere", t.genusDe);
        assertEquals("Whitebeam", t.genusEn);
        assertEquals("Schwedische Mehlbeere", t.speciesDe);  // padding trimmed
        assertEquals("Sorbus intermedia", t.speciesEn);      // padding trimmed
        assertEquals("bonn_2", t.id);
        assertEquals(50.6786305911, t.latitude, 1e-7);
        assertEquals(7.1636804156, t.longitude, 1e-7);
    }

    // --- KRZN Niederrhein: ein Dienst, neun Kommunen -------------------------

    /** Krefeld writes canonical German names and carries AKTIV instead of GEFAELLT. */
    @Test
    public void krznKrefeldKeepsCanonicalNameAndUsesRowId() throws Exception {
        String json = "{ \"type\": \"Feature\","
            + " \"geometry\": { \"type\": \"Point\", \"coordinates\": [ 6.523587544077307, 51.34959653686794 ] },"
            + " \"properties\": { \"GDO_GID\": 68, \"AKTIV\": 1, \"BAUMART\": \"Feld-Ahorn\","
            + " \"BAUMNUMMER\": 9056, \"BOTANISCHER_NAME\": \"Acer campestre\", \"PFLANZJAHR\": 1990 } }";
        TreeRecord t = krefeld().mapFeatureToTree(feature(json));
        assertNotNull(t);
        assertEquals("Ahorn", t.genusDe);
        assertEquals("Maple", t.genusEn);
        assertEquals("Feld-Ahorn", t.speciesDe);
        assertEquals("Acer campestre", t.speciesEn);
        assertEquals("krefeld_68", t.id);                    // GDO_GID as stable key
        assertEquals(51.34959653686794, t.latitude, 1e-7);   // GeoJSON is lon/lat …
        assertEquals(6.523587544077307, t.longitude, 1e-7);  // … despite EPSG:25832 in the capabilities
    }

    /** The smaller municipalities invert their names — the provider flips them back. */
    @Test
    public void krznXantenFlipsInvertedGermanName() throws Exception {
        String json = "{ \"type\": \"Feature\","
            + " \"geometry\": { \"type\": \"Point\", \"coordinates\": [ 6.45535081177223, 51.64188982856247 ] },"
            + " \"properties\": { \"GDO_GID\": 11985, \"BAUMNUMMER\": \"027\", \"BAUMART\": \"Eiche, Stiel-\","
            + " \"BOTANISCHER_NAME\": \"Quercus robur\", \"GEFAELLT\": 0 } }";
        TreeRecord t = xanten().mapFeatureToTree(feature(json));
        assertNotNull(t);
        assertEquals("Eiche", t.genusDe);
        assertEquals("Stiel-Eiche", t.speciesDe);   // "Eiche, Stiel-" flipped
        assertEquals("xanten_11985", t.id);
    }

    /** "…, Art" is a placeholder for an unspecified species, not a name part. */
    @Test
    public void krznDropsArtPlaceholderFromSpeciesName() {
        assertEquals("Trompetenbaum", KrznProvider.normalizeGermanName("Trompetenbaum, Art"));
        assertEquals("Rot-Buche", KrznProvider.normalizeGermanName("Buche, Rot-"));
        assertEquals("Weiße Rosskastanie", KrznProvider.normalizeGermanName("Rosskastanie, Weiße"));
        assertEquals("Blutpflaume", KrznProvider.normalizeGermanName("Blutpflaume"));
    }

    /** Felled and retired records must not produce warnings about tree stumps. */
    @Test
    public void krznSkipsFelledAndRetiredTrees() throws Exception {
        String felled = "{ \"type\": \"Feature\","
            + " \"geometry\": { \"type\": \"Point\", \"coordinates\": [ 6.4553, 51.6418 ] },"
            + " \"properties\": { \"GDO_GID\": 1, \"BAUMART\": \"Eiche, Stiel-\","
            + " \"BOTANISCHER_NAME\": \"Quercus robur\", \"GEFAELLT\": 1 } }";
        assertNull(xanten().mapFeatureToTree(feature(felled)));

        String retired = "{ \"type\": \"Feature\","
            + " \"geometry\": { \"type\": \"Point\", \"coordinates\": [ 6.5235, 51.3495 ] },"
            + " \"properties\": { \"GDO_GID\": 2, \"AKTIV\": 0, \"BAUMART\": \"Feld-Ahorn\","
            + " \"BOTANISCHER_NAME\": \"Acer campestre\" } }";
        assertNull(krefeld().mapFeatureToTree(feature(retired)));
    }

    /** Safety net: should the service ever deliver its advertised EPSG:25832. */
    @Test
    public void krznReprojectsUtmCoordinatesIfDelivered() throws Exception {
        String json = "{ \"type\": \"Feature\","
            + " \"geometry\": { \"type\": \"Point\", \"coordinates\": [ 328000.0, 5690000.0 ] },"
            + " \"properties\": { \"GDO_GID\": 3, \"BAUMART\": \"Sand-Birke\","
            + " \"BOTANISCHER_NAME\": \"Betula pendula\" } }";
        TreeRecord t = krefeld().mapFeatureToTree(feature(json));
        assertNotNull(t);
        assertEquals("Birke", t.genusDe);
        assertEquals(51.3, t.latitude, 0.3);   // grob im Krefelder Raum statt UTM-Rohwert
        assertEquals(6.5, t.longitude, 0.4);
    }

    /** Wesel packt Latein und Deutsch durch ein Komma getrennt in ein Feld. */
    @Test
    public void weselSplitsBotanicalAndGermanNameAtTheComma() throws Exception {
        String json = "{ \"type\": \"Feature\","
            + " \"geometry\": { \"type\": \"Point\", \"coordinates\": [ 6.66487595, 51.66132052 ] },"
            + " \"properties\": { \"ID\": \"1\", \"GATTUNGART\": \"QR\", \"GATTUNG\": \"Quercus\","
            + " \"GA_LANG\": \"Quercus robur, Stieleiche\", \"ST_DURCHM\": \"47\" } }";
        TreeRecord t = new WeselProvider().mapFeatureToTree(feature(json));
        assertNotNull(t);
        assertEquals("Eiche", t.genusDe);
        assertEquals("Oak", t.genusEn);
        assertEquals("Stieleiche", t.speciesDe);        // hinter dem Komma
        assertEquals("Quercus robur", t.speciesEn);     // davor
        assertEquals("wesel_1", t.id);
        assertEquals(51.66132052, t.latitude, 1e-7);
    }

    /** ~100 Weseler Sätze lassen die Gattungsspalte leer — GA_LANG rettet sie. */
    @Test
    public void weselFallsBackToGaLangWhenGenusColumnIsEmpty() throws Exception {
        String json = "{ \"type\": \"Feature\","
            + " \"geometry\": { \"type\": \"Point\", \"coordinates\": [ 6.61, 51.66 ] },"
            + " \"properties\": { \"ID\": \"77\", \"GATTUNG\": \"\","
            + " \"GA_LANG\": \"Betula pendula, Sandbirke\" } }";
        TreeRecord t = new WeselProvider().mapFeatureToTree(feature(json));
        assertNotNull(t);
        assertEquals("Birke", t.genusDe);
        assertEquals("Sandbirke", t.speciesDe);
    }

    /** Sieben Viersener Sätze tragen Gauß-Krüger-Ostwerte in einer UTM-Ebene. */
    @Test
    public void krznRejectsCoordinatesOutsideTheMunicipality() throws Exception {
        // 3317423 statt 317423 (GK-Zonenpräfix) → als UTM gelesen landet der Baum
        // bei 45,18° N / 45,38° O, also weit außerhalb von Viersen.
        String json = "{ \"type\": \"Feature\","
            + " \"geometry\": { \"type\": \"Point\", \"coordinates\": [ 45.37812612167328, 45.187857354311106 ] },"
            + " \"properties\": { \"GDO_GID\": 15952, \"BAUMART\": \"Winter-Linde\","
            + " \"BOTANISCHER_NAME\": \"Tilia cordata\" } }";
        assertNull(viersenGeoJson().mapFeatureToTree(feature(json)));

        // Derselbe Baum an seiner echten Position wird übernommen.
        String ok = "{ \"type\": \"Feature\","
            + " \"geometry\": { \"type\": \"Point\", \"coordinates\": [ 6.3600, 51.2600 ] },"
            + " \"properties\": { \"GDO_GID\": 15952, \"BAUMART\": \"Winter-Linde\","
            + " \"BOTANISCHER_NAME\": \"Tilia cordata\" } }";
        TreeRecord t = viersenGeoJson().mapFeatureToTree(feature(ok));
        assertNotNull(t);
        assertEquals("Linde", t.genusDe);
    }

    private static KrznProvider viersenGeoJson() {
        return new KrznProvider("viersen", "Viersen", "svie_baum",
            new double[]{51.2151, 6.2612, 51.3241, 6.4594});
    }

    private static KrznProvider krefeld() {
        return new KrznProvider("krefeld", "Krefeld", "skre_baum",
            new double[]{51.2835, 6.4770, 51.4089, 6.7086});
    }

    private static KrznProvider xanten() {
        return new KrznProvider("xanten", "Xanten", "xant_baum",
            new double[]{51.6106, 6.3517, 51.7575, 6.5185});
    }
}
