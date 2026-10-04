package com.example.timeline

import android.app.Application
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import android.os.Handler
import android.os.Looper

object TrackCodec {
    fun encode(tracks: List<Track>): String = JSONArray().apply {
        tracks.forEach { track -> put(JSONObject().apply {
            put("id", track.id); put("started", track.started); put("elapsed", track.elapsedSeconds)
            put("points", JSONArray().apply { track.points.forEach { p -> put(JSONObject().apply {
                put("latitude", p.latitude); put("longitude", p.longitude); put("timestamp", p.timestamp)
                put("accuracy", p.accuracy.toDouble()); put("segment", p.segment); put("source", p.source.name)
            }) } })
        }) }
    }.toString()
    fun decode(json: String): List<Track> {
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i); val points = obj.getJSONArray("points")
            val decoded = (0 until points.length()).map { n -> points.getJSONObject(n).let {
                TrackPoint(it.getDouble("latitude"), it.getDouble("longitude"), it.getLong("timestamp"), it.getDouble("accuracy").toFloat(), it.getInt("segment"),
                    LocationSource.valueOf(it.optString("source", LocationSource.DEVICE.name)))
            } }
            require(decoded.all { it.latitude.isFinite() && it.longitude.isFinite() && it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 && it.accuracy.isFinite() && it.accuracy >= 0 })
            Track(obj.getString("id"), obj.getLong("started"), decoded, obj.getLong("elapsed").also { require(it >= 0) })
        }
    }
}
class TimelineApplication : Application() {
    lateinit var repository: TrackRepository
        private set
    override fun onCreate() { super.onCreate(); repository = TrackRepository(this) }
}
class TrackRepository(app: Application) {
    val engine = RouteEngine()
    var uiVisible = true
    private val mutable = MutableStateFlow(engine.state)
    val state = mutable.asStateFlow()
    private val file = AtomicFile(File(app.filesDir, "tracks.json"))
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var writable = true
    private var history = emptyList<Track>()
    init {
        try { if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) history = TrackCodec.decode(file.openRead().bufferedReader().use { it.readText() }) }
        catch (error: Exception) {
            writable = false
            engine.state = engine.state.copy(message = "저장 기록을 읽지 못했습니다. 원본을 보호하기 위해 저장을 중단했습니다: ${error.message}")
        }
        publish()
    }
    fun publish() { engine.state = engine.state.copy(history = history); mutable.value = engine.state }
    fun message(text: String?) { engine.state = engine.state.copy(message = text); publish() }
    fun save() {
        val track = engine.state.track
        if (track.points.isEmpty() || !writable) return
        history = (listOf(track) + history.filter { it.id != track.id }).sortedByDescending { it.started }
        val snapshot = history
        publish()
        io.execute {
            var stream: java.io.FileOutputStream? = null
            try {
                stream = file.startWrite()
                stream.write(TrackCodec.encode(snapshot).toByteArray(Charsets.UTF_8))
                file.finishWrite(stream)
            } catch (error: Exception) {
                file.failWrite(stream)
                main.post { message("기록 저장 실패: ${error.message}") }
            }
        }
    }
}
