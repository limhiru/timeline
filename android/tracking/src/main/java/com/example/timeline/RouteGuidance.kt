package com.example.timeline

import kotlin.math.abs

fun directionLabel(angle: Double?): String = when {
    angle == null -> "방향 확인 중"
    abs(angle) >= 135 -> "뒤로 돌아가세요"
    angle >= 30 -> "오른쪽으로 가세요"
    angle <= -30 -> "왼쪽으로 가세요"
    else -> "정면으로 가세요"
}
/** Next recorded segment, not a road-routing turn instruction. */
fun nextDirection(state: TrackerState): String? {
    val index = state.targetIndex ?: return null
    val position = state.position ?: return null
    if (index == 0) return "출발점"
    val current = state.track.points[index]; val next = state.track.points[index - 1]
    if (current.segment != next.segment) return "기록 공백"
    return directionLabel(relativeAngle(bearing(current, next), bearing(position, current)))
}
