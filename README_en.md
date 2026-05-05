# Baumradar 🌳

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

Welcome to **Baumradar**! This open-source project combines a robust Java backend (data-processor) with a modern Android App.

**Baumradar is an Open-Data-based tool that allows you to explore trees in your vicinity and intentionally avoid them when navigating through the city – especially helpful if you suffer from tree pollen allergies (e.g., early bloomers).**

## 🌟 Detailed Features
- **Allergy Tracking & Warning Zones:** Select your personal allergies (e.g., Birch, Hazel). These trees will be highlighted on your map.
- **Exploration Mode:** Standing in front of a tree and wondering "What kind of tree is that?" The exploration mode reveals all trees within a 100-meter radius, regardless of your allergy profile.
- **AR Directional Arrows (Compass):** Baumradar displays arrows and distances, showing you the exact real-time direction and proximity to selected trees around you.
- **Allergy-free Routing & Warnings:** Plan your path through the city. The app calculates your route and **visually warns you during navigation** if your path intersects with the geofence of an allergenic tree you've configured.
- **Multi-City Support:** Supports cities like Vienna, Graz, Innsbruck, Linz, Berlin, Hamburg, Freiburg, Dortmund, Zurich, and Basel.
- **Offline First:** The app downloads compressed, pre-processed SQLite databases for each city. Once downloaded, no internet connection is required for tree visualization.
- **Open Data & Zero Trust:** The data is processed by the backend and cryptographically signed using Ed25519. This allows anyone to verify that the data is authentic and untampered.

## 🚀 Installation (Android App)
You can install the app directly on your Android device:
1. Download the latest `Baumradar.apk` from the repository (Releases tab).
2. Allow installation from "Unknown Sources" on your smartphone if you haven't already.
3. Open the APK and follow the instructions.

Alternatively, you can clone the project in Android Studio and compile it yourself!

## 📖 Documentation
Baumradar consists of two main parts and an open data structure. Here you can find detailed documentation for each area:

1. **[Android App Architecture](docs/app_architecture_en.md)**: Insights into the Kotlin app, Jetpack Compose UI, Room databases, and the routing system.
2. **[Backend / Data-Processor](docs/backend_architecture_en.md)**: How the Java backend ingests Open Data from different cities, translates it, splits it into chunks, and signs it.
3. **[Data Structure & Third-Party Usage](docs/data_structure_en.md)**: How you, as an external developer, can use the open, verified Baumradar data for your own app (e.g., iOS, Web).

*(Für die deutsche Dokumentation, siehe [README.md](README.md))*

## 📜 License
This project is released under the **MIT License**. See [LICENSE](LICENSE) for more details.
