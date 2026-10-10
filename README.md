# NidaMAP

NidaMAP is an Android-first, offline-first railway navigation app prototype, starting in Osaka.

## Implemented features

- Offline route search over a local station/connection graph, with estimated transfer costs.
- SQLite storage for stations, inter-station connections, timetables, dataset metadata and GTFS service-day exceptions.
- On-device import of GTFS static ZIP feeds (stops, routes, trips, stop_times, calendar and calendar_dates).
- Import/export of the NidaMAP JSON format for backups and smaller custom datasets.
- Timetable filtering for current local time, weekday/service date range and GTFS date exceptions.
- Ride guide with GPS proximity checks when imported station coordinates are available, accelerometer motion indication, vibration, and manual previous/next correction.
- No Internet permission; data import and location processing happen locally.

## Important data and safety notes

The bundled Osaka data is **demonstration-only**. Some station positions and journey times are approximate, and the timetable entries are fictional test times. Do not use the demo dataset for real journeys. Import a valid, properly licensed feed and independently validate its timetable, connections, calendar coverage, transfers and coordinates first.

The GPS function is a basic proximity estimate and can fail underground or at stations with nearby platforms. The accelerometer indicates device motion only; it does not prove that the user is on a train. Manual correction is available. The app does not provide live delays, cancellations, crowding, or live train positions.

GTFS feeds can have provider-specific licenses and redistribution restrictions. Check those terms before using a feed or sharing an exported backup.

## Import GTFS

Open **データ → GTFS ZIP（鉄道時刻表）を取り込む** and choose a static GTFS ZIP. It should contain `stops.txt`, `routes.txt`, `trips.txt`, and `stop_times.txt`. The importer processes the ZIP on-device and replaces the stored dataset only if import succeeds. Keep the phone powered during large imports.

## NidaMAP JSON format

Use UTF-8 JSON with `format: "nidamap-rail-data"`, `datasetName`, `datasetVersion`, `stations`, `connections`, and optional `timetables`.

- `stations`: `id`, `name`, optional `latitude`, `longitude`
- `connections`: `from`, `to`, `line`, `minutes`, optional `distanceMeters`
- `timetables`: `stationId`, `line`, `destination`, `departure`, `arrival`, `serviceDays`; optional `serviceId`, `departureSeconds`, `weekdayFlags`, `startDate`, `endDate`
- `serviceExceptions`: optional array of `serviceId`, `date` (YYYYMMDD), and `exceptionType` (1=added service, 2=removed service)

GTFS times after midnight may use hours above 23, such as `25:10`.

## Build and tests

GitHub Actions runs `gradle testDebugUnitTest assembleDebug` using JDK 17 and Gradle 8.9. The generated debug APK is uploaded as the `NidaMAP-debug-apk` artifact when the build succeeds.
