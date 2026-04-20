package at.mafue.baumradar.dataprocessor;

import java.io.File;

public interface CityProvider {
    String getCityId();
    String getName();
    // In roughly [minLat, minLon, maxLat, maxLon] or similar. Used by app for starting map rect.
    double[] getBoundingBox();
    void processData(DatabaseExporter exporter) throws Exception;
}
