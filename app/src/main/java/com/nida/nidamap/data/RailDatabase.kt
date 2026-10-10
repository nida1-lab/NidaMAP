package com.nida.nidamap.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small SQLite store so stations, route connections and imported timetables stay on-device.
 * No location or timetable data is uploaded by this class.
 */
class RailDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE stations (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                latitude REAL,
                longitude REAL
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE connections (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                from_id TEXT NOT NULL,
                to_id TEXT NOT NULL,
                line_name TEXT NOT NULL,
                minutes INTEGER NOT NULL,
                distance_meters INTEGER
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE timetable (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                station_id TEXT NOT NULL,
                line_name TEXT NOT NULL,
                destination TEXT NOT NULL,
                departure_time TEXT NOT NULL,
                arrival_time TEXT NOT NULL,
                service_days TEXT NOT NULL,
                service_id TEXT NOT NULL DEFAULT '',
                departure_seconds INTEGER NOT NULL DEFAULT 0,
                weekday_flags TEXT NOT NULL DEFAULT '1111111',
                start_date TEXT NOT NULL DEFAULT '00000000',
                end_date TEXT NOT NULL DEFAULT '99999999'
            )""".trimIndent()
        )
        db.execSQL("CREATE TABLE metadata (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
        db.execSQL("CREATE TABLE service_calendar (service_id TEXT PRIMARY KEY NOT NULL, weekday_flags TEXT NOT NULL, start_date TEXT NOT NULL, end_date TEXT NOT NULL)")
        db.execSQL("CREATE TABLE service_exceptions (service_id TEXT NOT NULL, service_date TEXT NOT NULL, exception_type INTEGER NOT NULL, PRIMARY KEY(service_id, service_date))")
        db.execSQL("CREATE TABLE gtfs_import_stop_times (trip_id TEXT NOT NULL, stop_sequence INTEGER NOT NULL, station_id TEXT NOT NULL, arrival_seconds INTEGER NOT NULL, departure_seconds INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX idx_timetable_station_time ON timetable(station_id, departure_seconds)")
        db.execSQL("CREATE UNIQUE INDEX idx_connection_key ON connections(from_id, to_id, line_name)")
        seedDemo(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE timetable ADD COLUMN service_id TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE timetable ADD COLUMN departure_seconds INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE timetable ADD COLUMN weekday_flags TEXT NOT NULL DEFAULT '1111111'")
            db.execSQL("ALTER TABLE timetable ADD COLUMN start_date TEXT NOT NULL DEFAULT '00000000'")
            db.execSQL("ALTER TABLE timetable ADD COLUMN end_date TEXT NOT NULL DEFAULT '99999999'")
            db.execSQL("CREATE TABLE IF NOT EXISTS service_calendar (service_id TEXT PRIMARY KEY NOT NULL, weekday_flags TEXT NOT NULL, start_date TEXT NOT NULL, end_date TEXT NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS service_exceptions (service_id TEXT NOT NULL, service_date TEXT NOT NULL, exception_type INTEGER NOT NULL, PRIMARY KEY(service_id, service_date))")
            db.execSQL("CREATE TABLE IF NOT EXISTS gtfs_import_stop_times (trip_id TEXT NOT NULL, stop_sequence INTEGER NOT NULL, station_id TEXT NOT NULL, arrival_seconds INTEGER NOT NULL, departure_seconds INTEGER NOT NULL)")
            db.execSQL("DELETE FROM connections WHERE id NOT IN (SELECT MIN(id) FROM connections GROUP BY from_id, to_id, line_name)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_connection_key ON connections(from_id, to_id, line_name)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_timetable_station_time ON timetable(station_id, departure_seconds)")
        }
    }

    fun getStations(): List<Station> {
        val result = mutableListOf<Station>()
        readableDatabase.rawQuery(
            "SELECT id, name, latitude, longitude FROM stations ORDER BY rowid",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += Station(
                    id = cursor.getString(0),
                    name = cursor.getString(1),
                    latitude = if (cursor.isNull(2)) null else cursor.getDouble(2),
                    longitude = if (cursor.isNull(3)) null else cursor.getDouble(3)
                )
            }
        }
        return result
    }

    fun getConnections(): List<RailConnection> {
        val result = mutableListOf<RailConnection>()
        readableDatabase.rawQuery(
            "SELECT from_id, to_id, line_name, minutes, distance_meters FROM connections ORDER BY id",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += RailConnection(
                    fromStationId = cursor.getString(0),
                    toStationId = cursor.getString(1),
                    lineName = cursor.getString(2),
                    minutes = cursor.getInt(3),
                    distanceMeters = if (cursor.isNull(4)) null else cursor.getInt(4)
                )
            }
        }
        return result
    }

    fun getTimetable(stationId: String): List<TimetableEntry> {
        val result = mutableListOf<TimetableEntry>()
        readableDatabase.rawQuery(
            """SELECT id, station_id, line_name, destination, departure_time, arrival_time, service_days,
                      service_id, departure_seconds, weekday_flags, start_date, end_date
                FROM timetable WHERE station_id = ? ORDER BY departure_seconds, id""".trimIndent(),
            arrayOf(stationId)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += TimetableEntry(
                    id = cursor.getLong(0),
                    stationId = cursor.getString(1),
                    lineName = cursor.getString(2),
                    destination = cursor.getString(3),
                    departureTime = cursor.getString(4),
                    arrivalTime = cursor.getString(5),
                    serviceDays = cursor.getString(6),
                    serviceId = cursor.getString(7),
                    departureSeconds = cursor.getInt(8),
                    weekdayFlags = cursor.getString(9),
                    startDate = cursor.getString(10),
                    endDate = cursor.getString(11)
                )
            }
        }
        return result
    }

    /** Filters timetable entries against local weekdays, service periods and GTFS date exceptions. */
    fun getUpcomingTimetable(
        stationId: String,
        date: java.time.LocalDate,
        afterSeconds: Int,
        limit: Int = 80
    ): List<TimetableEntry> {
        val dateKey = date.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
        val exceptions = mutableMapOf<String, Int>()
        readableDatabase.rawQuery(
            "SELECT service_id, exception_type FROM service_exceptions WHERE service_date = ?",
            arrayOf(dateKey)
        ).use { cursor ->
            while (cursor.moveToNext()) exceptions[cursor.getString(0)] = cursor.getInt(1)
        }
        val weekdayIndex = date.dayOfWeek.value - 1
        return getTimetable(stationId)
            .asSequence()
            .filter { it.departureSeconds >= afterSeconds }
            .filter { entry ->
                val exception = exceptions[entry.serviceId]
                if (exception != null) {
                    exception == 1
                } else {
                    dateKey >= entry.startDate &&
                        dateKey <= entry.endDate &&
                        entry.weekdayFlags.getOrNull(weekdayIndex) == '1'
                }
            }
            .take(limit)
            .toList()
    }

    fun getDatasetInfo(): DatasetInfo {
        val db = readableDatabase
        return DatasetInfo(
            name = readMeta(db, "dataset_name") ?: "NidaMAP demo",
            version = readMeta(db, "dataset_version") ?: "demo-1",
            stationCount = countRows(db, "stations"),
            connectionCount = countRows(db, "connections"),
            timetableCount = countRows(db, "timetable"),
            importedAt = readMeta(db, "imported_at") ?: "組み込みデモ",
            isDemo = readMeta(db, "is_demo") != "false"
        )
    }

    /**
     * Import JSON atomically. Data is fully parsed and validated before the current dataset
     * is replaced, so invalid files leave the existing offline data untouched.
     */
    fun importJson(jsonText: String): ImportSummary {
        val root = JSONObject(jsonText)
        val format = root.optString("format")
        require(format == "nidamap-rail-data") {
            "対応していないファイル形式です。format は nidamap-rail-data にしてください。"
        }
        val stationArray = root.optJSONArray("stations")
            ?: throw IllegalArgumentException("stations 配列がありません。")
        val connectionArray = root.optJSONArray("connections")
            ?: throw IllegalArgumentException("connections 配列がありません。")
        val timetableArray = root.optJSONArray("timetables") ?: JSONArray()

        val stations = mutableListOf<Station>()
        val ids = mutableSetOf<String>()
        for (i in 0 until stationArray.length()) {
            val item = stationArray.getJSONObject(i)
            val id = item.optString("id").trim()
            val name = item.optString("name").trim()
            require(id.isNotBlank() && name.isNotBlank()) { "駅の id / name は必須です。" }
            require(ids.add(id)) { "駅IDが重複しています: $id" }
            val latitude = if (item.has("latitude") && !item.isNull("latitude")) item.getDouble("latitude") else null
            val longitude = if (item.has("longitude") && !item.isNull("longitude")) item.getDouble("longitude") else null
            if (latitude != null) require(latitude in -90.0..90.0) { "緯度が範囲外です: $id" }
            if (longitude != null) require(longitude in -180.0..180.0) { "経度が範囲外です: $id" }
            require((latitude == null) == (longitude == null)) { "緯度・経度は両方セットしてください: $id" }
            stations += Station(id, name, latitude, longitude)
        }
        require(stations.size >= 2) { "駅は2件以上必要です。" }

        val connections = mutableListOf<RailConnection>()
        for (i in 0 until connectionArray.length()) {
            val item = connectionArray.getJSONObject(i)
            val from = item.optString("from").trim()
            val to = item.optString("to").trim()
            val line = item.optString("line").trim()
            val minutes = item.optInt("minutes", -1)
            require(from in ids && to in ids && from != to) { "接続先の駅IDが不正です（行: ${i + 1}）。" }
            require(line.isNotBlank()) { "路線名が空です（行: ${i + 1}）。" }
            require(minutes in 1..240) { "所要分数は1〜240の範囲にしてください（行: ${i + 1}）。" }
            val distance = if (item.has("distanceMeters") && !item.isNull("distanceMeters")) {
                item.getInt("distanceMeters").also { require(it in 1..500000) { "距離が範囲外です。" } }
            } else null
            connections += RailConnection(from, to, line, minutes, distance)
        }
        require(connections.isNotEmpty()) { "connections は1件以上必要です。" }

        val timetables = mutableListOf<JSONObject>()
        val timePattern = Regex("^\\d{2,3}:[0-5]\\d$")
        for (i in 0 until timetableArray.length()) {
            val item = timetableArray.getJSONObject(i)
            val stationId = item.optString("stationId").trim()
            val departure = item.optString("departure").trim()
            val arrival = item.optString("arrival").trim()
            require(stationId in ids) { "時刻表の stationId が見つかりません: $stationId" }
            require(item.optString("line").isNotBlank() && item.optString("destination").isNotBlank()) {
                "時刻表には line と destination が必要です。"
            }
            require(timePattern.matches(departure) && timePattern.matches(arrival)) {
                "時刻は HH:mm 形式で指定してください。"
            }
            timetables += item
        }

        val exceptionArray = root.optJSONArray("serviceExceptions") ?: JSONArray()
        val exceptions = mutableListOf<Triple<String, String, Int>>()
        for (i in 0 until exceptionArray.length()) {
            val item = exceptionArray.getJSONObject(i)
            val serviceId = item.optString("serviceId").trim()
            val date = item.optString("date").trim()
            val type = item.optInt("exceptionType", 0)
            require(serviceId.isNotBlank() && date.matches(Regex("^\\d{8}$")) && type in 1..2) {
                "serviceExceptions の内容が不正です（行: " + (i + 1) + "）。"
            }
            exceptions += Triple(serviceId, date, type)
        }

        val datasetName = root.optString("datasetName", "インポートデータ").trim()
            .ifBlank { "インポートデータ" }
        val datasetVersion = root.optString("datasetVersion", "1").trim().ifBlank { "1" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("timetable", null, null)
            db.delete("service_calendar", null, null)
            db.delete("service_exceptions", null, null)
            db.delete("gtfs_import_stop_times", null, null)
            db.delete("connections", null, null)
            db.delete("stations", null, null)
            db.delete("metadata", null, null)
            stations.forEach { insertStation(db, it) }
            connections.forEach { insertConnection(db, it) }
            timetables.forEach { item ->
                insertTimetable(
                    db,
                    stationId = item.getString("stationId"),
                    line = item.getString("line"),
                    destination = item.getString("destination"),
                    departure = item.getString("departure"),
                    arrival = item.getString("arrival"),
                    serviceDays = item.optString("serviceDays", "毎日"),
                    serviceId = item.optString("serviceId", ""),
                    departureSeconds = item.optInt("departureSeconds", parseTimeSeconds(item.optString("departure"))),
                    weekdayFlags = item.optString("weekdayFlags", weekdayFlagsFromServiceDays(item.optString("serviceDays", "毎日"))),
                    startDate = item.optString("startDate", "00000000"),
                    endDate = item.optString("endDate", "99999999")
                )
            }
            exceptions.forEach { (serviceId, date, type) ->
                db.insertWithOnConflict(
                    "service_exceptions", null,
                    ContentValues().apply {
                        put("service_id", serviceId)
                        put("service_date", date)
                        put("exception_type", type)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            writeMeta(db, "dataset_name", datasetName)
            writeMeta(db, "dataset_version", datasetVersion)
            writeMeta(db, "imported_at", nowString())
            writeMeta(db, "is_demo", "false")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return ImportSummary(datasetName, stations.size, connections.size, timetables.size)
    }

    fun exportJson(): String {
        val info = getDatasetInfo()
        val root = JSONObject()
            .put("format", "nidamap-rail-data")
            .put("datasetName", info.name)
            .put("datasetVersion", info.version)
        val stationArray = JSONArray()
        getStations().forEach { station ->
            val item = JSONObject().put("id", station.id).put("name", station.name)
            station.latitude?.let { item.put("latitude", it) }
            station.longitude?.let { item.put("longitude", it) }
            stationArray.put(item)
        }
        val connectionArray = JSONArray()
        getConnections().forEach { connection ->
            val item = JSONObject()
                .put("from", connection.fromStationId)
                .put("to", connection.toStationId)
                .put("line", connection.lineName)
                .put("minutes", connection.minutes)
            connection.distanceMeters?.let { item.put("distanceMeters", it) }
            connectionArray.put(item)
        }
        val timetableArray = JSONArray()
        getStations().forEach { station ->
            getTimetable(station.id).forEach { entry ->
                timetableArray.put(
                    JSONObject()
                        .put("stationId", entry.stationId)
                        .put("line", entry.lineName)
                        .put("destination", entry.destination)
                        .put("departure", entry.departureTime)
                        .put("arrival", entry.arrivalTime)
                        .put("serviceDays", entry.serviceDays)
                        .put("serviceId", entry.serviceId)
                        .put("departureSeconds", entry.departureSeconds)
                        .put("weekdayFlags", entry.weekdayFlags)
                        .put("startDate", entry.startDate)
                        .put("endDate", entry.endDate)
                )
            }
        }
        val exceptionArray = JSONArray()
        readableDatabase.rawQuery(
            "SELECT service_id, service_date, exception_type FROM service_exceptions ORDER BY service_date, service_id",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                exceptionArray.put(
                    JSONObject()
                        .put("serviceId", cursor.getString(0))
                        .put("date", cursor.getString(1))
                        .put("exceptionType", cursor.getInt(2))
                )
            }
        }
        root.put("stations", stationArray)
            .put("connections", connectionArray)
            .put("timetables", timetableArray)
            .put("serviceExceptions", exceptionArray)
        return root.toString(2)
    }

    fun resetToDemoData() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("timetable", null, null)
            db.delete("service_calendar", null, null)
            db.delete("service_exceptions", null, null)
            db.delete("gtfs_import_stop_times", null, null)
            db.delete("connections", null, null)
            db.delete("stations", null, null)
            db.delete("metadata", null, null)
            seedDemo(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun seedDemo(db: SQLiteDatabase) {
        val stations = listOf(
            Station("osaka", "大阪", 34.702485, 135.495951),
            Station("umeda", "梅田", 34.705300, 135.498200),
            Station("nakatsu", "中津", 34.710500, 135.495500),
            Station("nishinakajima", "西中島南方", 34.726400, 135.498600),
            Station("shinosaka", "新大阪", 34.733500, 135.500000),
            Station("higashimikuni", "東三国", 34.742500, 135.498500),
            Station("esaka", "江坂", 34.758000, 135.496000),
            Station("ryokuchikoen", "緑地公園", 34.774000, 135.496000),
            Station("momoyamadai", "桃山台", 34.793000, 135.496000),
            Station("senrichuo", "千里中央", 34.808400, 135.495700),
            Station("yodoyabashi", "淀屋橋", 34.692400, 135.502100),
            Station("kitahama", "北浜", 34.691600, 135.507100),
            Station("tenmabashi", "天満橋", 34.689800, 135.516600),
            Station("hommachi", "本町", 34.681500, 135.498800),
            Station("shinsaibashi", "心斎橋", 34.674800, 135.500000),
            Station("namba", "なんば", 34.666100, 135.500800),
            Station("daikokucho", "大国町", 34.656700, 135.500200),
            Station("dobutsuenmae", "動物園前", 34.649600, 135.506000),
            Station("shinimamiya", "新今宮", 34.650100, 135.505500),
            Station("tengachaya", "天下茶屋", 34.637200, 135.498800),
            Station("tennoji", "天王寺", 34.647200, 135.513700),
            Station("temma", "天満", 34.705800, 135.512000),
            Station("sakuranomiya", "桜ノ宮", 34.702000, 135.519000),
            Station("kyobashi", "京橋", 34.697200, 135.534700),
            Station("osakajokoen", "大阪城公園", 34.689800, 135.534700),
            Station("morinomiya", "森ノ宮", 34.680100, 135.534400),
            Station("tsuruhashi", "鶴橋", 34.665400, 135.533400),
            Station("fukushima", "福島", 34.696800, 135.486100),
            Station("juso", "十三", 34.720200, 135.482000),
            Station("awaji", "淡路", 34.739700, 135.517500)
        )
        stations.forEach { insertStation(db, it) }

        val metro = "御堂筋線・北大阪急行（直通運転）"
        val connections = listOf(
            RailConnection("osaka", "umeda", "徒歩連絡", 5, 500),
            RailConnection("umeda", "nakatsu", metro, 2, 1100),
            RailConnection("nakatsu", "nishinakajima", metro, 4, 2300),
            RailConnection("nishinakajima", "shinosaka", metro, 2, 1400),
            RailConnection("shinosaka", "higashimikuni", metro, 2, 1300),
            RailConnection("higashimikuni", "esaka", metro, 2, 1700),
            RailConnection("esaka", "ryokuchikoen", metro, 3, 2400),
            RailConnection("ryokuchikoen", "momoyamadai", metro, 4, 3300),
            RailConnection("momoyamadai", "senrichuo", metro, 4, 3000),
            RailConnection("umeda", "yodoyabashi", "Osaka Metro 御堂筋線", 3, 1700),
            RailConnection("yodoyabashi", "hommachi", "Osaka Metro 御堂筋線", 2, 1300),
            RailConnection("hommachi", "shinsaibashi", "Osaka Metro 御堂筋線", 2, 900),
            RailConnection("shinsaibashi", "namba", "Osaka Metro 御堂筋線", 2, 1100),
            RailConnection("namba", "daikokucho", "Osaka Metro 御堂筋線", 2, 1200),
            RailConnection("daikokucho", "dobutsuenmae", "Osaka Metro 御堂筋線", 2, 1000),
            RailConnection("dobutsuenmae", "tennoji", "Osaka Metro 御堂筋線", 2, 1400),
            RailConnection("yodoyabashi", "kitahama", "京阪本線", 2, 800),
            RailConnection("kitahama", "tenmabashi", "京阪本線", 3, 1200),
            RailConnection("tenmabashi", "kyobashi", "京阪本線", 3, 2000),
            RailConnection("osaka", "temma", "JR大阪環状線", 2, 1400),
            RailConnection("temma", "sakuranomiya", "JR大阪環状線", 2, 1400),
            RailConnection("sakuranomiya", "kyobashi", "JR大阪環状線", 3, 1800),
            RailConnection("kyobashi", "osakajokoen", "JR大阪環状線", 2, 1200),
            RailConnection("osakajokoen", "morinomiya", "JR大阪環状線", 2, 1300),
            RailConnection("morinomiya", "tsuruhashi", "JR大阪環状線", 4, 2500),
            RailConnection("tsuruhashi", "tennoji", "JR大阪環状線", 5, 3000),
            RailConnection("osaka", "shinosaka", "JR京都線", 4, 3900),
            RailConnection("umeda", "juso", "阪急線", 3, 2300),
            RailConnection("juso", "awaji", "阪急線", 6, 4300),
            RailConnection("awaji", "nishinakajima", "徒歩連絡", 12, 2500),
            RailConnection("namba", "tsuruhashi", "近鉄難波線", 5, 3200),
            RailConnection("namba", "shinimamiya", "南海本線", 3, 1600),
            RailConnection("shinimamiya", "tengachaya", "南海本線", 3, 1800),
            RailConnection("dobutsuenmae", "shinimamiya", "徒歩連絡", 3, 400),
            RailConnection("umeda", "fukushima", "阪神本線", 2, 1400),
            RailConnection("fukushima", "osaka", "徒歩連絡", 4, 700)
        )
        connections.forEach { insertConnection(db, it) }

        val sampleTimes = listOf("07:00", "07:15", "07:30", "07:45", "08:00", "08:15", "08:30", "08:45")
        sampleTimes.forEachIndexed { index, departure ->
            insertTimetable(
                db, "osaka", "JR京都線", "新大阪",
                departure, addMinutes(departure, 4), "毎日（架空サンプル）"
            )
            insertTimetable(
                db, "namba", "Osaka Metro 御堂筋線", "天王寺",
                departure, addMinutes(departure, 6), "毎日（架空サンプル）"
            )
            if (index % 2 == 0) {
                insertTimetable(
                    db, "tennoji", "Osaka Metro 御堂筋線", "なんば",
                    departure, addMinutes(departure, 6), "毎日（架空サンプル）"
                )
            }
        }
        writeMeta(db, "dataset_name", "大阪エリア・開発用デモ")
        writeMeta(db, "dataset_version", "demo-1")
        writeMeta(db, "imported_at", "アプリ内蔵デモ")
        writeMeta(db, "is_demo", "true")
    }

    private fun addMinutes(time: String, minutes: Int): String {
        val parts = time.split(":")
        val total = (parts[0].toInt() * 60 + parts[1].toInt() + minutes) % (24 * 60)
        return "%02d:%02d".format(Locale.US, total / 60, total % 60)
    }

    private fun insertStation(db: SQLiteDatabase, station: Station) {
        val values = ContentValues().apply {
            put("id", station.id)
            put("name", station.name)
            if (station.latitude == null) putNull("latitude") else put("latitude", station.latitude)
            if (station.longitude == null) putNull("longitude") else put("longitude", station.longitude)
        }
        db.insertOrThrow("stations", null, values)
    }

    private fun insertConnection(db: SQLiteDatabase, connection: RailConnection) {
        val values = ContentValues().apply {
            put("from_id", connection.fromStationId)
            put("to_id", connection.toStationId)
            put("line_name", connection.lineName)
            put("minutes", connection.minutes)
            if (connection.distanceMeters == null) putNull("distance_meters")
            else put("distance_meters", connection.distanceMeters)
        }
        db.insertWithOnConflict("connections", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun insertTimetable(
        db: SQLiteDatabase,
        stationId: String,
        line: String,
        destination: String,
        departure: String,
        arrival: String,
        serviceDays: String,
        serviceId: String = "",
        departureSeconds: Int = parseTimeSeconds(departure),
        weekdayFlags: String = weekdayFlagsFromServiceDays(serviceDays),
        startDate: String = "00000000",
        endDate: String = "99999999"
    ) {
        val values = ContentValues().apply {
            put("station_id", stationId)
            put("line_name", line)
            put("destination", destination)
            put("departure_time", departure)
            put("arrival_time", arrival)
            put("service_days", serviceDays)
            put("service_id", serviceId)
            put("departure_seconds", departureSeconds)
            put("weekday_flags", weekdayFlags)
            put("start_date", startDate)
            put("end_date", endDate)
        }
        db.insertOrThrow("timetable", null, values)
    }

    private fun parseTimeSeconds(time: String): Int {
        val parts = time.split(":")
        if (parts.size < 2) return 0
        val hours = parts[0].toIntOrNull() ?: return 0
        val minutes = parts[1].toIntOrNull() ?: return 0
        val seconds = parts.getOrNull(2)?.toIntOrNull() ?: 0
        if (minutes !in 0..59 || seconds !in 0..59) return 0
        return hours * 3600 + minutes * 60 + seconds
    }

    private fun weekdayFlagsFromServiceDays(value: String): String = when {
        value.contains("毎日") -> "1111111"
        value.contains("平日") -> "1111100"
        value.contains("土休日") || value.contains("土日祝") -> "0000011"
        value.contains("土曜") -> "0000010"
        value.contains("日曜") || value.contains("日祝") -> "0000001"
        else -> "1111111"
    }

    private fun writeMeta
    private fun writeMeta(db: SQLiteDatabase, key: String, value: String) {
        db.insertWithOnConflict(
            "metadata",
            null,
            ContentValues().apply { put("key", key); put("value", value) },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun readMeta(db: SQLiteDatabase, key: String): String? =
        db.rawQuery("SELECT value FROM metadata WHERE key = ?", arrayOf(key)).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private fun countRows(db: SQLiteDatabase, table: String): Int =
        db.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

    private fun nowString(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.JAPAN).format(Date())

    companion object {
        private const val DATABASE_NAME = "nidamap_offline.db"
        private const val DATABASE_VERSION = 2
    }
}
