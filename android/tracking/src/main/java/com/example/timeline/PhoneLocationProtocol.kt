package com.example.timeline

import org.json.JSONObject
import java.util.UUID

/** Versioned request/reply messages, not persisted DataItems (which can replay old locations). */
object PhoneLocationProtocol {
    const val CAPABILITY = "timeline_phone_location_v1"
    const val REQUEST_PATH = "/timeline/location/request"
    const val REPLY_PATH = "/timeline/location/reply"
    private const val MAX_BYTES = 2048
    private fun id(value: String) = value.matches(Regex("[A-Za-z0-9_-]{1,96}"))
    fun request(token: String): ByteArray {
        require(id(token))
        return JSONObject().put("v", 1).put("request", token).toString().toByteArray(Charsets.UTF_8)
    }
    fun readRequest(bytes: ByteArray): String? = try { parse(bytes)?.getString("request")?.takeIf(::id) }
        catch (_: Exception) { null }
    fun reply(token: String, fix: WirePhoneFix?): ByteArray {
        require(id(token))
        val json = JSONObject().put("v", 1).put("request", token)
        json.put("fix", fix?.let {
            require(valid(it))
            JSONObject().put("session", it.session).put("sequence", it.sequence)
                .put("latitude", it.latitude).put("longitude", it.longitude).put("accuracy", it.accuracy.toDouble())
                .put("ageMillis", it.ageMillis).put("motion", it.motion.name)
                .put("speedLowerBound", it.speedLowerBound ?: JSONObject.NULL)
        } ?: JSONObject.NULL)
        return json.toString().toByteArray(Charsets.UTF_8)
    }
    fun readReply(bytes: ByteArray): PhoneLocationReply? { return try {
        val json = parse(bytes) ?: return null
        val token = json.getString("request").takeIf(::id) ?: return null
        if (!json.has("fix")) return null
        val obj = json.optJSONObject("fix")
        if (obj == null && !json.isNull("fix")) return null
        val fix = obj?.let {
            WirePhoneFix(it.getString("session"), it.getLong("sequence"), it.getDouble("latitude"),
                it.getDouble("longitude"), it.getDouble("accuracy").toFloat(), it.getLong("ageMillis"),
                MotionState.valueOf(it.getString("motion")),
                if (it.isNull("speedLowerBound")) null else it.getDouble("speedLowerBound"))
        }
        if (fix != null && !valid(fix)) null else PhoneLocationReply(token, fix)
    } catch (_: Exception) { null } }
    private fun parse(bytes: ByteArray): JSONObject? = try {
        if (bytes.size !in 1..MAX_BYTES) null
        else JSONObject(bytes.toString(Charsets.UTF_8)).takeIf { it.getInt("v") == 1 }
    } catch (_: Exception) { null }
    private fun valid(fix: WirePhoneFix) = id(fix.session) && fix.sequence > 0 &&
        fix.latitude.isFinite() && fix.latitude in -90.0..90.0 &&
        fix.longitude.isFinite() && fix.longitude in -180.0..180.0 &&
        fix.accuracy.isFinite() && fix.accuracy in 0f..10_000f && fix.ageMillis in 0..20_000 &&
        (fix.speedLowerBound == null || (fix.speedLowerBound.isFinite() && fix.speedLowerBound in 0.0..100.0))
}

data class WirePhoneFix(val session: String, val sequence: Long, val latitude: Double, val longitude: Double,
                        val accuracy: Float, val ageMillis: Long, val motion: MotionState,
                        val speedLowerBound: Double?)
data class PhoneLocationReply(val request: String, val fix: WirePhoneFix?)
data class RemoteLocationFix(val node: String, val point: TrackPoint, val sampleMillis: Long,
                             val motion: MotionState, val speedLowerBound: Double?)

/** All clocks in this class are the watch's monotonic clock. Phone wall time is never trusted. */
class PhoneLocationSession(private val prefix: String = UUID.randomUUID().toString()) {
    private data class Pending(val ordinal: Long, val sent: Long, val node: String)
    private val pending = linkedMapOf<String, Pending>()
    var node: String? = null
        private set
    private var counter = 0L
    private var lastResponse = 0L
    private var phoneSession: String? = null
    private var lastSequence = 0L
    private val retiredSessions = mutableSetOf<String>()
    private var acceptedSample: Long? = null
    fun selectNearbyPhone(id: String?) {
        if (id == node) return
        node = id; pending.clear(); acceptedSample = null; phoneSession = null
        lastSequence = 0; lastResponse = 0; retiredSessions.clear()
    }
    fun request(now: Long): ByteArray? {
        val phone = node ?: return null
        pending.entries.removeAll { now - it.value.sent !in 0..MAX_RTT }
        while (pending.size >= 3) pending.remove(pending.keys.first())
        val token = "$prefix-${++counter}"
        pending[token] = Pending(counter, now, phone)
        return PhoneLocationProtocol.request(token)
    }
    fun receive(from: String, bytes: ByteArray, now: Long, wallTime: Long): RemoteLocationFix? {
        if (from != node) return null
        val reply = PhoneLocationProtocol.readReply(bytes) ?: return null
        val request = pending.remove(reply.request) ?: return null
        val rtt = now - request.sent
        if (request.node != from || rtt !in 0..MAX_RTT || request.ordinal <= lastResponse) return null
        lastResponse = request.ordinal
        val fix = reply.fix
        if (fix == null || fix.accuracy > 20f || fix.ageMillis + rtt > MAX_AGE) {
            acceptedSample = null; return null
        }
        if (fix.session in retiredSessions) return null
        if (fix.session != phoneSession) {
            phoneSession?.let { retiredSessions.add(it) }
            // Bound memory; old replies are already bounded by the request window.
            if (retiredSessions.size > 8) retiredSessions.remove(retiredSessions.first())
            phoneSession = fix.session; lastSequence = 0
        }
        if (fix.sequence <= lastSequence) return null
        lastSequence = fix.sequence
        val age = fix.ageMillis + rtt // conservative upper bound, includes transport delay
        val sample = now - age
        if (sample < 0 || acceptedSample?.let { sample <= it } == true) return null
        return RemoteLocationFix(from, TrackPoint(fix.latitude, fix.longitude, wallTime - age,
            fix.accuracy, source = LocationSource.PHONE), sample, fix.motion, fix.speedLowerBound)
    }
    fun accepted(fix: RemoteLocationFix) { if (fix.node == node) acceptedSample = fix.sampleMillis }
    fun prefersPhone(now: Long) = node != null && acceptedSample?.let { now - it in 0..MAX_AGE } == true
    fun failedConnection() { selectNearbyPhone(null) }
    companion object { const val MAX_AGE = 8000L; const val MAX_RTT = 4000L }
}

/** Explicit user-enabled sharing may keep its FGS alive, but GPS only runs on a short lease. */
class LocationSharingLease {
    @Volatile var enabled = false
        private set
    private var requestTime: Long? = null
    @Synchronized fun enable(now: Long) { enabled = true; requestTime = now }
    @Synchronized fun disable() { enabled = false; requestTime = null }
    @Synchronized fun request(now: Long) { if (enabled) requestTime = now }
    @Synchronized fun needsGps(now: Long) = enabled && requestTime?.let { now - it in 0..15_000 } == true
}
