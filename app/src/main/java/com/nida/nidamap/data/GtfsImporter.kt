package com.nida.nidamap.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Imports a static GTFS ZIP on-device. Existing data is replaced only in a successful transaction. */
object GtfsImporter {
    private data class RawStop(
        val id: String,
        val name: String,
        val latitude: Double?,
        val longitude: Double?,
        val parent: String,
        val locationType: Int
    )
    private data class ServiceCalendar(val flags: String, val startDate: String, val endDate: String)
    private data class TripInfo(val serviceId: String, val lineName: String, val headsign: String)
    private data class StopTime(
        val tripId: String,
        val stationId: String,
        val sequence: Int,
        val arrivalSeconds: Int,
        val departureSeconds: Int
    )

    fun importZip(context: Context, uri: Uri, database: RailDatabase): ImportSummary {
        val temporaryZip = File.createTempFile("nidamap-gtfs-", ".zip", context.cacheDir)
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("ZIPファイルを読み取れません。")
            input.use { source ->
                FileOutputStream(temporaryZip).use { target -> source.copyTo(target) }
            }
            ZipFile(temporaryZip).use { zip -> return importFromZip(zip, database) }
        } finally {
            temporaryZip.delete()
        }
    }

    private fun importFromZip(zip: ZipFile, database: RailDatabase): ImportSummary {
        val rawStops = linkedMapOf<String, RawStop>()
        readCsv(zip, "stops.txt", required = true) { row ->
            val id = row["stop_id"].orEmpty().trim()
            val name = row["stop_name"].orEmpty().trim()
            if (id.isNotBlank() && name.isNotBlank()) {
                rawStops[id] = RawStop(
                    id = id,
                    name = name,
                    latitude = row["stop_lat"]?.toDoubleOrNull(),
                    longitude = row["stop_lon"]?.toDoubleOrNull(),
                    parent = row["parent_station"].orEmpty().trim(),
                    locationType = row["location_type"]?.toIntOrNull() ?: 0
                )
            }
        }
        if (rawStops.size < 2) throw IOException("stops.txt に有効な駅が2件以上ありません。")

        val stopToStation = mutableMapOf<String, String>()
        rawStops.values.forEach { stop ->
            val canonical = if (stop.parent.isNotBlank() && rawStops.containsKey(stop.parent)) stop.parent else stop.id
            stopToStation[stop.id] = canonical
        }
        val stationsById = linkedMapOf<String, Station>()
        rawStops.values.forEach { stop ->
            if (stop.locationType in 2..4 && stop.parent.isBlank()) return@forEach
            val canonical = stopToStation[stop.id] ?: stop.id
            val root = rawStops[canonical] ?: stop
            val latitude = root.latitude ?: stop.latitude
            val longitude = root.longitude ?: stop.longitude
            stationsById.putIfAbsent(
                canonical,
                Station(
                    id = canonical,
                    name = root.name.ifBlank { stop.name },
                    latitude = if (latitude != null && longitude != null) latitude else null,
                    longitude = if (latitude != null && longitude != null) longitude else null
                )
            )
        }
        stopToStation.entries.removeAll { it.value !in stationsById.keys }
        if (stationsById.size < 2) throw IOException("駅データが不足しています。GTFS ZIPの内容を確認してください。")

        val routeNames = mutableMapOf<String, String>()
        readCsv(zip, "routes.txt", required = true) { row ->
            val id = row["route_id"].orEmpty().trim()
            if (id.isNotBlank()) {
                val shortName = row["route_short_name"].orEmpty().trim()
                val longName = row["route_long_name"].orEmpty().trim()
                routeNames[id] = when {
                    shortName.isBlank() -> longName
                    longName.isBlank() || shortName == longName -> shortName
                    else -> shortName + " · " + longName
                }.ifBlank { id }
            }
        }

        val trips = mutableMapOf<String, TripInfo>()
        readCsv(zip, "trips.txt", required = true) { row ->
            val tripId = row["trip_id"].orEmpty().trim()
            val routeId = row["route_id"].orEmpty().trim()
            val serviceId = row["service_id"].orEmpty().trim()
            if (tripId.isNotBlank() && serviceId.isNotBlank()) {
                val lineName = routeNames[routeId] ?: routeId.ifBlank { "路線不明" }
                trips[tripId] = TripInfo(
                    serviceId = serviceId,
                    lineName = lineName,
                    headsign = row["trip_headsign"].orEmpty().trim().ifBlank { lineName }
                )
            }
        }
        if (trips.isEmpty()) throw IOException("trips.txt に有効な列車データがありません。")

        val calendars = mutableMapOf<String, ServiceCalendar>()
        readCsv(zip, "calendar.txt") { row ->
            val serviceId = row["service_id"].orEmpty().trim()
            if (serviceId.isNotBlank()) {
                val flags = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
                    .joinToString("") { if (row[it] == "1") "1" else "0" }
                calendars[serviceId] = ServiceCalendar(
                    flags = flags,
                    startDate = normalizeDate(row["start_date"].orEmpty(), "00000000"),
                    endDate = normalizeDate(row["end_date"].orEmpty(), "99999999")
                )
            }
        }
        trips.values.map { it.serviceId }.distinct().forEach {
            calendars.putIfAbsent(it, ServiceCalendar("1111111", "00000000", "99999999"))
        }

        var datasetName = "インポートしたGTFSデータ"
        var datasetVersion = "GTFS static"
        readCsv(zip, "feed_info.txt") { row ->
            datasetName = row["feed_publisher_name"].orEmpty().trim().ifBlank { datasetName }
            datasetVersion = row["feed_version"].orEmpty().trim().ifBlank { datasetVersion }
        }
        if (datasetName == "インポートしたGTFSデータ") {
            readCsv(zip, "agency.txt") { row ->
                datasetName = row["agency_name"].orEmpty().trim().ifBlank { datasetName }
            }
        }

        val db = database.writableDatabase
        db.beginTransaction()
        try {
            db.delete("timetable", null, null)
            db.delete("service_calendar", null, null)
            db.delete("service_exceptions", null, null)
            db.delete("gtfs_import_stop_times", null, null)
            db.delete("connections", null, null)
            db.delete("stations", null, null)
            db.delete("metadata", null, null)

            stationsById.values.forEach { station ->
                val values = ContentValues().apply {
                    put("id", station.id)
                    put("name", station.name)
                    if (station.latitude == null) putNull("latitude") else put("latitude", station.latitude)
                    if (station.longitude == null) putNull("longitude") else put("longitude", station.longitude)
                }
                db.insertOrThrow("stations", null, values)
            }
            calendars.forEach { (id, calendar) ->
                db.insertOrThrow(
                    "service_calendar",
                    null,
                    ContentValues().apply {
                        put("service_id", id)
                        put("weekday_flags", calendar.flags)
                        put("start_date", calendar.startDate)
                        put("end_date", calendar.endDate)
                    }
                )
            }
            readCsv(zip, "calendar_dates.txt") { row ->
                val serviceId = row["service_id"].orEmpty().trim()
                val date = normalizeDate(row["date"].orEmpty(), "")
                val exceptionType = row["exception_type"]?.toIntOrNull()
                if (serviceId.isNotBlank() && date.length == 8 && exceptionType in 1..2) {
                    db.insertWithOnConflict(
                        "service_exceptions",
                        null,
                        ContentValues().apply {
                            put("service_id", serviceId)
                            put("service_date", date)
                            put("exception_type", exceptionType)
                        },
                        SQLiteDatabase.CONFLICT_REPLACE
                    )
                }
            }

            val staging = db.compileStatement(
                "INSERT INTO gtfs_import_stop_times (trip_id, stop_sequence, station_id, arrival_seconds, departure_seconds) VALUES (?, ?, ?, ?, ?)"
            )
            try {
                readCsv(zip, "stop_times.txt", required = true) { row ->
                    val tripId = row["trip_id"].orEmpty().trim()
                    val stopId = row["stop_id"].orEmpty().trim()
                    val stationId = stopToStation[stopId]
                    val sequence = row["stop_sequence"]?.toIntOrNull()
                    val arrival = parseGtfsTime(row["arrival_time"].orEmpty())
                    val departure = parseGtfsTime(row["departure_time"].orEmpty())
                    if (tripId.isNotBlank() && stationId != null && sequence != null && (arrival != null || departure != null)) {
                        staging.clearBindings()
                        staging.bindString(1, tripId)
                        staging.bindLong(2, sequence.toLong())
                        staging.bindString(3, stationId)
                        staging.bindLong(4, (arrival ?: departure ?: 0).toLong())
                        staging.bindLong(5, (departure ?: -1).toLong())
                        staging.executeInsert()
                    }
                }
            } finally {
                staging.close()
            }
            if (countRows(db, "gtfs_import_stop_times") == 0) {
                throw IOException("stop_times.txt に有効な停車時刻がありません。")
            }

            val insertDeparture = db.compileStatement(
                """INSERT INTO timetable
                   (station_id, line_name, destination, departure_time, arrival_time, service_days,
                    service_id, departure_seconds, weekday_flags, start_date, end_date)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".trimIndent()
            )
            val cursor = db.rawQuery(
                "SELECT trip_id, station_id, stop_sequence, arrival_seconds, departure_seconds FROM gtfs_import_stop_times ORDER BY trip_id, stop_sequence",
                null
            )
            var activeTripId = ""
            var previous: StopTime? = null
            try {
                while (cursor.moveToNext()) {
                    val tripId = cursor.getString(0)
                    val current = StopTime(
                        tripId = tripId,
                        stationId = cursor.getString(1),
                        sequence = cursor.getInt(2),
                        arrivalSeconds = cursor.getInt(3),
                        departureSeconds = cursor.getInt(4)
                    )
                    if (tripId != activeTripId) {
                        activeTripId = tripId
                        previous = null
                    }
                    val trip = trips[tripId]
                    if (trip == null) {
                        previous = null
                        continue
                    }
                    val calendar = calendars[trip.serviceId] ?: ServiceCalendar("1111111", "00000000", "99999999")
                    if (current.departureSeconds >= 0) {
                        insertDeparture.clearBindings()
                        insertDeparture.bindString(1, current.stationId)
                        insertDeparture.bindString(2, trip.lineName)
                        insertDeparture.bindString(3, trip.headsign)
                        insertDeparture.bindString(4, formatGtfsTime(current.departureSeconds))
                        insertDeparture.bindString(5, formatGtfsTime(current.arrivalSeconds))
                        insertDeparture.bindString(6, serviceLabel(calendar))
                        insertDeparture.bindString(7, trip.serviceId)
                        insertDeparture.bindLong(8, current.departureSeconds.toLong())
                        insertDeparture.bindString(9, calendar.flags)
                        insertDeparture.bindString(10, calendar.startDate)
                        insertDeparture.bindString(11, calendar.endDate)
                        insertDeparture.executeInsert()
                    }
                    val prior = previous
                    if (prior != null && prior.stationId != current.stationId) {
                        val priorDeparture = if (prior.departureSeconds >= 0) prior.departureSeconds else prior.arrivalSeconds
                        val durationSeconds = current.arrivalSeconds - priorDeparture
                        if (durationSeconds in 1..14400) {
                            val minutes = ((durationSeconds + 59) / 60).coerceIn(1, 240)
                            val distance = distanceMeters(stationsById[prior.stationId], stationsById[current.stationId])
                            upsertConnection(db, prior.stationId, current.stationId, trip.lineName, minutes, distance)
                        }
                    }
                    previous = current
                }
            } finally {
                cursor.close()
                insertDeparture.close()
            }
            db.delete("gtfs_import_stop_times", null, null)
            if (countRows(db, "timetable") == 0 || countRows(db, "connections") == 0) {
                throw IOException("GTFSから時刻表または駅間接続を作成できませんでした。")
            }
            writeMeta(db, "dataset_name", datasetName)
            writeMeta(db, "dataset_version", datasetVersion)
            writeMeta(db, "imported_at", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.JAPAN).format(java.util.Date()))
            writeMeta(db, "is_demo", "false")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        return ImportSummary(
            datasetName = datasetName,
            stationCount = countRows(db, "stations"),
            connectionCount = countRows(db, "connections"),
            timetableCount = countRows(db, "timetable")
        )
    }

    private fun upsertConnection(
        db: android.database.sqlite.SQLiteDatabase,
        from: String,
        to: String,
        line: String,
        minutes: Int,
        distance: Int?
    ) {
        val values = ContentValues().apply {
            put("from_id", from)
            put("to_id", to)
            put("line_name", line)
            put("minutes", minutes)
            if (distance == null) putNull("distance_meters") else put("distance_meters", distance)
        }
        db.insertWithOnConflict("connections", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        val update = if (distance == null) {
            "UPDATE connections SET minutes = ? WHERE from_id = ? AND to_id = ? AND line_name = ? AND minutes > ?"
        } else {
            "UPDATE connections SET minutes = ?, distance_meters = COALESCE(distance_meters, ?) WHERE from_id = ? AND to_id = ? AND line_name = ? AND minutes > ?"
        }
        if (distance == null) {
            db.execSQL(update, arrayOf(minutes, from, to, line, minutes))
        } else {
            db.execSQL(update, arrayOf(minutes, distance, from, to, line, minutes))
        }
    }

    private fun writeMeta(db: android.database.sqlite.SQLiteDatabase, key: String, value: String) {
        db.insertWithOnConflict(
            "metadata",
            null,
            ContentValues().apply { put("key", key); put("value", value) },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun countRows(db: android.database.sqlite.SQLiteDatabase, table: String): Int =
        db.rawQuery("SELECT COUNT(*) FROM " + table, null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

    private fun distanceMeters(first: Station?, second: Station?): Int? {
        if (first?.latitude == null || first.longitude == null || second?.latitude == null || second.longitude == null) return null
        val earth = 6371000.0
        val lat1 = Math.toRadians(first.latitude)
        val lat2 = Math.toRadians(second.latitude)
        val dLat = Math.toRadians(second.latitude - first.latitude)
        val dLon = Math.toRadians(second.longitude - first.longitude)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return (earth * 2 * atan2(sqrt(a), sqrt(1 - a))).toInt().coerceAtLeast(1)
    }

    private fun serviceLabel(calendar: ServiceCalendar): String {
        val weekdays = listOf("月", "火", "水", "木", "金", "土", "日")
        val active = weekdays.filterIndexed { index, _ -> calendar.flags.getOrNull(index) == '1' }
        val dayText = if (active.size == 7) "毎日" else if (active.size == 5 && calendar.flags.take(5) == "11111") "平日" else active.joinToString("")
        val period = if (calendar.startDate == "00000000" && calendar.endDate == "99999999") "" else " · " + calendar.startDate + "〜" + calendar.endDate
        return dayText + period
    }

    private fun parseGtfsTime(value: String): Int? {
        val parts = value.trim().split(":")
        if (parts.size !in 2..3) return null
        val hours = parts[0].toIntOrNull() ?: return null
        val minutes = parts[1].toIntOrNull() ?: return null
        val seconds = parts.getOrNull(2)?.toIntOrNull() ?: 0
        if (hours !in 0..99 || minutes !in 0..59 || seconds !in 0..59) return null
        return hours * 3600 + minutes * 60 + seconds
    }

    private fun formatGtfsTime(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return String.format(Locale.US, "%02d:%02d", hours, minutes)
    }

    private fun normalizeDate(value: String, fallback: String): String {
        val clean = value.trim()
        return if (clean.length == 8 && clean.all { it.isDigit() }) clean else fallback
    }

    private fun findEntry(zip: ZipFile, filename: String): java.util.zip.ZipEntry? =
        zip.entries().asSequence().firstOrNull {
            !it.isDirectory && it.name.substringAfterLast('/') == filename
        }

    private fun readCsv(
        zip: ZipFile,
        filename: String,
        required: Boolean = false,
        onRow: (Map<String, String>) -> Unit
    ): Int {
        val entry = findEntry(zip, filename)
        if (entry == null) {
            if (required) throw IOException(filename + " が ZIP 内にありません。")
            return 0
        }
        var count = 0
        BufferedReader(InputStreamReader(zip.getInputStream(entry), Charsets.UTF_8)).use { reader ->
            val firstLine = reader.readLine() ?: throw IOException(filename + " が空です。")
            val headers = parseCsvLine(firstLine).mapIndexed { index, value ->
                if (index == 0) value.removePrefix("\uFEFF").trim() else value.trim()
            }
            var line = reader.readLine()
            while (line != null) {
                if (line.isNotBlank()) {
                    val values = parseCsvLine(line)
                    val row = headers.mapIndexed { index, key -> key to values.getOrElse(index) { "" } }.toMap()
                    onRow(row)
                    count++
                }
                line = reader.readLine()
            }
        }
        return count
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                    field.append('"')
                    index++
                }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> {
                    result += field.toString()
                    field.setLength(0)
                }
                else -> field.append(char)
            }
            index++
        }
        result += field.toString()
        return result
    }
}
