package com.nida.nidamap.routing

import com.nida.nidamap.data.RailConnection
import com.nida.nidamap.data.Station
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class RoutePlannerTest {
    private val stations = listOf(
        Station("a", "A"),
        Station("b", "B"),
        Station("c", "C"),
        Station("d", "D")
    )

    @Test
    fun transferPenaltyIsIncludedInRouteCost() {
        val connections = listOf(
            RailConnection("a", "b", "Blue", 2),
            RailConnection("b", "d", "Blue", 10),
            RailConnection("a", "c", "Green", 3),
            RailConnection("c", "d", "Orange", 3)
        )
        val result = RoutePlanner.findRoute(stations, connections, "a", "d")
        assertNotNull(result)
        assertEquals(listOf("a", "c", "d"), result!!.stations.map { it.id })
        assertEquals(6, result.trainMinutes)
        assertEquals(4, result.transferMinutes)
        assertEquals(10, result.totalMinutes)
        assertEquals(1, result.transfers)
    }

    @Test
    fun walkingConnectionDoesNotCreateRailTransfer() {
        val connections = listOf(
            RailConnection("a", "b", "Blue", 2),
            RailConnection("b", "c", "徒歩連絡", 3),
            RailConnection("c", "d", "Blue", 2)
        )
        val result = RoutePlanner.findRoute(stations, connections, "a", "d")
        assertNotNull(result)
        assertEquals(listOf("a", "b", "c", "d"), result!!.stations.map { it.id })
        assertEquals(7, result.totalMinutes)
        assertEquals(0, result.transfers)
        assertEquals(0, result.transferMinutes)
    }

    @Test
    fun disconnectedStationHasNoRoute() {
        val isolated = stations + Station("e", "E")
        val result = RoutePlanner.findRoute(
            isolated,
            listOf(RailConnection("a", "b", "Blue", 2)),
            "a",
            "e"
        )
        assertNull(result)
    }

    @Test
    fun sameStartAndGoalIsAZeroLegRoute() {
        val result = RoutePlanner.findRoute(stations, emptyList(), "a", "a")
        assertNotNull(result)
        assertEquals(0, result!!.totalMinutes)
        assertEquals(0, result.legs.size)
    }
}
