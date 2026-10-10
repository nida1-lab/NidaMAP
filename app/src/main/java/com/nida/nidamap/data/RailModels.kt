package com.nida.nidamap.data

data class Station(
    val id: String,
    val name: String,
    val latitude: Double? = null,
    val longitude: Double? = null
)

data class RailConnection(
    val fromStationId: String,
    val toStationId: String,
    val lineName: String,
    val minutes: Int,
    val distanceMeters: Int? = null
)

data class TimetableEntry(
    val id: Long,
    val stationId: String,
    val lineName: String,
    val destination: String,
    val departureTime: String,
    val arrivalTime: String,
    val serviceDays: String,
    val serviceId: String = "",
    val departureSeconds: Int = 0,
    val weekdayFlags: String = "1111111",
    val startDate: String = "00000000",
    val endDate: String = "99999999"
)

data class RouteLeg(
    val from: Station,
    val to: Station,
    val lineName: String,
    val minutes: Int,
    val transferMinutes: Int = 0
)

data class RouteResult(
    val stations: List<Station>,
    val legs: List<RouteLeg>,
    val trainMinutes: Int,
    val transferMinutes: Int,
    val transfers: Int
) {
    val totalMinutes: Int
        get() = trainMinutes + transferMinutes
}

data class DatasetInfo(
    val name: String,
    val version: String,
    val stationCount: Int,
    val connectionCount: Int,
    val timetableCount: Int,
    val importedAt: String,
    val isDemo: Boolean
)

data class ImportSummary(
    val datasetName: String,
    val stationCount: Int,
    val connectionCount: Int,
    val timetableCount: Int
)
