package at.mafue.baumradar.dataprocessor.models;

import at.mafue.baumradar.dataprocessor.providers.*;
import at.mafue.baumradar.dataprocessor.providers.austria.*;
import at.mafue.baumradar.dataprocessor.providers.germany.*;
import at.mafue.baumradar.dataprocessor.providers.switzerland.*;
import at.mafue.baumradar.dataprocessor.models.*;
import at.mafue.baumradar.dataprocessor.utils.*;

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

