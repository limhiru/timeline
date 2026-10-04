package com.example.timeline

import org.junit.Assert.*
import org.junit.Test

class RouteGuidanceTest {
    @Test fun directionsDoNotInventHeading() {
        assertEquals("방향 확인 중", directionLabel(null))
        assertEquals("정면으로 가세요", directionLabel(0.0))
        assertEquals("오른쪽으로 가세요", directionLabel(80.0))
        assertEquals("왼쪽으로 가세요", directionLabel(-80.0))
        assertEquals("뒤로 돌아가세요", directionLabel(179.0))
    }
    @Test fun nextSegmentRespectsPauseGaps() {
        val a = TrackPoint(37.0, 127.0, 1, 5f, 0)
        val b = TrackPoint(37.0, 127.001, 2, 5f, 1)
        val state = TrackerState(track = Track(points = listOf(a, b)), position = b.copy(longitude = 127.002), targetIndex = 1)
        assertEquals("기록 공백", nextDirection(state))
        assertEquals("출발점", nextDirection(state.copy(targetIndex = 0)))
        assertNull(nextDirection(state.copy(position = null)))
    }
}
