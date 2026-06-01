package at.mafue.baumradar.dataprocessor.models;

import at.mafue.baumradar.dataprocessor.providers.*;
import at.mafue.baumradar.dataprocessor.providers.austria.*;
import at.mafue.baumradar.dataprocessor.providers.germany.*;
import at.mafue.baumradar.dataprocessor.providers.switzerland.*;
import at.mafue.baumradar.dataprocessor.models.*;
import at.mafue.baumradar.dataprocessor.utils.*;

/**
 * Flat data-transfer object representing a single urban tree.
 *
 * <p>Each record carries the tree's GPS coordinates, a city identifier, and
 * bilingual genus/species names (German original plus English translation).
 * Instances are created by {@link at.mafue.baumradar.dataprocessor.providers.CityProvider}
 * implementations and subsequently batch-inserted into a per-city SQLite database
 * via {@link at.mafue.baumradar.dataprocessor.utils.DatabaseExporter#insertBatch}.
 *
 * <p>Fields are intentionally kept public for simple, POJO-style access without
 * boilerplate getters/setters.
 */
public class TreeRecord {
    public String id;
    public String cityId;
    public double latitude;
    public double longitude;
    public String genusDe;
    public String genusEn;
    public String speciesDe;
    public String speciesEn;

    public TreeRecord(String id, String cityId, double latitude, double longitude, String genusDe, String genusEn, String speciesDe, String speciesEn) {
        this.id = id;
        this.cityId = cityId;
        this.latitude = latitude;
        this.longitude = longitude;
        this.genusDe = genusDe;
        this.genusEn = genusEn;
        this.speciesDe = speciesDe;
        this.speciesEn = speciesEn;
    }
}

