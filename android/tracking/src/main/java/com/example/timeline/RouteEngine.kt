package com.example.timeline

import java.util.UUID
import kotlin.math.*

enum class LocationSource { DEVICE, PHONE }
data class TrackPoint(
    val latitude: Double, val longitude: Double, val timestamp: Long,
    val accuracy: Float, val segment: Int = 0, val source: LocationSource = LocationSource.DEVICE
)
data class Track(
    val id: String = UUID.randomUUID().toString(), val started: Long = System.currentTimeMillis(),
    val points: List<TrackPoint> = emptyList(), val elapsedSeconds: Long = 0
) {
    val distance: Double get() = points.zipWithNext().sumOf { (a, b) -> if (a.segment == b.segment) distance(a, b) else 0.0 }
}
fun distance(a: TrackPoint, b: TrackPoint): Double {
    val p1 = Math.toRadians(a.latitude); val p2 = Math.toRadians(b.latitude)
    val dLat = p2 - p1; val dLon = Math.toRadians(b.longitude - a.longitude)
    val h = sin(dLat / 2).pow(2) + cos(p1) * cos(p2) * sin(dLon / 2).pow(2)
    return 6371000 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
}
fun bearing(a: TrackPoint, b: TrackPoint): Double {
    val p1 = Math.toRadians(a.latitude); val p2 = Math.toRadians(b.latitude)
    val delta = Math.toRadians(b.longitude - a.longitude)
    return (Math.toDegrees(atan2(sin(delta) * cos(p2), cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(delta))) + 360) % 360
}
fun relativeAngle(bearing: Double, heading: Double) = (bearing - heading + 540) % 360 - 180

enum class Mode { IDLE, RECORDING, PAUSED, RETURNING }
data class TrackerState(
    val track: Track = Track(), val mode: Mode = Mode.IDLE,
    val position: TrackPoint? = null, val heading: Double? = null,
    val targetIndex: Int? = null, val awaitingFix: Boolean = false,
    val motion: MotionState = MotionState.UNKNOWN, val heldStill: Boolean = false,
    val locationSource: LocationSource = LocationSource.DEVICE, val phoneConnected: Boolean = false,
    val phoneSharing: Boolean = false,
    val message: String? = null, val history: List<Track> = emptyList(), val serviceRunning: Boolean = false
) {
    val target: TrackPoint? get() = targetIndex?.let { track.points.getOrNull(it) }
    val targetDistance: Double? get() = target?.let { t -> position?.let { distance(it, t) } }
    val targetAngle: Double? get() = target?.let { t -> position?.let { p -> heading?.let { relativeAngle(bearing(p, t), it) } } }
}

/** Pure navigation/recording logic; service owns clocks and sensor lifetimes. */
class RouteEngine {
    var state = TrackerState()
    private var segment = 0
    private var anchorMillis: Long? = null
    private var baseSeconds = 0L
    private val locationFilter = LocationStabilizer()
    fun start(now: Long, monotonicMillis: Long) {
        locationFilter.reset()
        segment = 0; baseSeconds = 0; anchorMillis = monotonicMillis
        state = state.copy(track = Track(started = now), mode = Mode.RECORDING, position = null,
            targetIndex = null, awaitingFix = true, heading = null, message = null,
            motion = MotionState.UNKNOWN, heldStill = false, locationSource = LocationSource.DEVICE)
    }
    fun tick(monotonicMillis: Long) {
        if (state.mode == Mode.RECORDING) {
            val elapsed = baseSeconds + ((monotonicMillis - (anchorMillis ?: monotonicMillis)) / 1000).coerceAtLeast(0)
            state = state.copy(track = state.track.copy(elapsedSeconds = elapsed))
        }
    }
    fun togglePause(monotonicMillis: Long) {
        when (state.mode) {
            Mode.RECORDING -> { tick(monotonicMillis); baseSeconds = state.track.elapsedSeconds; anchorMillis = null; state = state.copy(mode = Mode.PAUSED) }
            Mode.PAUSED -> {
                segment++; anchorMillis = monotonicMillis; locationFilter.reset()
                state = state.copy(mode = Mode.RECORDING, position = null, awaitingFix = true, heldStill = false)
            }
            else -> Unit
        }
    }
    fun stop(monotonicMillis: Long) {
        tick(monotonicMillis); anchorMillis = null
        locationFilter.reset()
        state = state.copy(mode = Mode.IDLE, targetIndex = null, awaitingFix = false)
    }
    fun beginReturn(monotonicMillis: Long) {
        if (state.track.points.size < 2) { state = state.copy(message = "경로를 먼저 기록해 주세요."); return }
        tick(monotonicMillis); anchorMillis = null
        locationFilter.reset()
        // Wait for a new GPS fix rather than navigating from an old saved position.
        state = state.copy(mode = Mode.RETURNING, targetIndex = null, position = null, awaitingFix = true, message = null, heldStill = false)
    }
    fun accept(rawPoint: TrackPoint, now: Long, sampleMillis: Long = rawPoint.timestamp,
               motion: MotionState = MotionState.UNKNOWN, reliableSpeed: Double? = null): Boolean {
        val point = rawPoint
        if (!point.latitude.isFinite() || !point.longitude.isFinite() || point.latitude !in -90.0..90.0 || point.longitude !in -180.0..180.0 ||
            !point.accuracy.isFinite() || point.accuracy !in 0f..30f || abs(now - point.timestamp) > 20_000) return false
        if (point.source != state.locationSource) {
            if (point.accuracy > 20f) return false
            locationFilter.reset()
            // A source change can contain GPS bias, not actual movement. Never count its jump.
            if (state.mode == Mode.RECORDING && state.track.points.lastOrNull()?.segment == segment) segment++
            state = state.copy(locationSource = point.source, position = null, awaitingFix = true, heldStill = false)
        }
        val fix = locationFilter.accept(point, sampleMillis, motion, reliableSpeed) ?: return false
        return acceptFiltered(fix, motion)
    }
    private fun acceptFiltered(fix: StabilizedFix, motion: MotionState): Boolean {
        val point = fix.point
        state = state.copy(position = point, awaitingFix = false, motion = motion, heldStill = fix.heldStill)
        if (state.mode == Mode.RETURNING) {
            var index = state.targetIndex ?: state.track.points.indices.minWithOrNull(compareBy<Int> { distance(point, state.track.points[it]) }.thenByDescending { it }) ?: return true
            val threshold = point.accuracy.toDouble().coerceIn(8.0, 15.0)
            while (distance(point, state.track.points[index]) <= threshold) {
                if (index == 0) {
                    state = state.copy(mode = Mode.IDLE, targetIndex = null, message = "출발점에 도착했습니다.")
                    return true
                }
                index--
            }
            state = state.copy(targetIndex = index)
        }
        if (state.mode != Mode.RECORDING) return true
        val last = state.track.points.lastOrNull()
        if (fix.gap && last?.segment == segment) segment++
        if (last != null) {
            if (point.timestamp <= last.timestamp) return true
            if (last.segment == segment) {
                val meters = distance(last, point); val seconds = (point.timestamp - last.timestamp) / 1000.0
                val spacing = max(3.0, max(last.accuracy, point.accuracy) * 0.5)
                if (fix.heldStill || meters < spacing || meters / seconds > 12) return true
            }
        }
        state = state.copy(track = state.track.copy(points = state.track.points + point.copy(segment = segment)))
        return true
    }
    fun select(track: Track) {
        if (state.mode == Mode.IDLE) {
            locationFilter.reset()
            state = state.copy(track = track, position = null, targetIndex = null, heading = null, heldStill = false, motion = MotionState.UNKNOWN)
        }
    }
}
