package at.mafue.baumradar.webgis.loader;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * Provisioniert GeoServer idempotent per REST-API — „Konfiguration als Code"
 * statt Klick-Arbeit in der Admin-UI:
 *
 * <ol>
 *   <li>Workspace {@code baumradar}</li>
 *   <li>PostGIS-Datastore (zeigt auf den Compose-Service)</li>
 *   <li>Styles (SLD aus dem Klassenpfad; bei erneutem Lauf aktualisiert)</li>
 *   <li>FeatureTypes/Layer {@code trees} und {@code allergy_zones}</li>
 *   <li>Default-Style-Zuordnung</li>
 * </ol>
 *
 * Für Erstellen/Prüfen wird das stabile XML-Format der REST-API verwendet.
 */
final class GeoServerProvisioner {

    private static final String WORKSPACE = "baumradar";
    private static final String DATASTORE = "postgis";

    private final Config cfg;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final String authHeader;

    GeoServerProvisioner(Config cfg) {
        this.cfg = cfg;
        this.authHeader = "Basic " + Base64.getEncoder().encodeToString(
                (cfg.geoserverUser() + ":" + cfg.geoserverPassword()).getBytes(StandardCharsets.UTF_8));
    }

    void provision() throws IOException, InterruptedException {
        waitUntilReady();

        if (!exists("/rest/workspaces/" + WORKSPACE + ".xml")) {
            post("/rest/workspaces", "<workspace><name>" + WORKSPACE + "</name></workspace>");
            System.out.println("  [geoserver] Workspace angelegt: " + WORKSPACE);
        }

        if (!exists("/rest/workspaces/" + WORKSPACE + "/datastores/" + DATASTORE + ".xml")) {
            post("/rest/workspaces/" + WORKSPACE + "/datastores", datastoreXml());
            System.out.println("  [geoserver] PostGIS-Datastore angelegt");
        }

        ensureStyle("baumradar_trees", "/styles/trees.sld");
        ensureStyle("baumradar_zones", "/styles/allergy_zones.sld");

        ensureFeatureType("trees", "BaumRadar Bäume");
        ensureFeatureType("allergy_zones", "BaumRadar Allergiezonen");
        // Geometrielose Tabellen — nur via WFS sinnvoll (Filter/Suche des Web-Clients).
        ensureFeatureType("genus_stats", "BaumRadar Gattungs-Statistik");
        ensureFeatureType("genus_stats_city", "BaumRadar Gattungs-Statistik je Stadt");
        ensureFeatureType("species_stats", "BaumRadar Art-Statistik");

        // Generalisierungs-Layer für die Vektorkachel-Ansicht (MapLibre): gestylt
        // wird clientseitig (Style-JSON), deshalb bleiben die GeoServer-Defaults.
        ensureFeatureType("city_points", "BaumRadar Stadtpunkte (Übersicht)");
        ensureFeatureType("tree_cells_z8", "BaumRadar Rasterzellen (grob)");
        ensureFeatureType("tree_cells_z11", "BaumRadar Rasterzellen (mittel)");
        ensureFeatureType("tree_cells_z13", "BaumRadar Rasterzellen (fein)");

        setDefaultStyle("trees", "baumradar_trees");
        setDefaultStyle("allergy_zones", "baumradar_zones");

        for (String layer : List.of("trees", "allergy_zones", "city_points",
                "tree_cells_z8", "tree_cells_z11", "tree_cells_z13")) {
            ensureMvtTileLayer(layer);
        }

        System.out.println("  [geoserver] Provisionierung abgeschlossen");
        System.out.println("  [geoserver] WMS:  " + cfg.geoserverUrl() + "/" + WORKSPACE + "/wms");
        System.out.println("  [geoserver] WFS:  " + cfg.geoserverUrl() + "/" + WORKSPACE + "/wfs");
    }

    /** GeoServer braucht nach Container-Start (inkl. Extension-Install) ggf. Minuten. */
    private void waitUntilReady() throws InterruptedException {
        int attempts = 0;
        while (true) {
            attempts++;
            try {
                HttpResponse<Void> resp = http.send(
                        request("/rest/about/version.xml").GET().build(),
                        HttpResponse.BodyHandlers.discarding());
                if (resp.statusCode() == 200) return;
            } catch (IOException ignored) {
                // Noch nicht erreichbar — weiter warten.
            }
            if (attempts >= 150) {
                throw new IllegalStateException("GeoServer nicht erreichbar: " + cfg.geoserverUrl());
            }
            if (attempts % 15 == 0) {
                System.out.println("  [geoserver] warte auf Start ... (" + (attempts * 2) + "s)");
            }
            Thread.sleep(2000);
        }
    }

    private void ensureStyle(String name, String resourcePath) throws IOException, InterruptedException {
        String sld = readResource(resourcePath);
        if (exists("/rest/styles/" + name + ".xml")) {
            // Style existiert: Inhalt aktualisieren, damit SLD-Änderungen greifen.
            send(request("/rest/styles/" + name)
                    .header("Content-Type", "application/vnd.ogc.sld+xml")
                    .PUT(HttpRequest.BodyPublishers.ofString(sld)).build(), 200);
        } else {
            send(request("/rest/styles?name=" + name)
                    .header("Content-Type", "application/vnd.ogc.sld+xml")
                    .POST(HttpRequest.BodyPublishers.ofString(sld)).build(), 201);
            System.out.println("  [geoserver] Style angelegt: " + name);
        }
    }

    private void ensureFeatureType(String table, String title) throws IOException, InterruptedException {
        String base = "/rest/workspaces/" + WORKSPACE + "/datastores/" + DATASTORE + "/featuretypes";
        if (exists(base + "/" + table + ".xml")) return;
        String body = """
                <featureType>
                  <name>%s</name>
                  <nativeName>%s</nativeName>
                  <title>%s</title>
                  <srs>EPSG:4326</srs>
                  <enabled>true</enabled>
                </featureType>""".formatted(table, table, title);
        post(base, body);
        System.out.println("  [geoserver] Layer publiziert: " + WORKSPACE + ":" + table);
    }

    /**
     * Schaltet auf dem GWC-Kachel-Layer das MVT-Format frei (Vektorkacheln für
     * die MapLibre-Ansicht). Der Kachel-Layer selbst entsteht beim Publizieren
     * automatisch (Direct-WMS-Integration); hier wird nur seine Konfiguration
     * ersetzt — PUT ist idempotent. Meta-Kacheln bleiben aus (1×1): Vektor-
     * kacheln werden einzeln gerechnet, nicht als Bildverbund.
     */
    private void ensureMvtTileLayer(String layer) throws IOException, InterruptedException {
        String name = WORKSPACE + ":" + layer;
        String body = """
                <GeoServerLayer>
                  <enabled>true</enabled>
                  <name>%s</name>
                  <mimeFormats>
                    <string>application/vnd.mapbox-vector-tile</string>
                    <string>image/png</string>
                  </mimeFormats>
                  <gridSubsets>
                    <gridSubset><gridSetName>EPSG:900913</gridSetName></gridSubset>
                    <gridSubset><gridSetName>EPSG:4326</gridSetName></gridSubset>
                  </gridSubsets>
                  <metaWidthHeight><int>1</int><int>1</int></metaWidthHeight>
                </GeoServerLayer>""".formatted(name);
        send(request("/gwc/rest/layers/" + name + ".xml")
                .header("Content-Type", "application/xml")
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build(), 200);
    }

    private void setDefaultStyle(String layer, String style) throws IOException, InterruptedException {
        String body = "<layer><defaultStyle><name>" + style + "</name></defaultStyle></layer>";
        send(request("/rest/layers/" + WORKSPACE + ":" + layer)
                .header("Content-Type", "application/xml")
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build(), 200);
    }

    // --- kleine HTTP-Helfer ---------------------------------------------------

    private boolean exists(String path) throws IOException, InterruptedException {
        HttpResponse<Void> resp = http.send(request(path).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        return resp.statusCode() == 200;
    }

    private void post(String path, String xmlBody) throws IOException, InterruptedException {
        send(request(path)
                .header("Content-Type", "application/xml")
                .POST(HttpRequest.BodyPublishers.ofString(xmlBody)).build(), 201);
    }

    private void send(HttpRequest req, int expectedStatus) throws IOException, InterruptedException {
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != expectedStatus) {
            throw new IOException("GeoServer REST " + req.method() + " " + req.uri()
                    + " -> HTTP " + resp.statusCode() + ": " + resp.body());
        }
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(cfg.geoserverUrl() + path))
                .header("Authorization", authHeader)
                .timeout(Duration.ofSeconds(60));
    }

    private String datastoreXml() {
        return """
                <dataStore>
                  <name>%s</name>
                  <connectionParameters>
                    <entry key="dbtype">postgis</entry>
                    <entry key="host">%s</entry>
                    <entry key="port">%d</entry>
                    <entry key="database">%s</entry>
                    <entry key="schema">public</entry>
                    <entry key="user">%s</entry>
                    <entry key="passwd">%s</entry>
                    <entry key="Expose primary keys">true</entry>
                  </connectionParameters>
                </dataStore>""".formatted(DATASTORE, cfg.geoserverPgHost(), cfg.geoserverPgPort(),
                cfg.pgDb(), cfg.pgUser(), cfg.pgPassword());
    }

    private static String readResource(String path) throws IOException {
        try (InputStream in = GeoServerProvisioner.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException("Ressource fehlt: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
