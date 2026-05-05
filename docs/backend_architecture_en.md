# Backend Architecture (Data-Processor)

The backend of Baumradar (a Java project) is responsible for aggregating and processing Open Data from various cities. It essentially acts as a "Data Pipeline" or "Data Trust".

## Workflow and Data Ingestion

![Data Ingestion Workflow](architecture/02_data_ingestion.png)
![System Architecture Overview](architecture/01_system_architecture.png)

1. **City Providers**
   There are abstract base classes for different data formats (`AbstractGeoJsonProvider`, `AbstractCsvProvider`). Each city has its own implementation (e.g., `ViennaProvider`, `HamburgProvider`). These fetch data from the cities' Open Data portals (sometimes ZIP archives, sometimes direct APIs with pagination).

2. **Normalization & Translation**
   Tree species designations vary greatly from city to city. The `Translator` unifies botanical names and common names to a shared standard. Geo-coordinates in different projections (like UTM in Hamburg) are converted to standard WGS84 coordinates using the `UtmConverter`.

3. **Geofence Clustering**
   To optimize performance, the backend calculates geofence zones for trees of the same species that are close to each other. Instead of checking every single tree during routing, the app can later collide against these larger zones.

4. **Cryptographic Security & Splitting**
   The result is exported into a highly performant SQLite database and compressed using `GZIP`.
   - The `CryptoManager` cryptographically signs this file (Ed25519) using a Private Key. The signature (.sig file) and the database are saved.
   - If a database exceeds 50MB, it is split into chunks (`.001`, `.002`) to comply with GitHub file size limits.

![Backend Class Diagram](architecture/05_backend_classes.png)

[Back to Start](../README_en.md) | [Deutsche Version](backend_architecture.md)
