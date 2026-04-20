package at.mafue.baumradar.dataprocessor;

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
