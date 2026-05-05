*(Für die deutsche Dokumentation, siehe [README.md](README.md))*

# Baumradar 🌳

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

Welcome to **Baumradar**! This open-source project combines a robust Java backend (data-processor) with a modern Android app.

**Baumradar is an Open-Data-based tool that allows you to explore trees in your vicinity and intentionally avoid them when navigating through the city – especially helpful if you suffer from tree pollen allergies (e.g., early bloomers).**

## 🌟 Detailed Features

### 🌿 Allergy Profile & Warning Zones
In your personal allergy profile, you select the tree genera you are allergic to (e.g., Birch, Hazel, Ash). The app distinguishes between two levels:
- **"Avoid 🚫"**: These trees are considered during routing – the app tries to calculate routes that avoid these tree genera as much as possible.
- **"Warning ⚠️"**: For these trees, Baumradar registers background geofence zones with Android. You receive a **push notification directly on your lock screen** when you approach such a tree – even when the app is closed. No permanently running background app is required; Android monitors the zones energy-efficiently via Play Services.

### 🔍 Exploration Mode
Standing in front of a tree and wondering: *"What kind of tree is that?"* Activate the exploration mode (the magnifying glass icon at the bottom right of the map) and Baumradar shows you **all trees within a 100-meter radius** – regardless of your allergy profile. Each marker on the map displays the common name and, if known, the specific species.

### 🧭 AR Directional Display (Compass Arrows)
On the map screen, Baumradar displays transparent arrows and distance indicators. These show you the real-time direction and distance to the closest marked trees (up to the nearest 15). The arrows respond to your compass (gyroscope) and rotate with you, so you always know which direction a tree is in – even if it's outside the visible map area.

### 🗺️ Allergy-Free Routing
Open the "Plan Route" card at the top of the screen. Enter a start and destination address and choose your mode of transport (Walking, Cycling, Driving). Baumradar:
1. Resolves the addresses to coordinates via the **Nominatim Geocoder** (OpenStreetMap).
2. Requests up to 3 route alternatives from the public **OSRM Routing Server**.
3. Loads all geofence zones for tree species marked as "Avoid" in your allergy profile from the local database.
4. Checks for each route alternative how many of these zones are intersected (see [Collision Detection](docs/architecture/06_collision_activity.png)).
5. Sorts the routes: the allergen-free route is displayed first and marked as **"Allergy-free 🟢"**.

The calculated route can be shared via GPX export, e.g., to a navigation app.

### 🏙️ Multi-City Support
Supported cities: **Vienna, Graz, Innsbruck, Linz** (Austria), **Berlin, Hamburg, Freiburg, Dortmund** (Germany), **Zurich, Basel** (Switzerland). On first launch, you select at least one city. When you later move to a new city, the app automatically suggests downloading the local tree data.

### 📴 Offline First
The app downloads a compressed, processed SQLite database for each city. Once downloaded, the map display, exploration mode, and background warnings work **completely without an internet connection**. Only the routing feature (route calculation via OSRM) briefly requires a connection.

### 🔐 Open Data & Zero Trust
The data is processed by the backend and cryptographically signed using **Ed25519**. Before the app uses a downloaded database, it verifies the signature against a public key embedded in the app. Only after successful verification is the data imported. This ensures anyone can verify the data is authentic and untampered.

---

## 🚀 Installation (Android App)

### APK Download (recommended)
1. Download the latest `Baumradar.apk` from the repository (Releases tab).
2. Allow installation from "Unknown Sources" on your smartphone (usually prompted automatically when opening the file).
3. Open the APK and follow the instructions.
4. On first launch: Select at least one city and download its data.
5. Grant permissions for Location (including Background Location for geofence warnings) and Notifications.

### Build from Source
```bash
git clone https://github.com/matthili/BaumRadar.git
cd BaumRadar
./gradlew assembleDebug
# The APK can be found at app/build/outputs/apk/debug/
```
Requirements: Android Studio (current version), JDK 17, Android SDK 34.

---

## 🎮 Using the App

### Initial Setup
On the very first launch, a **City Wizard** appears. Here you toggle the cities whose tree data you want to download. The app shows download progress including signature verification. Then tap "Continue" to reach the main screen.

### Main Screen (Tabs)
The app has a tab bar at the bottom with three sections:

1. **Map (🗺️):** The main area. Here you see the OpenStreetMap map with your location (blue dot), marked allergy trees (yellow pins), geofence zones (red circles), and any calculated routes (blue polyline). At the bottom right there are three buttons:
   - 📍 **Center**: Jumps back to your current location.
   - ⚠️ **Hotspots**: Shows all geofence zones for your selected allergens within a 2 km radius.
   - 🔍 **Exploration Mode**: Shows all trees (of any species) within 100 m.

2. **Allergy Profile (👤):** Here you manage your allergies. You see a searchable list grouped by genus of all tree species in the database. For each species there are two checkboxes:
   - **"Warning ⚠️"**: Activates background geofence notifications for this species.
   - **"Avoid 🚫"**: Considers this species during allergy-free routing.
   
   The tri-state checkbox on the group header (e.g., "Ahorn / Maple") lets you select or deselect an entire genus with one click.

3. **Cities (🏙️):** Here you manage downloaded cities. You can download additional cities, delete existing ones, or tap the location icon to jump directly to a city's map position.

### Long Press on the Map
A long press on any point on the map opens a context menu:
- **Set Virtual Location**: For testing or pre-planning – the app behaves as if you were at this point.
- **Start Route HERE**: Sets the starting point for a route.
- **End Route HERE**: Sets the endpoint and calculates the route.

---

## 📖 Technical Documentation

Baumradar consists of two main parts and an open data structure. Here you can find detailed documentation for each area:

1. **[Android App Architecture](docs/app_architecture_en.md)**: Insights into the Kotlin app, Jetpack Compose UI, Room databases, the routing system, and background geofence notifications.
2. **[Backend / Data-Processor](docs/backend_architecture_en.md)**: How the Java backend reads Open Data from various cities, translates it, clusters it, splits it into chunks, and signs it.
3. **[Data Structure & Third-Party Usage](docs/data_structure_en.md)**: How you as an external developer can use the open, verified Baumradar data for your own app (e.g., iOS, Web) – with code examples.

## 📜 License
This project is published under the **MIT License**. See [LICENSE](LICENSE) for more details.
