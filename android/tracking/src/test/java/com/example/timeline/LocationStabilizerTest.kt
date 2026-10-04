package com.example.timeline

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class LocationStabilizerTest {
    private fun point(east: Double, north: Double = 0.0, time: Long = 100_000, accuracy: Float = 5f) =
        TrackPoint(37.0 + north / 111_195, 127.0 + east / (111_195 * cos(Math.toRadians(37.0))), time, accuracy)

    @Test fun stationaryJitterIsHeldWithoutInventingAccuracy() {
        val filter = LocationStabilizer()
        val anchor = filter.accept(point(0.0), 0)!!.point
        for (i in 1..100) {
            val fix = filter.accept(point(if (i % 2 == 0) 7.0 else -7.0, time = 100_000 + i * 1000L, accuracy = 8f), i * 1000L, MotionState.STILL)!!
            assertTrue(fix.heldStill)
            assertEquals(0.0, distance(anchor, fix.point), 0.001)
            assertTrue(fix.point.accuracy >= 8f)
        }
    }
    @Test fun noisyWalkingKeepsMovingAndReducesCrossTrackError() {
        val filter = LocationStabilizer()
        var rawError = 0.0; var filteredError = 0.0
        var latest: TrackPoint? = null
        for (i in 0..100) {
            val sample = point(i * 1.4, if (i % 2 == 0) 7.0 else -7.0, 100_000 + i * 1000L, 10f)
            latest = filter.accept(sample, i * 1000L, MotionState.MOVING)!!.point
            rawError += abs(sample.latitude - 37.0)
            filteredError += abs(latest.latitude - 37.0)
        }
        assertTrue(filteredError < rawError * 0.5)
        assertTrue(distance(point(140.0), latest!!) < 7.0)
    }
    @Test fun accurateGpsSpeedOverridesFalseStationaryEvidence() {
        val filter = LocationStabilizer(); filter.accept(point(0.0), 0)
        val fix = filter.accept(point(5.0, time = 102_000), 2000, MotionState.STILL, reliableSpeed = 1.5)!!
        assertFalse(fix.heldStill)
        assertTrue(distance(point(0.0), fix.point) > 2)
    }
    @Test fun betterAccuracyCanCorrectAnInitiallyBiasedStationaryAnchor() {
        val filter = LocationStabilizer(); filter.accept(point(12.0, accuracy = 20f), 0)
        val fix = filter.accept(point(0.0, time = 102_000, accuracy = 3f), 2000, MotionState.STILL)!!
        assertFalse(fix.heldStill)
        assertTrue(distance(point(0.0), fix.point) < 1)
    }
    @Test fun stationarySensorsRejectLargeContradictoryDrift() {
        val filter = LocationStabilizer(); filter.accept(point(0.0), 0)
        assertNull(filter.accept(point(18.0, time = 103_000), 3000, MotionState.STILL))
        val fix = filter.accept(point(1.0, time = 105_000), 5000, MotionState.STILL)!!
        assertEquals(0.0, distance(point(0.0), fix.point), .001)
    }
    @Test fun jumpsAndOutOfOrderFixesNeverChangePosition() {
        val filter = LocationStabilizer(); filter.accept(point(0.0), 1000)
        assertNull(filter.accept(point(200.0, time = 101_000), 2000))
        assertNull(filter.accept(point(20.0, time = 100_500), 1500))
        val fix = filter.accept(point(1.0, time = 102_000), 3000)!!
        assertTrue(distance(point(0.0), fix.point) < 1.1)
    }
    @Test fun firstFixMustBeAccurateAndSensorlessFallbackWorks() {
        val filter = LocationStabilizer()
        assertNull(filter.accept(point(0.0, accuracy = 25f), 0))
        assertNotNull(filter.accept(point(0.0), 1000))
        assertNotNull(filter.accept(point(2.0, time = 102_000), 2000))
    }
    @Test fun outageRequiresTwoGoodFixesAndMarksGap() {
        val filter = LocationStabilizer(); filter.accept(point(0.0), 0)
        assertNull(filter.accept(point(1000.0, time = 130_000), 30_000))
        assertNull(filter.accept(point(500.0, time = 132_000), 32_000))
        assertNull(filter.accept(point(500.0, time = 133_000, accuracy = 25f), 33_000))
        assertNull(filter.accept(point(500.0, time = 134_000), 34_000))
        val fix = filter.accept(point(503.0, time = 136_000), 36_000)!!
        assertTrue(fix.gap)
        assertEquals(0.0, distance(point(503.0), fix.point), .001)
    }
    @Test fun outageDoesNotInflateRecordedDistance() {
        val engine = RouteEngine(); engine.start(100_000, 0)
        engine.accept(point(0.0), 100_000, 0)
        engine.accept(point(5.0, time = 102_000), 102_000, 2000)
        assertFalse(engine.accept(point(200.0, time = 130_000), 130_000, 30_000))
        assertTrue(engine.accept(point(202.0, time = 132_000), 132_000, 32_000))
        assertEquals(listOf(0, 0, 1), engine.state.track.points.map { it.segment })
        assertTrue(engine.state.track.distance < 5.1)
    }
    @Test fun reverseGuidanceRejectsSpikeBeforeAdvancingWaypoint() {
        val engine = RouteEngine()
        engine.select(Track(points = listOf(point(0.0), point(100.0), point(200.0))))
        engine.beginReturn(0)
        engine.accept(point(200.0), 100_000, 0)
        val previous = engine.state.position
        assertFalse(engine.accept(point(100.0, time = 101_000), 101_000, 1000))
        assertEquals(previous, engine.state.position)
        assertEquals(1, engine.state.targetIndex)
    }
    @Test fun stationaryJitterDoesNotGrowTrack() {
        val engine = RouteEngine(); engine.start(100_000, 0)
        engine.accept(point(0.0), 100_000, 0)
        for (i in 1..60) engine.accept(point(if (i % 2 == 0) 6.0 else -6.0, time = 100_000 + i * 1000L),
            100_000 + i * 1000L, i * 1000L, MotionState.STILL)
        assertEquals(1, engine.state.track.points.size)
        assertEquals(0.0, engine.state.track.distance, .001)
    }
    @Test fun resetAllowsFreshStartAtAnotherPlace() {
        val filter = LocationStabilizer(); filter.accept(point(0.0), 1000); filter.reset()
        val fix = filter.accept(point(1000.0), 2000)!!
        assertFalse(fix.gap)
        assertEquals(0.0, distance(point(1000.0), fix.point), .001)
    }
    @Test fun antimeridianUsesShortestLongitudeArc() {
        val filter = LocationStabilizer()
        filter.accept(TrackPoint(0.0, 179.99999, 100_000, 5f), 0)
        val fix = filter.accept(TrackPoint(0.0, -179.99999, 102_000, 5f), 2000)!!
        assertTrue(abs(fix.point.longitude) > 179.99)
    }
    @Test fun turnsAreNotForcedToDeviceHeading() {
        val filter = LocationStabilizer(); filter.accept(point(0.0), 0)
        for (i in 1..20) filter.accept(point(i * 2.0, time = 100_000 + i * 1000L), i * 1000L, MotionState.MOVING)
        var latest: TrackPoint? = null
        for (i in 21..40) latest = filter.accept(point(40.0, (i - 20) * 2.0, 100_000 + i * 1000L), i * 1000L, MotionState.MOVING)!!.point
        assertTrue(distance(point(40.0, 40.0), latest!!) < 4)
    }
}
