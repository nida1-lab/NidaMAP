package com.nida.nidamap.routing

import com.nida.nidamap.data.RailConnection
import com.nida.nidamap.data.RouteLeg
import com.nida.nidamap.data.RouteResult
import com.nida.nidamap.data.Station
import java.util.PriorityQueue

/**
 * Offline shortest-time route planner.
 *
 * Transfer time is a planning estimate, not a live or official railway value.
 */
object RoutePlanner {
    private const val WALK_LINE = "徒歩連絡"
    private const val DEFAULT_TRANSFER_MINUTES = 4

    private data class StateKey(val stationId: String, val activeLine: String)
    private data class QueueNode(val key: StateKey, val cost: Int)
    private data class Previous(val key: StateKey, val leg: RouteLeg)

    fun findRoute(
        stations: List<Station>,
        connections: List<RailConnection>,
        startStationId: String,
        goalStationId: String
    ): RouteResult? {
        val stationById = stations.associateBy { it.id }
        val start = stationById[startStationId] ?: return null
        val goal = stationById[goalStationId] ?: return null
        if (start.id == goal.id) return RouteResult(listOf(start), emptyList(), 0, 0, 0)

        val adjacency = mutableMapOf<String, MutableList<Pair<String, RailConnection>>>()
        connections.forEach { connection ->
            if (stationById.containsKey(connection.fromStationId) &&
                stationById.containsKey(connection.toStationId)
            ) {
                adjacency.getOrPut(connection.fromStationId) { mutableListOf() }
                    .add(connection.toStationId to connection)
                adjacency.getOrPut(connection.toStationId) { mutableListOf() }
                    .add(connection.fromStationId to connection)
            }
        }

        val initial = StateKey(start.id, "")
        val distance = mutableMapOf(initial to 0)
        val previous = mutableMapOf<StateKey, Previous>()
        val queue = PriorityQueue<QueueNode>(compareBy { it.cost })
        queue.add(QueueNode(initial, 0))
        var endKey: StateKey? = null

        while (queue.isNotEmpty()) {
            val item = queue.remove()
            if (item.cost != distance[item.key]) continue
            if (item.key.stationId == goal.id) {
                endKey = item.key
                break
            }

            adjacency[item.key.stationId].orEmpty().forEach { (nextId, connection) ->
                val walking = isWalking(connection.lineName)
                val changeLine = !walking &&
                    item.key.activeLine.isNotBlank() &&
                    !sameService(item.key.activeLine, connection.lineName)
                val transferPenalty = if (changeLine) DEFAULT_TRANSFER_MINUTES else 0
                val nextActiveLine = if (walking) item.key.activeLine else connection.lineName
                val nextKey = StateKey(nextId, nextActiveLine)
                val nextCost = item.cost + connection.minutes + transferPenalty
                if (nextCost < (distance[nextKey] ?: Int.MAX_VALUE)) {
                    val from = stationById[item.key.stationId] ?: return@forEach
                    val to = stationById[nextId] ?: return@forEach
                    distance[nextKey] = nextCost
                    previous[nextKey] = Previous(
                        item.key,
                        RouteLeg(
                            from = from,
                            to = to,
                            lineName = connection.lineName,
                            minutes = connection.minutes,
                            transferMinutes = transferPenalty
                        )
                    )
                    queue.add(QueueNode(nextKey, nextCost))
                }
            }
        }

        val finalKey = endKey ?: return null
        val reversedLegs = mutableListOf<RouteLeg>()
        var cursor = finalKey
        while (cursor != initial) {
            val step = previous[cursor] ?: return null
            reversedLegs += step.leg
            cursor = step.key
        }
        val legs = reversedLegs.asReversed()
        val pathStations = listOf(start) + legs.map { it.to }
        var lastRailLine = ""
        var transfers = 0
        legs.forEach { leg ->
            if (!isWalking(leg.lineName)) {
                if (lastRailLine.isNotBlank() && !sameService(lastRailLine, leg.lineName)) {
                    transfers += 1
                }
                lastRailLine = leg.lineName
            }
        }
        return RouteResult(
            stations = pathStations,
            legs = legs,
            trainMinutes = legs.sumOf { it.minutes },
            transferMinutes = legs.sumOf { it.transferMinutes },
            transfers = transfers
        )
    }

    private fun isWalking(line: String): Boolean =
        line.contains(WALK_LINE) || line.contains("徒歩")

    private fun sameService(first: String, second: String): Boolean {
        if (first == second) return true
        val bothThroughServices =
            (first.contains("御堂筋線") || first.contains("北大阪急行")) &&
            (second.contains("御堂筋線") || second.contains("北大阪急行")) &&
            (first.contains("直通") || second.contains("直通"))
        return bothThroughServices
    }
}
