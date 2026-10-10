# NidaMAP

NidaMAP is an Android-first, offline-first railway navigation prototype, beginning with the Osaka area.

## Current prototype
- Kotlin + Jetpack Compose Android app
- Route search against a tiny demonstration-only station graph
- Simple route summary and four navigation sections
- GitHub Actions debug APK build

## Important data notice
Station connections and travel times currently shown in the app are sample data for development only. They are not official timetables, do not represent live delays, and must not be used to plan real journeys. Accurate railway data, service calendars, transfer rules, and redistribution permissions must be researched and validated before real-world use.

## Build
The GitHub Actions workflow runs gradle assembleDebug with JDK 17 and Gradle 8.9. The APK is uploaded as the NidaMAP-debug-apk artifact after a successful build.
