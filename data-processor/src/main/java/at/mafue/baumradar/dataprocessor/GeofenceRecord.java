package at.mafue.baumradar.dataprocessor;

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
