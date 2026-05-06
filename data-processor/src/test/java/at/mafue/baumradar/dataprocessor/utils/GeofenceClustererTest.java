package at.mafue.baumradar.dataprocessor.utils;

import at.mafue.baumradar.dataprocessor.models.GeofenceRecord;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class GeofenceClustererTest {

    /**
     * Verifies the real-world scenario: 7 "Schmalblättrige Eschen" within ~200m
     * that were previously split into 6 grid clusters due to rigid grid boundaries.
     * After the merge pass, they should form at most 3 clusters.
     */
    @Test
    public void testEschenMerge_realWorldPositions() {
        GeofenceClusterer clusterer = new GeofenceClusterer();
        String genusDe = "Fraxinus angustifolia (Schmalblättrige Esche)";

        // Real positions from Wien.db
        clusterer.addTree(48.2025240045109, 16.3793620792106, genusDe);
        clusterer.addTree(48.202613877224,  16.3795202272573, genusDe);
        clusterer.addTree(48.2034177349412, 16.38173287846,   genusDe);
        clusterer.addTree(48.2034362982231, 16.3802546586758, genusDe);
        clusterer.addTree(48.2035661654295, 16.3804754030463, genusDe);
        clusterer.addTree(48.2036255949645, 16.3805160831187, genusDe);
        clusterer.addTree(48.2039908036559, 16.3816817263311, genusDe);

        List<GeofenceRecord> geofences = clusterer.buildGeofences("wien");

        // Previously: 6 clusters. After merge: should be significantly fewer.
        assertTrue("Expected at most 3 merged clusters, but got " + geofences.size(),
                geofences.size() <= 3);
        assertTrue("Expected at least 1 cluster",
                geofences.size() >= 1);

        // All 7 trees must be accounted for
        int totalTrees = geofences.stream().mapToInt(g -> g.count).sum();
        assertEquals("All 7 trees must be in the clusters", 7, totalTrees);

        // No cluster radius should exceed 170m (150m extent + 20m buffer)
        for (GeofenceRecord gf : geofences) {
            assertTrue("Cluster radius " + gf.radius + " exceeds 170m limit",
                    gf.radius <= 170);
        }
    }

    /**
     * Trees of different species in the same grid cell should NOT be merged.
     */
    @Test
    public void testDifferentSpecies_notMerged() {
        GeofenceClusterer clusterer = new GeofenceClusterer();

        clusterer.addTree(48.200, 16.380, "Fraxinus excelsior (Gemeine Esche)");
        clusterer.addTree(48.200, 16.380, "Acer platanoides (Spitzahorn)");

        List<GeofenceRecord> geofences = clusterer.buildGeofences("wien");
        assertEquals("Different species must stay in separate clusters", 2, geofences.size());
    }

    /**
     * Trees that are far apart (>150m radius) should NOT be merged
     * into a single oversized cluster.
     */
    @Test
    public void testDistantTrees_notOverMerged() {
        GeofenceClusterer clusterer = new GeofenceClusterer();
        String genusDe = "Fraxinus excelsior (Gemeine Esche)";

        // Place trees in a 500m line (~55m apart each)
        for (int i = 0; i < 10; i++) {
            double lat = 48.200 + i * 0.0005;
            clusterer.addTree(lat, 16.380, genusDe);
        }

        List<GeofenceRecord> geofences = clusterer.buildGeofences("wien");

        // With a 150m radius cap, a 500m line should produce at least 2 clusters
        assertTrue("A 500m line should produce at least 2 clusters, got " + geofences.size(),
                geofences.size() >= 2);

        // No cluster should have excessive radius
        for (GeofenceRecord gf : geofences) {
            assertTrue("Cluster radius " + gf.radius + " exceeds 170m limit",
                    gf.radius <= 170);
        }

        int totalTrees = geofences.stream().mapToInt(g -> g.count).sum();
        assertEquals("All 10 trees must be accounted for", 10, totalTrees);
    }

    /**
     * A single isolated tree should produce one geofence with minimum radius.
     */
    @Test
    public void testSingleTree_minimumRadius() {
        GeofenceClusterer clusterer = new GeofenceClusterer();
        clusterer.addTree(48.200, 16.380, "Tilia cordata (Winterlinde)");

        List<GeofenceRecord> geofences = clusterer.buildGeofences("wien");
        assertEquals(1, geofences.size());
        assertEquals("Single tree should get minimum 50m radius", 50, geofences.get(0).radius);
        assertEquals(1, geofences.get(0).count);
    }
}
