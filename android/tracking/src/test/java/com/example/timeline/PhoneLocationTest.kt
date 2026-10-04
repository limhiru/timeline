package com.example.timeline

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class PhoneLocationTest {
    private fun fix(sequence: Long = 1, age: Long = 100, accuracy: Float = 5f, session: String = "phone-boot") =
        WirePhoneFix(session, sequence, 37.0, 127.0, accuracy, age, MotionState.MOVING, 1.0)
    private fun request(session: PhoneLocationSession, time: Long) = PhoneLocationProtocol.readRequest(session.request(time)!!)!!
    private fun reply(token: String, fix: WirePhoneFix? = fix()) = PhoneLocationProtocol.reply(token, fix)
    private fun session() = PhoneLocationSession("watch-session").apply { selectNearbyPhone("phone") }

    @Test fun protocolRoundTripAndNullLocation() {
        assertEquals("watch-1", PhoneLocationProtocol.readRequest(PhoneLocationProtocol.request("watch-1")))
        assertEquals(PhoneLocationReply("watch-1", fix()), PhoneLocationProtocol.readReply(reply("watch-1")))
        assertEquals(PhoneLocationReply("watch-1", null), PhoneLocationProtocol.readReply(reply("watch-1", null)))
    }
    @Test fun malformedOrWrongVersionMessagesAreIgnored() {
        assertNull(PhoneLocationProtocol.readRequest("{}".toByteArray()))
        assertNull(PhoneLocationProtocol.readRequest("{\"v\":1}".toByteArray()))
        assertNull(PhoneLocationProtocol.readRequest(ByteArray(2049)))
        assertNull(PhoneLocationProtocol.readReply("broken".toByteArray()))
        assertNull(PhoneLocationProtocol.readReply("{\"v\":2,\"request\":\"x\",\"fix\":null}".toByteArray()))
        assertNull(PhoneLocationProtocol.readReply("{\"v\":1,\"request\":\"x\"}".toByteArray()))
        assertNull(PhoneLocationProtocol.readReply("{\"v\":1,\"request\":\"x\",\"fix\":42}".toByteArray()))
    }
    @Test fun invalidCoordinatesAndMotionAreIgnored() {
        val json = JSONObject(String(reply("watch-1")))
        json.getJSONObject("fix").put("latitude", 91)
        assertNull(PhoneLocationProtocol.readReply(json.toString().toByteArray()))
        json.getJSONObject("fix").put("latitude", 37).put("motion", "BAD")
        assertNull(PhoneLocationProtocol.readReply(json.toString().toByteArray()))
    }
    @Test fun unknownSpeedAndMotionAreNotInvented() {
        val original = fix().copy(speedLowerBound = null, motion = MotionState.UNKNOWN)
        assertEquals(original, PhoneLocationProtocol.readReply(reply("watch-1", original))!!.fix)
    }
    @Test fun onlySelectedNearbyPhoneAndMatchingRequestCanSupplyPosition() {
        val session = session(); val token = request(session, 10_000)
        assertNull(session.receive("other-phone", reply(token), 10_200, 60_000))
        assertNull(session.receive("phone", reply("wrong-token"), 10_200, 60_000))
        assertNotNull(session.receive("phone", reply(token), 10_200, 60_000))
    }
    @Test fun freshnessIncludesRoundTripWithoutComparingDeviceClocks() {
        val session = session(); val token = request(session, 10_000)
        val result = session.receive("phone", reply(token), 10_400, 60_000)!!
        assertEquals(9900L, result.sampleMillis) // 100 ms on phone + 400 ms transit upper bound
        assertEquals(59_500L, result.point.timestamp)
        assertEquals(LocationSource.PHONE, result.point.source)
        assertFalse(session.prefersPhone(10_400)) // reject is still possible in the engine
        session.accepted(result)
        assertTrue(session.prefersPhone(17_900))
        assertFalse(session.prefersPhone(17_901))
    }
    @Test fun lateRepliesAndOldPhoneSamplesCannotBecomeFresh() {
        val session = session()
        assertNull(session.receive("phone", reply(request(session, 10_000)), 14_001, 60_000))
        assertNull(session.receive("phone", reply(request(session, 15_000), fix(age = 7900)), 15_200, 60_000))
        assertFalse(session.prefersPhone(15_200))
    }
    @Test fun duplicatePhoneFixDoesNotExtendTheFreshnessDeadline() {
        val session = session()
        val first = session.receive("phone", reply(request(session, 10_000)), 10_200, 60_000)!!
        session.accepted(first)
        assertNull(session.receive("phone", reply(request(session, 12_000)), 12_200, 62_000))
        assertFalse(session.prefersPhone(18_001))
    }
    @Test fun disconnectionClearsPreferenceAndPendingRequests() {
        val session = session(); val token = request(session, 10_000)
        val result = session.receive("phone", reply(token), 10_200, 60_000)!!; session.accepted(result)
        val pending = request(session, 12_000)
        session.failedConnection()
        assertFalse(session.prefersPhone(12_100))
        assertNull(session.receive("phone", reply(pending, fix(sequence = 2)), 12_200, 62_000))
        assertNull(session.request(13_000))
    }
    @Test fun unavailableOrInaccuratePhoneImmediatelyAllowsLocalFallback() {
        val session = session()
        session.accepted(session.receive("phone", reply(request(session, 10_000)), 10_200, 60_000)!!)
        assertNull(session.receive("phone", reply(request(session, 12_000), null), 12_200, 62_000))
        assertFalse(session.prefersPhone(12_200))
        assertNull(session.receive("phone", reply(request(session, 14_000), fix(sequence = 2, accuracy = 25f)), 14_200, 64_000))
        assertFalse(session.prefersPhone(14_200))
    }
    @Test fun outOfOrderRepliesCannotUndoANewerSample() {
        val session = session(); val first = request(session, 10_000); val second = request(session, 12_000)
        session.accepted(session.receive("phone", reply(second, fix(sequence = 2)), 12_200, 62_000)!!)
        assertNull(session.receive("phone", reply(first, null), 12_300, 62_100))
        assertTrue(session.prefersPhone(12_300))
    }
    @Test fun restartedPhoneCannotReplayEarlierBootSamples() {
        val session = session()
        session.accepted(session.receive("phone", reply(request(session, 10_000)), 10_200, 60_000)!!)
        session.accepted(session.receive("phone", reply(request(session, 12_000), fix(session = "new-boot")), 12_200, 62_000)!!)
        assertNull(session.receive("phone", reply(request(session, 14_000), fix(sequence = 10)), 14_200, 64_000))
        assertTrue(session.prefersPhone(14_200))
    }
    @Test fun reconnectionAcceptsANewFreshPhoneSample() {
        val session = session()
        session.accepted(session.receive("phone", reply(request(session, 10_000)), 10_200, 60_000)!!)
        session.failedConnection(); session.selectNearbyPhone("phone")
        session.accepted(session.receive("phone", reply(request(session, 12_000), fix(sequence = 2)), 12_200, 62_000)!!)
        assertTrue(session.prefersPhone(12_200))
    }
    @Test fun locationSharingLeaseRequiresUserEnableAndExpiresWithoutRequests() {
        val lease = LocationSharingLease(); lease.request(0)
        assertFalse(lease.needsGps(0))
        lease.enable(1000)
        assertTrue(lease.needsGps(16_000)); assertFalse(lease.needsGps(16_001))
        lease.request(20_000)
        assertTrue(lease.needsGps(20_000))
        lease.disable(); lease.request(21_000)
        assertFalse(lease.needsGps(21_000))
    }
    private fun point(lon: Double, time: Long, source: LocationSource = LocationSource.DEVICE, accuracy: Float = 5f) =
        TrackPoint(37.0, lon, time, accuracy, source = source)
    @Test fun switchingSourcesDoesNotTurnPositionBiasIntoDistance() {
        val engine = RouteEngine(); engine.start(100_000, 0)
        engine.accept(point(127.0, 100_000), 100_000, 0)
        engine.accept(point(127.001, 102_000, LocationSource.PHONE), 102_000, 2000)
        engine.accept(point(127.00106, 104_000, LocationSource.PHONE), 104_000, 4000)
        engine.accept(point(127.002, 106_000), 106_000, 6000)
        assertEquals(listOf(0, 1, 1, 2), engine.state.track.points.map { it.segment })
        assertTrue(engine.state.track.distance in 3.0..5.5)
        assertEquals(LocationSource.DEVICE, engine.state.locationSource)
    }
    @Test fun poorSourceChangeDoesNotClearCurrentPosition() {
        val engine = RouteEngine(); engine.start(100_000, 0)
        engine.accept(point(127.0, 100_000), 100_000, 0)
        val before = engine.state.position
        assertFalse(engine.accept(point(127.01, 102_000, LocationSource.PHONE, 25f), 102_000, 2000))
        assertEquals(before, engine.state.position)
        assertEquals(LocationSource.DEVICE, engine.state.locationSource)
    }
    @Test fun phoneSourceRoundTripAndOlderSavedTracksRemainReadable() {
        val track = Track(points = listOf(point(127.0, 100_000, LocationSource.PHONE)))
        assertEquals(listOf(track), TrackCodec.decode(TrackCodec.encode(listOf(track))))
        val legacy = TrackCodec.encode(listOf(track)).replace(",\"source\":\"PHONE\"", "").replace("\"source\":\"PHONE\",", "")
        assertEquals(LocationSource.DEVICE, TrackCodec.decode(legacy).first().points.first().source)
    }
}
