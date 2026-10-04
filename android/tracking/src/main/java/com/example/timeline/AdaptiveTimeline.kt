package com.example.timeline

import kotlin.math.max
import kotlin.math.sqrt

/** Scheduling uses elapsed realtime, never the editable wall clock. */
class AdaptiveLocationPolicy {
    companion object {
        const val QUIET_INTERVAL = 30 * 60_000L
        const val MOVING_INTERVAL = 10_000L
        const val SETTLE_DELAY = 2 * 60_000L
        const val FIX_TIMEOUT = 45_000L
    }
    private var movingUntil: Long? = null
    private var checkpointAt = 0L
    fun start(now: Long) { require(now >= 0); movingUntil = null; checkpointAt = now }
    fun movement(now: Long) {
        require(now >= 0)
        movingUntil = max(movingUntil ?: 0, now + SETTLE_DELAY)
    }
    fun update(now: Long) {
        if (movingUntil?.let { now >= it } == true) {
            movingUntil = null
            checkpointAt = now + QUIET_INTERVAL
        }
    }
    fun moving(now: Long) = movingUntil?.let { now < it } == true
    fun checkpointDue(now: Long) = !moving(now) && now >= checkpointAt
    // Failed acquisition also backs off; no battery-draining retry loop indoors.
    fun checkpointFinished(now: Long) { checkpointAt = now + QUIET_INTERVAL }
    fun nextDeadline(now: Long) = if (moving(now)) movingUntil!! else checkpointAt
}

/** Sustained acceleration is a fallback movement hint, NOT an estimate of position. */
class MovementPulseDetector {
    private var first: Long? = null
    private var last: Long? = null
    private var count = 0
    fun reset() { first = null; last = null; count = 0 }
    fun acceleration(x: Double, y: Double, z: Double, time: Long): Boolean {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite() || time < 0) { reset(); return false }
        if (last?.let { time <= it } == true) return false
        if (last?.let { time - it > 1500 } == true) reset()
        last = time
        if (sqrt(x * x + y * y + z * z) < .4) return false
        if (first == null || time - first!! > 1500) { first = time; count = 0 }
        count++
        if (count >= 3 && time - first!! >= 500) { first = null; count = 0; return true }
        return false
    }
}

/** Sparse checkpoints are never joined into an invented route or counted as travel. */
object TimelineSamples {
    fun append(points: List<TrackPoint>, point: TrackPoint, moving: Boolean,
               breakSegment: Boolean, heldStill: Boolean = false): List<TrackPoint> {
        require(point.latitude.isFinite() && point.latitude in -90.0..90.0 &&
            point.longitude.isFinite() && point.longitude in -180.0..180.0 &&
            point.accuracy.isFinite() && point.accuracy in 0f..30f && point.timestamp >= 0)
        val previous = points.lastOrNull()
        if (previous != null && point.timestamp <= previous.timestamp) return points
        val gap = previous == null || breakSegment || !moving || point.timestamp - previous.timestamp > 20_000
        if (moving && !gap && (heldStill || distance(previous!!, point) < max(3.0, max(previous.accuracy, point.accuracy) * .5))) return points
        val segment = if (previous == null) 0 else previous.segment + if (gap) 1 else 0
        return points + point.copy(segment = segment)
    }
}
