# Android App Architecture

The Baumradar Android App is a native Kotlin application built upon modern Jetpack Compose UI. It is designed to efficiently display massive amounts of data (like hundreds of thousands of trees) and make them available offline.

## Core Components

1. **City Manager & Data Synchronization**
   The app does not fetch individual trees via a REST API. Instead, it downloads complete, pre-built SQLite databases for selected cities.
   - The `CityManager` reads a `catalog.json` (provided by the backend).
   - It downloads compressed `.db.gz` files (for large cities, these are split into chunks like `.001`, `.002`).
   - Before the data is imported into the app's database (`Room`), the `SignatureVerifier` cryptographically verifies the data using a hardcoded public key.
   
   ![Synchronization Flow](architecture/03_app_sync.png)

2. **Routing & Collision Detection**
   The core feature for allergy sufferers is the routing capability. The app uses the `OsrmRoutingClient` to calculate paths.
   - Afterward, the `RouteCollisionDetector` checks if this path collides with geofence zones of allergenic trees.
   - A mathematical line intersection test based on the zone radius (plus a tolerance margin) is applied.
   
   ![Routing & Collision Algorithm](architecture/06_collision_activity.png)
   ![Routing Architecture](architecture/04_routing_collision.png)

3. **Room Database & Map Rendering**
   The imported SQLite trees are stored in a local Room database. Map markers are dynamically loaded and clustered based on the current viewport from the local database to preserve memory.

[Back to Start](../README_en.md) | [Deutsche Version](app_architecture.md)
