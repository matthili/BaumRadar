package at.mafue.baumradar.dataprocessor.providers;

import at.mafue.baumradar.dataprocessor.providers.*;
import at.mafue.baumradar.dataprocessor.providers.austria.*;
import at.mafue.baumradar.dataprocessor.providers.germany.*;
import at.mafue.baumradar.dataprocessor.providers.switzerland.*;
import at.mafue.baumradar.dataprocessor.models.*;
import at.mafue.baumradar.dataprocessor.utils.*;

import java.io.File;

public interface CityProvider {
    String getCityId();
    String getName();
    String getCountry();
    // In roughly [minLat, minLon, maxLat, maxLon] or similar. Used by app for starting map rect.
    double[] getBoundingBox();
    void processData(DatabaseExporter exporter) throws Exception;
}

