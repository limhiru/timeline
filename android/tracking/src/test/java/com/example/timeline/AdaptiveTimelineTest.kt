package com.example.timeline

import org.junit.Assert.*
import org.junit.Test

class AdaptiveTimelineTest {
    @Test fun startsWithImmediateCheckpointThenThirtyMinutes() {
        val p = AdaptiveLocationPolicy(); p.start(1000)
        assertTrue(p.checkpointDue(1000)); assertFalse(p.moving(1000))
        p.checkpointFinished(2000)
        assertFalse(p.checkpointDue(1_801_999)); assertTrue(p.checkpointDue(1_802_000))
    }
    @Test fun movementImmediatelyOverridesQuietDeadline() {
        val p = AdaptiveLocationPolicy(); p.start(0); p.checkpointFinished(0); p.movement(5000)
        assertTrue(p.moving(5000)); assertFalse(p.checkpointDue(5000))
        assertEquals(125_000L, p.nextDeadline(5000))
        assertEquals(10_000L, AdaptiveLocationPolicy.MOVING_INTERVAL)
    }
    @Test fun repeatedMovementExtendsHoldThenSettles() {
        val p = AdaptiveLocationPolicy(); p.start(0); p.movement(1000); p.movement(80_000)
        p.update(121_000); assertTrue(p.moving(121_000))
        p.update(200_000); assertFalse(p.moving(200_000))
        assertEquals(2_000_000L, p.nextDeadline(200_000))
    }
    @Test fun unsuccessfulFixBacksOffRatherThanLooping() {
        val p = AdaptiveLocationPolicy(); p.start(0); p.checkpointFinished(45_000)
        assertFalse(p.checkpointDue(46_000)); assertEquals(1_845_000L, p.nextDeadline(46_000))
    }
    @Test fun oldMovementCannotShortenCurrentHold() {
        val p = AdaptiveLocationPolicy(); p.start(0); p.movement(10_000); p.movement(1)
        assertEquals(130_000L, p.nextDeadline(10_000))
    }
    @Test fun accelerationNeedsSustainedEvidenceNotOneShake() {
        val d = MovementPulseDetector()
        assertFalse(d.acceleration(2.0, 0.0, 0.0, 0))
        assertFalse(d.acceleration(0.0, 0.0, 0.0, 1000))
        assertFalse(d.acceleration(1.0, 0.0, 0.0, 2000))
        assertFalse(d.acceleration(1.0, 0.0, 0.0, 2300))
        assertTrue(d.acceleration(1.0, 0.0, 0.0, 2600))
    }
    @Test fun invalidAndStaleSensorDataCannotTriggerMovement() {
        val d = MovementPulseDetector()
        assertFalse(d.acceleration(Double.NaN, 0.0, 0.0, 0))
        assertFalse(d.acceleration(1.0, 0.0, 0.0, 1))
        assertFalse(d.acceleration(1.0, 0.0, 0.0, 1))
        assertFalse(d.acceleration(1.0, 0.0, 0.0, 5000))
    }
    private fun point(time: Long, lon: Double = 127.0) = TrackPoint(37.0, lon, time, 5f)
    @Test fun stationaryCheckpointsPersistEvenAtSameCoordinate() {
        var points = TimelineSamples.append(emptyList(), point(0), false, true)
        points = TimelineSamples.append(points, point(1_800_000), false, true)
        assertEquals(2, points.size); assertEquals(listOf(0, 1), points.map { it.segment })
        assertEquals(0.0, Track(points = points).distance, 0.0)
    }
    @Test fun sparseGapsNeverBecomeTravelDistance() {
        var points = TimelineSamples.append(emptyList(), point(0), false, true)
        points = TimelineSamples.append(points, point(1_800_000, 128.0), false, true)
        points = TimelineSamples.append(points, point(1_810_000, 128.001), true, true)
        points = TimelineSamples.append(points, point(1_820_000, 128.002), true, false)
        assertEquals(listOf(0, 1, 2, 2), points.map { it.segment })
        assertTrue(Track(points = points).distance in 88.0..90.0)
    }
    @Test fun driftAndDuplicateSamplesAreNotAddedDuringMovingSegment() {
        val points = TimelineSamples.append(emptyList(), point(1000), true, true)
        assertEquals(points, TimelineSamples.append(points, point(1000, 127.001), true, false))
        assertEquals(points, TimelineSamples.append(points, point(2000, 127.00001), true, false))
        assertEquals(points, TimelineSamples.append(points, point(3000, 127.001), true, false, true))
    }
    @Test fun longGpsOutageBreaksSegment() {
        val points = TimelineSamples.append(emptyList(), point(1000), true, true)
        val later = TimelineSamples.append(points, point(60_000, 127.001), true, false)
        assertEquals(1, later.last().segment); assertEquals(0.0, Track(points = later).distance, 0.0)
    }
    @Test fun dailyStorageRoundTripKeepsSparseSegments() {
        val track = Track(id = "2026-10-05", started = 1000, points = listOf(point(1000), point(1_801_000).copy(segment = 1)))
        assertEquals(listOf(track), TrackCodec.decode(TrackCodec.encode(listOf(track))))
    }
}
