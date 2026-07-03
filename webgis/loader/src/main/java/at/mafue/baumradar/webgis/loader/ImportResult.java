package at.mafue.baumradar.webgis.loader;

/** Ergebnis eines Stadt-Imports für die Abschluss-Zusammenfassung. */
record ImportResult(String cityId, Status status, int trees, int zones, String error) {

    enum Status { IMPORTED, SKIPPED, FAILED }

    static ImportResult imported(String cityId, int trees, int zones) {
        return new ImportResult(cityId, Status.IMPORTED, trees, zones, null);
    }

    static ImportResult skipped(String cityId) {
        return new ImportResult(cityId, Status.SKIPPED, 0, 0, null);
    }

    static ImportResult failed(String cityId, String error) {
        return new ImportResult(cityId, Status.FAILED, 0, 0, error);
    }
}
