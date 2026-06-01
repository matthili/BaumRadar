package at.mafue.baumradar.dataprocessor.utils;

import at.mafue.baumradar.dataprocessor.models.GeofenceRecord;

import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Clusters individual trees into spatial geofence zones using a two-pass algorithm:
 * <ol>
 *   <li><b>Grid pass:</b> Trees of the same genusDe within the same 0.001° grid cell (~100m)
 *       are grouped together.</li>
 *   <li><b>Merge pass:</b> Adjacent grid clusters of the same genusDe are merged if the
 *       resulting cluster radius stays within {@value #MAX_MERGE_RADIUS_M} meters.
 *       This eliminates artifacts caused by rigid grid boundaries splitting nearby tree groups.</li>
 * </ol>
 */
public class GeofenceClusterer {

    private static final Logger logger = LoggerFactory.getLogger(GeofenceClusterer.class);

    /** Maximum allowed radius (in meters) for a merged cluster. */
    private static final double MAX_MERGE_RADIUS_M = 150.0;
    private static final double EARTH_RADIUS_M = 6_371_000.0;

    private final Map<String, GridCluster> gridClusters = new HashMap<>();

    /**
     * Registers a single tree for clustering.
     *
     * <p>The tree's coordinates are quantized to a 0.001° grid (~111 m latitude,
     * ~70 m longitude at 50°N) and combined with its genus to form a composite
     * grid-cell key. All trees sharing the same key accumulate in one
     * {@link GridCluster}, tracking count, coordinate sums, and bounding box.
     *
     * @param lat     WGS-84 latitude
     * @param lon     WGS-84 longitude
     * @param genusDe German genus name used as part of the clustering key
     */
    public void addTree(double lat, double lon, String genusDe) {
        long latIdx = Math.round(lat * 1000.0);
        long lonIdx = Math.round(lon * 1000.0);
        String clusterKey = genusDe + "|" + latIdx + "|" + lonIdx;

        GridCluster c = gridClusters.computeIfAbsent(clusterKey,
                k -> new GridCluster(genusDe, latIdx, lonIdx));
        c.count++;
        c.sumLat += lat;
        c.sumLon += lon;
        c.minLat = Math.min(c.minLat, lat);
        c.maxLat = Math.max(c.maxLat, lat);
        c.minLon = Math.min(c.minLon, lon);
        c.maxLon = Math.max(c.maxLon, lon);
    }

    /**
     * Build final geofence records after merging adjacent clusters.
     */
    public List<GeofenceRecord> buildGeofences(String cityId) {
        int before = gridClusters.size();
        List<MergedCluster> merged = mergePass();
        logger.info("Merge pass: {} grid clusters -> {} merged clusters", before, merged.size());

        List<GeofenceRecord> result = new ArrayList<>();
        for (MergedCluster mc : merged) {
            double centerLat = mc.sumLat / mc.count;
            double centerLon = mc.sumLon / mc.count;
            double maxDist = maxDistFromCenter(centerLat, centerLon, mc);
            // Dynamic radius: actual max extent from centroid plus a 20 m safety
            // buffer, with an absolute minimum of 50 m so tiny clusters still
            // trigger the geofence reliably on mobile devices.
            int radius = Math.max(50, (int) Math.ceil(maxDist) + 20);

            result.add(new GeofenceRecord(
                    UUID.randomUUID().toString(), cityId,
                    centerLat, centerLon, radius, mc.count, mc.genusDe
            ));
        }
        return result;
    }

    // ── Merge algorithm ──────────────────────────────────────────────────

    private List<MergedCluster> mergePass() {
        // Group grid clusters by genusDe
        Map<String, List<GridCluster>> byGenus = new HashMap<>();
        for (GridCluster gc : gridClusters.values()) {
            byGenus.computeIfAbsent(gc.genusDe, k -> new ArrayList<>()).add(gc);
        }

        List<MergedCluster> allResult = new ArrayList<>();

        for (List<GridCluster> genusClusters : byGenus.values()) {
            // Create one MergedCluster per GridCluster
            List<MergedCluster> mcList = new ArrayList<>();
            for (GridCluster gc : genusClusters) {
                mcList.add(new MergedCluster(gc));
            }

            // Spatial index: "latIdx|lonIdx" -> owning MergedCluster
            Map<String, MergedCluster> index = new HashMap<>();
            for (MergedCluster mc : mcList) {
                for (String gk : mc.gridKeys) {
                    index.put(gk, mc);
                }
            }

            // Iterative merge until stable
            boolean changed = true;
            while (changed) {
                changed = false;
                for (MergedCluster mc : mcList) {
                    if (mc.absorbed) continue;
                    // Check 8 neighbors of every grid cell owned by this cluster
                    for (String gk : new ArrayList<>(mc.gridKeys)) {
                        String[] parts = gk.split("\\|");
                        long latI = Long.parseLong(parts[0]);
                        long lonI = Long.parseLong(parts[1]);

                        for (int dLat = -1; dLat <= 1; dLat++) {
                            for (int dLon = -1; dLon <= 1; dLon++) {
                                if (dLat == 0 && dLon == 0) continue;
                                String nk = (latI + dLat) + "|" + (lonI + dLon);
                                MergedCluster neighbor = index.get(nk);
                                if (neighbor == null || neighbor == mc || neighbor.absorbed) continue;
                                if (canMerge(mc, neighbor)) {
                                    // Absorb neighbor into mc
                                    for (String key : neighbor.gridKeys) {
                                        index.put(key, mc);
                                    }
                                    mc.absorb(neighbor);
                                    changed = true;
                                }
                            }
                        }
                    }
                }
            }

            for (MergedCluster mc : mcList) {
                if (!mc.absorbed) allResult.add(mc);
            }
        }
        return allResult;
    }

    /**
     * Tests whether two clusters can be merged without exceeding the
     * maximum allowed radius.
     *
     * <p>A hypothetical merged bounding box is computed and the farthest
     * corner distance from the new centroid is measured.  If that distance
     * is within {@value #MAX_MERGE_RADIUS_M} meters, merging is safe.
     */
    private boolean canMerge(MergedCluster a, MergedCluster b) {
        // Compute hypothetical merged bounding box
        double minLat = Math.min(a.minLat, b.minLat);
        double maxLat = Math.max(a.maxLat, b.maxLat);
        double minLon = Math.min(a.minLon, b.minLon);
        double maxLon = Math.max(a.maxLon, b.maxLon);
        int totalCount = a.count + b.count;
        double centerLat = (a.sumLat + b.sumLat) / totalCount;
        double centerLon = (a.sumLon + b.sumLon) / totalCount;

        // Max distance from centroid to any bounding-box corner
        double d1 = haversine(centerLat, centerLon, minLat, minLon);
        double d2 = haversine(centerLat, centerLon, minLat, maxLon);
        double d3 = haversine(centerLat, centerLon, maxLat, minLon);
        double d4 = haversine(centerLat, centerLon, maxLat, maxLon);
        double maxDist = Math.max(Math.max(d1, d2), Math.max(d3, d4));

        return maxDist <= MAX_MERGE_RADIUS_M;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * Computes the maximum Haversine distance from the centroid to any corner
     * of the cluster's bounding box.  Used to determine the geofence radius.
     */
    private static double maxDistFromCenter(double centerLat, double centerLon, MergedCluster mc) {
        double d1 = haversine(centerLat, centerLon, mc.minLat, mc.minLon);
        double d2 = haversine(centerLat, centerLon, mc.minLat, mc.maxLon);
        double d3 = haversine(centerLat, centerLon, mc.maxLat, mc.minLon);
        double d4 = haversine(centerLat, centerLon, mc.maxLat, mc.maxLon);
        return Math.max(Math.max(d1, d2), Math.max(d3, d4));
    }

    /**
     * Haversine great-circle distance between two WGS-84 points, in meters.
     *
     * <p>Uses the standard formula:
     * {@code d = 2 · R · arcsin(√(sin²(Δlat/2) + cos(lat1) · cos(lat2) · sin²(Δlon/2)))}
     */
    private static double haversine(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                        Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.sqrt(a));
    }

    // ── Data classes ─────────────────────────────────────────────────────

    /**
     * Intermediate accumulator for all trees of a single genus that fall into
     * the same 0.001° grid cell during the first clustering pass.
     */
    private static class GridCluster {
        final String genusDe;
        final long latIdx, lonIdx;
        int count;
        double sumLat, sumLon;
        double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
        double minLon = Double.MAX_VALUE, maxLon = -Double.MAX_VALUE;

        GridCluster(String genusDe, long latIdx, long lonIdx) {
            this.genusDe = genusDe;
            this.latIdx = latIdx;
            this.lonIdx = lonIdx;
        }
    }

    /**
     * Result of the second (merge) pass: one or more adjacent
     * {@link GridCluster}s combined into a single spatial cluster.
     *
     * <p>The {@code absorbed} flag marks clusters that have been merged into
     * another {@link MergedCluster} and should be excluded from the final output.
     */
    private static class MergedCluster {
        final String genusDe;
        final Set<String> gridKeys = new HashSet<>();
        int count;
        double sumLat, sumLon;
        double minLat, maxLat, minLon, maxLon;
        boolean absorbed = false;

        MergedCluster(GridCluster gc) {
            this.genusDe = gc.genusDe;
            this.gridKeys.add(gc.latIdx + "|" + gc.lonIdx);
            this.count = gc.count;
            this.sumLat = gc.sumLat;
            this.sumLon = gc.sumLon;
            this.minLat = gc.minLat;
            this.maxLat = gc.maxLat;
            this.minLon = gc.minLon;
            this.maxLon = gc.maxLon;
        }

        /** Merges all statistics from {@code other} into this cluster and marks {@code other} as absorbed. */
        void absorb(MergedCluster other) {
            this.gridKeys.addAll(other.gridKeys);
            this.count += other.count;
            this.sumLat += other.sumLat;
            this.sumLon += other.sumLon;
            this.minLat = Math.min(this.minLat, other.minLat);
            this.maxLat = Math.max(this.maxLat, other.maxLat);
            this.minLon = Math.min(this.minLon, other.minLon);
            this.maxLon = Math.max(this.maxLon, other.maxLon);
            other.absorbed = true;
        }
    }
}
