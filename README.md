# NidaMAP

NidaMAP is an Android-first, offline-first railway navigation prototype, beginning with Osaka.

## Implemented in the current prototype

- Local SQLite database for stations, connections, timetable records and dataset metadata.
- Offline shortest-time route search using a weighted graph and an estimated transfer penalty.
- Station name suggestions and swapping the departure/destination.
- Timetable screen backed by the device database.
- Ride guide for a selected route, manual next/previous station correction, vibration and GPS proximity checks where station coordinates are available.
- JSON import/export using Android's document picker. Import is validated before the existing dataset is replaced.
- Reset to the bundled demo dataset.
- GitHub Actions debug APK build; a successful run uploads `NidaMAP-debug-apk`.

## Important limitations

The bundled Osaka dataset is **development-only sample data**, not verified official railway data. Its station connections, estimated minutes, coordinates and timetable entries must not be used to plan real journeys. In particular, the timetable entries are marked as fictional test times.

GPS guide logic currently checks proximity to the next station and requires repeated accurate fixes. It is a simple prototype, not a safety-critical navigation system. GPS can be unreliable underground and can misidentify a station where platforms are close together; manual correction is available. The current implementation does not yet combine motion sensors with schedule-based inference, nor does it provide validated all-line coverage, platform-level navigation, live delay/cancellation alerts, or background tracking.

Routine route search and saved timetable viewing work offline. The app does not request Internet permission, and it does not upload location or imported rail data. GPS permission is requested only when the user starts the ride guide.

Before real-world use, import a complete and independently validated dataset and verify route data, service calendars, transfers, coordinates, and reuse/redistribution permissions. Live operation updates require a separate supported data source and are not available offline by themselves.

## Import / export JSON format

Use a UTF-8 JSON file with this structure:

```json
{
  "format": "nidamap-rail-data",
  "datasetName": "Example Osaka dataset",
  "datasetVersion": "1",
  "stations": [
    {
      "id": "station_a",
      "name": "駅A",
      "latitude": 34.7000,
      "longitude": 135.5000
    },
    {
      "id": "station_b",
      "name": "駅B",
      "latitude": 34.7100,
      "longitude": 135.5100
    }
  ],
  "connections": [
    {
      "from": "station_a",
      "to": "station_b",
      "line": "路線名",
      "minutes": 5,
      "distanceMeters": 1200
    }
  ],
  "timetables": [
    {
      "stationId": "station_a",
      "line": "路線名",
      "destination": "駅B",
      "departure": "08:00",
      "arrival": "08:05",
      "serviceDays": "平日"
    }
  ]
}
```

Fields `latitude`, `longitude`, `distanceMeters`, and `timetables` are optional where noted by the app. `from` and `to` reference station IDs. Times use 24-hour `HH:mm`. A connection is treated as traversable in both directions. Invalid files are rejected before the current dataset is replaced.

## Build

The GitHub Actions workflow runs `gradle assembleDebug` with JDK 17 and Gradle 8.9. After a successful run, download the `NidaMAP-debug-apk` artifact from the workflow page.
