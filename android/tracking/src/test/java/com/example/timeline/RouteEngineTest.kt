package com.example.timeline

import org.junit.Assert.*
import org.junit.Test

class RouteEngineTest {
    private fun point(lon: Double, time: Long = 100_000, accuracy: Float = 5f) = TrackPoint(37.0, lon, time, accuracy)
    @Test fun distanceAndBearing() {
        val a = point(127.0); val b = point(127.001)
        assertEquals(88.8, distance(a, b), 1.0)
        assertEquals(90.0, bearing(a, b), 0.01)
        assertEquals(2.0, relativeAngle(1.0, 359.0), 0.01)
    }
    @Test fun pauseExcludesMovementAndTime() {
        val engine = RouteEngine(); engine.start(100_000, 0)
        engine.accept(point(127.0), 100_000)
        engine.accept(point(127.001, 110_000), 110_000)
        engine.togglePause(10_000)
        engine.accept(point(127.02, 150_000), 150_000)
        engine.togglePause(60_000)
        engine.accept(point(127.02, 160_000), 160_000)
        engine.accept(point(127.021, 170_000), 170_000)
        engine.stop(70_000)
        assertEquals(20L, engine.state.track.elapsedSeconds)
        assertEquals(177.6, engine.state.track.distance, 2.0)
        assertEquals(listOf(0, 0, 1, 1), engine.state.track.points.map { it.segment })
    }
    @Test fun badFixesAndJumpsAreNotRecorded() {
        val engine = RouteEngine(); engine.start(100_000, 0)
        assertFalse(engine.accept(point(127.0, accuracy = 100f), 100_000))
        assertFalse(engine.accept(point(127.0, time = 1), 100_000))
        assertFalse(engine.accept(point(Double.NaN), 100_000))
        engine.accept(point(127.0), 100_000)
        engine.accept(point(128.0, 101_000), 101_000)
        engine.accept(point(127.0, 99_000), 100_000)
        assertEquals(1, engine.state.track.points.size)
    }
    @Test fun returnFollowsOrderedPointsAndArrives() {
        val engine = RouteEngine()
        engine.select(Track(points = listOf(point(127.0), point(127.001), point(127.002))))
        engine.beginReturn(0)
        assertTrue(engine.state.awaitingFix)
        engine.accept(point(127.002), 100_000)
        assertEquals(1, engine.state.targetIndex)
        engine.accept(point(127.001), 100_000)
        assertEquals(0, engine.state.targetIndex)
        engine.accept(point(127.0), 100_000)
        assertEquals(Mode.IDLE, engine.state.mode)
        assertNull(engine.state.targetIndex)
        assertEquals("출발점에 도착했습니다.", engine.state.message)
    }
    @Test fun returnStartsAtNearestPointAndKeepsWaitingForPoorAccuracy() {
        val engine = RouteEngine()
        engine.select(Track(points = listOf(point(127.0), point(127.001), point(127.002))))
        engine.beginReturn(0)
        engine.accept(point(127.001, accuracy = 40f), 100_000)
        assertTrue(engine.state.awaitingFix)
        engine.accept(point(127.001), 100_000)
        assertEquals(0, engine.state.targetIndex)
    }
    @Test fun savedTrackRoundTripPreservesIdentityAndSegments() {
        val track = Track(points = listOf(point(127.0), point(127.001).copy(segment = 1)), elapsedSeconds = 125)
        assertEquals(listOf(track), TrackCodec.decode(TrackCodec.encode(listOf(track))))
    }
    @Test(expected = Exception::class) fun corruptDataIsReported() { TrackCodec.decode("broken") }
    @Test(expected = IllegalArgumentException::class) fun invalidSavedCoordinatesAreRejected() {
        TrackCodec.decode(TrackCodec.encode(listOf(Track(points = listOf(point(200.0))))))
    }
}
