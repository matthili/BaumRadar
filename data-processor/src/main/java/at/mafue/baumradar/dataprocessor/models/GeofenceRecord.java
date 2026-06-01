package at.mafue.baumradar.dataprocessor.models;

import at.mafue.baumradar.dataprocessor.providers.*;
import at.mafue.baumradar.dataprocessor.providers.austria.*;
import at.mafue.baumradar.dataprocessor.providers.germany.*;
import at.mafue.baumradar.dataprocessor.providers.switzerland.*;
import at.mafue.baumradar.dataprocessor.models.*;
import at.mafue.baumradar.dataprocessor.utils.*;

/**
 * Immutable data-transfer object representing a spatial geofence cluster.
 *
 * <p>A geofence is a circular region defined by a center point
 * ({@code latitude}/{@code longitude}) and a {@code radius} in meters.
 * It aggregates a {@code count} of nearby trees that share the same
 * German genus name ({@code genusDe}). These clusters are produced by
 * {@link at.mafue.baumradar.dataprocessor.utils.GeofenceClusterer} and
 * persisted in the {@code geofences} table of the per-city SQLite database.
 *
 * <p>The Android app uses these records to trigger proximity notifications
 * without querying every individual tree row at runtime.
 */
public class GeofenceRecord {
    public final String id;
    public final String cityId;
    public final double latitude;
    public final double longitude;
    public final int radius;
    public final int count;
    public final String genusDe;

    public GeofenceRecord(String id, String cityId, double latitude, double longitude, int radius, int count, String genusDe) {
        this.id = id;
        this.cityId = cityId;
        this.latitude = latitude;
        this.longitude = longitude;
        this.radius = radius;
        this.count = count;
        this.genusDe = genusDe;
    }
}

