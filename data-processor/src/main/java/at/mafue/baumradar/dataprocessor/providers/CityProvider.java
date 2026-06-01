package at.mafue.baumradar.dataprocessor.providers;

import at.mafue.baumradar.dataprocessor.providers.*;
import at.mafue.baumradar.dataprocessor.providers.austria.*;
import at.mafue.baumradar.dataprocessor.providers.germany.*;
import at.mafue.baumradar.dataprocessor.providers.switzerland.*;
import at.mafue.baumradar.dataprocessor.models.*;
import at.mafue.baumradar.dataprocessor.utils.*;

import java.io.File;

/**
 * Strategy interface for city-specific tree data importers.
 *
 * <p>Each supported city implements this interface to define how its
 * open-data portal is queried, how raw records are parsed, and what
 * metadata (name, country, bounding box) describes the city.  The
 * {@link at.mafue.baumradar.dataprocessor.Main} entry point iterates
 * over all registered providers and calls {@link #processData} in
 * parallel threads.
 *
 * @see AbstractCsvProvider
 * @see AbstractGeoJsonProvider
 */
public interface CityProvider {
    /** Returns a short, URL-safe identifier for the city (e.g. {@code "wien"}, {@code "berlin"}). */
    String getCityId();

    /** Returns the human-readable display name of the city (e.g. {@code "Wien"}, {@code "Zürich"}). */
    String getName();

    /** Returns the country name in the local language (e.g. {@code "Österreich"}). */
    String getCountry();

    /**
     * Returns the geographic bounding box as {@code [minLat, minLon, maxLat, maxLon]}.
     * The Android app uses this to set the initial map viewport when the city is selected.
     */
    double[] getBoundingBox();

    /**
     * Downloads, parses, and inserts all tree and geofence data for this city
     * into the provided {@link at.mafue.baumradar.dataprocessor.utils.DatabaseExporter}.
     *
     * @param exporter the open database exporter to write records into
     * @throws Exception if downloading or parsing fails
     */
    void processData(DatabaseExporter exporter) throws Exception;
}

