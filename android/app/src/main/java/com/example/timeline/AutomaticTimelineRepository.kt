package com.example.timeline

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors

data class AutomaticTimelineState(
    val days: List<Track> = emptyList(), val enabled: Boolean = false,
    val moving: Boolean = false, val acquiring: Boolean = false,
    val nextCheck: Long? = null, val detection: String = "이동 감지 대기",
    val status: String? = null, val storageError: String? = null
)

/** Separate daily files: automatic sparse samples cannot be selected as a return route. */
class AutomaticTimelineRepository private constructor(context: Context) {
    companion object {
        @Volatile private var instance: AutomaticTimelineRepository? = null
        fun get(context: Context): AutomaticTimelineRepository = instance ?: synchronized(this) {
            instance ?: AutomaticTimelineRepository(context.applicationContext).also { instance = it }
        }
        fun dayId(time: Long = System.currentTimeMillis()): String =
            Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDate().toString()
    }
    private val directory = File(context.filesDir, "automatic-timeline")
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val blocked = mutableSetOf<String>()
    private val mutable = MutableStateFlow(AutomaticTimelineState())
    val state = mutable.asStateFlow()
    init {
        val days = mutableListOf<Track>()
        val names = directory.listFiles().orEmpty().map { it.name.removeSuffix(".bak") }.filter { it.endsWith(".json") }.distinct()
        names.forEach { name ->
            val id = name.removeSuffix(".json")
            try {
                LocalDate.parse(id)
                val tracks = TrackCodec.decode(AtomicFile(File(directory, name)).openRead().bufferedReader().use { it.readText() })
                require(tracks.size == 1 && tracks.single().id == id)
                val track = tracks.single()
                require(track.points.zipWithNext().all { (a, b) -> b.timestamp > a.timestamp && b.segment >= a.segment })
                days += track
            } catch (_: Exception) { blocked += id }
        }
        mutable.value = AutomaticTimelineState(days = days.sortedByDescending { it.id },
            storageError = if (blocked.isEmpty()) null else "일부 자동 기록을 읽지 못했습니다. 해당 날짜의 원본은 덮어쓰지 않습니다.")
    }
    fun update(transform: (AutomaticTimelineState) -> AutomaticTimelineState) { mutable.value = transform(mutable.value) }
    fun append(point: TrackPoint, moving: Boolean, breakSegment: Boolean, heldStill: Boolean): Boolean {
        val id = dayId(point.timestamp)
        if (id in blocked) return false
        val old = mutable.value.days.firstOrNull { it.id == id } ?: Track(id = id, started = point.timestamp)
        val points = TimelineSamples.append(old.points, point, moving, breakSegment, heldStill)
        if (points === old.points) return false
        val track = old.copy(points = points)
        update { it.copy(days = (listOf(track) + it.days.filter { day -> day.id != id }).sortedByDescending { day -> day.id }) }
        io.execute {
            var stream: java.io.FileOutputStream? = null
            val file = AtomicFile(File(directory, "$id.json"))
            try {
                check(directory.isDirectory || directory.mkdirs())
                stream = file.startWrite(); stream.write(TrackCodec.encode(listOf(track)).toByteArray(Charsets.UTF_8)); file.finishWrite(stream)
            } catch (error: Exception) {
                file.failWrite(stream)
                main.post { update { it.copy(storageError = "자동 기록 저장 실패: ${error.message}") } }
            }
        }
        return true
    }
}
