package at.mafue.baumradar.dataprocessor.providers;

import at.mafue.baumradar.dataprocessor.models.TreeRecord;
import at.mafue.baumradar.dataprocessor.providers.germany.KrznGmlProvider;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Parser tests for {@link AbstractGmlProvider}, using a GML snippet shaped like
 * the real KRZN response — including the multi-geometry that breaks that
 * service's GeoJSON writer and forced this second transport in the first place.
 */
public class GmlProviderTest {

    private static final String GML = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<wfs:FeatureCollection xmlns:wfs=\"http://www.opengis.net/wfs/2.0\""
        + " xmlns:gml=\"http://www.opengis.net/gml/3.2\""
        + " xmlns:gis=\"http://gis-integration.gis.krzn.de/gis\" numberReturned=\"3\">"
        + "<wfs:member><gis:svie_baum gml:id=\"ID_SVIE_BAUM59\">"
        + "<gis:GDO_GID>59</gis:GDO_GID><gis:AKTIV>1</gis:AKTIV>"
        + "<gis:BAUMART>Rosskastanie</gis:BAUMART>"
        + "<gis:BOTANISCHER_NAME>Aesculus hippocastanum</gis:BOTANISCHER_NAME>"
        + "<gis:GEOMETRY><gml:Point gml:id=\"G1\" srsName=\"urn:ogc:def:crs:EPSG::25832\">"
        + "<gml:pos>318368.0 5678901.0</gml:pos></gml:Point></gis:GEOMETRY>"
        + "</gis:svie_baum></wfs:member>"
        + "<wfs:member><gis:svie_baum gml:id=\"ID_SVIE_BAUM291\">"
        + "<gis:GDO_GID>291</gis:GDO_GID><gis:AKTIV>1</gis:AKTIV>"
        + "<gis:BAUMART>Eiche, Stiel-</gis:BAUMART>"
        + "<gis:BOTANISCHER_NAME>Quercus robur</gis:BOTANISCHER_NAME>"
        + "<gis:GEOMETRY><gml:MultiPoint gml:id=\"G2\" srsName=\"urn:ogc:def:crs:EPSG::25832\">"
        + "<gml:pointMember><gml:Point gml:id=\"G2a\"><gml:pos>317523.507 5682281.384</gml:pos></gml:Point></gml:pointMember>"
        + "<gml:pointMember><gml:Point gml:id=\"G2b\"><gml:pos>317530.100 5682290.900</gml:pos></gml:Point></gml:pointMember>"
        + "</gml:MultiPoint></gis:GEOMETRY>"
        + "</gis:svie_baum></wfs:member>"
        + "<wfs:member><gis:svie_baum gml:id=\"ID_SVIE_BAUM999\">"
        + "<gis:GDO_GID>999</gis:GDO_GID><gis:AKTIV>0</gis:AKTIV>"
        + "<gis:BAUMART>Feld-Ahorn</gis:BAUMART>"
        + "<gis:BOTANISCHER_NAME>Acer campestre</gis:BOTANISCHER_NAME>"
        + "<gis:GEOMETRY><gml:Point gml:id=\"G3\"><gml:pos>318000.0 5680000.0</gml:pos></gml:Point></gis:GEOMETRY>"
        + "</gis:svie_baum></wfs:member>"
        + "</wfs:FeatureCollection>";

    /**
     * WFS 1.0 profile (Winterthur): positions are {@code gml:coordinates} with a
     * comma, and every feature repeats them inside its own {@code gml:boundedBy}.
     */
    private static final String GML_WFS10 = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<wfs:FeatureCollection xmlns:wfs=\"http://www.opengis.net/wfs\""
        + " xmlns:gml=\"http://www.opengis.net/gml\" xmlns:ms=\"http://mapserver.gis.umn.edu/mapserver\">"
        + "<gml:featureMember><ms:BaumkatasterBaumstandort>"
        + "<gml:boundedBy><gml:Box srsName=\"EPSG:2056\">"
        + "<gml:coordinates>2697749.048,1260793.062 2697749.048,1260793.062</gml:coordinates></gml:Box></gml:boundedBy>"
        + "<ms:msGeometry><gml:Point srsName=\"EPSG:2056\">"
        + "<gml:coordinates>2697749.048000,1260793.062000</gml:coordinates></gml:Point></ms:msGeometry>"
        + "<ms:Baumnummer>27411</ms:Baumnummer>"
        + "<ms:Baumart_deutsch>Hainbuche, Weissbuche</ms:Baumart_deutsch>"
        + "<ms:Baumart_latein>Carpinus betulus</ms:Baumart_latein>"
        + "<ms:Pflanzjahr>2013</ms:Pflanzjahr>"
        + "</ms:BaumkatasterBaumstandort></gml:featureMember>"
        + "</wfs:FeatureCollection>";

    @Test
    public void readsGml2CoordinatesAndSwissGrid() throws Exception {
        AbstractGmlProvider winterthur =
            new at.mafue.baumradar.dataprocessor.providers.switzerland.WinterthurProvider();
        AbstractGmlProvider.Page page = winterthur.parse(
            new ByteArrayInputStream(GML_WFS10.getBytes(StandardCharsets.UTF_8)));

        assertEquals(1, page.featuresParsed);
        assertEquals(1, page.records.size());
        TreeRecord t = page.records.get(0);
        assertEquals("Hainbuche", t.genusDe);
        assertEquals("Hainbuche", t.speciesDe);          // Synonym hinter dem Komma verworfen
        assertEquals("Carpinus betulus", t.speciesEn);
        assertEquals(47.4906, t.latitude, 0.002);        // LV95 → WGS-84
        assertEquals(8.7358, t.longitude, 0.002);
    }

    /**
     * Returned as the base type on purpose: {@code parse} is package-private in
     * {@link AbstractGmlProvider}, and package-private members are not inherited
     * across package boundaries — via the subclass type it would be invisible here.
     */
    private static AbstractGmlProvider viersen() {
        return new KrznGmlProvider("viersen", "Viersen", "svie_baum",
            new double[]{51.2151, 6.2612, 51.3241, 6.4594});
    }

    @Test
    public void parsesFeaturesAndReprojectsUtm() throws Exception {
        AbstractGmlProvider.Page page = viersen().parse(
            new ByteArrayInputStream(GML.getBytes(StandardCharsets.UTF_8)));

        assertEquals(3, page.featuresParsed);           // alle drei Sätze gesehen …
        assertEquals(2, page.records.size());           // … der stillgelegte fliegt raus

        TreeRecord first = page.records.get(0);
        assertEquals("viersen_59", first.id);
        assertEquals("Aesculus hippocastanum", first.speciesEn);
        // EPSG:25832 → WGS-84: irgendwo im Viersener Raum, nicht der UTM-Rohwert.
        assertEquals(51.25, first.latitude, 0.35);
        assertEquals(6.36, first.longitude, 0.35);
    }

    @Test
    public void multiGeometryYieldsOneTreeAtItsFirstPosition() throws Exception {
        AbstractGmlProvider.Page page = viersen().parse(
            new ByteArrayInputStream(GML.getBytes(StandardCharsets.UTF_8)));

        TreeRecord multi = page.records.get(1);
        assertEquals("viersen_291", multi.id);
        assertEquals("Eiche", multi.genusDe);
        assertEquals("Stiel-Eiche", multi.speciesDe);   // invertierter Name zurückgedreht

        // Der zweite Punkt der Gruppe darf keinen zweiten Baum erzeugen.
        assertEquals(2, page.records.size());
    }
}
