package com.example.timeline

import android.content.Context
import android.os.SystemClock
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.wearable.*

/** Active-session listener. No background service is launched on the phone by the watch. */
class WatchPhoneLocationClient(private val context: Context,
    private val onFix: (RemoteLocationFix) -> Boolean, private val onChanged: () -> Unit
) : MessageClient.OnMessageReceivedListener, CapabilityClient.OnCapabilityChangedListener {
    private val session = PhoneLocationSession()
    private var messages: MessageClient? = null
    private var capabilities: CapabilityClient? = null
    private var generation = 0
    private var running = false
    private var ready = false
    private var querying = false
    private var lastApiCheck = Long.MIN_VALUE
    private var lastQuery = Long.MIN_VALUE
    private var lastRequest = Long.MIN_VALUE
    val phoneConnected get() = session.node != null
    fun prefersPhone(now: Long) = running && session.prefersPhone(now)
    fun start() {
        if (running) return
        running = true; generation++; lastQuery = Long.MIN_VALUE; lastRequest = Long.MIN_VALUE
        try {
            messages = Wearable.getMessageClient(context)
            capabilities = Wearable.getCapabilityClient(context)
            checkApi()
        } catch (_: Exception) { session.failedConnection(); onChanged() }
    }
    private fun checkApi() {
        val client = messages ?: return
        val current = generation
        lastApiCheck = SystemClock.elapsedRealtime()
        GoogleApiAvailability.getInstance().checkApiAvailability(client)
            .addOnSuccessListener {
                if (!running || current != generation) return@addOnSuccessListener
                ready = true
                client.addListener(this).addOnFailureListener { if (current == generation) unavailable() }
                capabilities?.addListener(this, PhoneLocationProtocol.CAPABILITY)
                queryNearby()
            }.addOnFailureListener { if (running && current == generation) unavailable() }
    }
    fun tick(now: Long) {
        if (!running) return
        if (!ready) {
            if (now - lastApiCheck >= 30_000) checkApi()
            return
        }
        if (lastQuery == Long.MIN_VALUE || now - lastQuery >= 5000) queryNearby()
        if (lastRequest == Long.MIN_VALUE || now - lastRequest >= 2000) {
            lastRequest = now
            val node = session.node ?: return
            val request = session.request(now) ?: return
            val current = generation
            messages?.sendMessage(node, PhoneLocationProtocol.REQUEST_PATH, request)?.addOnFailureListener {
                if (running && current == generation && node == session.node) unavailable()
            }
        }
    }
    private fun queryNearby() {
        if (!running || !ready || querying) return
        querying = true; lastQuery = SystemClock.elapsedRealtime()
        val current = generation
        capabilities?.getCapability(PhoneLocationProtocol.CAPABILITY, CapabilityClient.FILTER_REACHABLE)
            ?.addOnSuccessListener { info ->
                if (!running || current != generation) return@addOnSuccessListener
                querying = false
                // Cloud-reachable phones may be elsewhere. Never treat their location as ours.
                val nearby = info.nodes.filter { it.isNearby }.map { it.id }.sorted()
                session.selectNearbyPhone(session.node?.takeIf { it in nearby } ?: nearby.firstOrNull())
                onChanged()
            }?.addOnFailureListener {
                if (running && current == generation) { querying = false; unavailable() }
            }
    }
    private fun unavailable() { session.failedConnection(); onChanged() }
    override fun onCapabilityChanged(info: CapabilityInfo) { queryNearby() }
    override fun onMessageReceived(event: MessageEvent) {
        if (!running || event.path != PhoneLocationProtocol.REPLY_PATH) return
        val fix = session.receive(event.sourceNodeId, event.data, SystemClock.elapsedRealtime(), System.currentTimeMillis())
        if (fix != null && onFix(fix)) session.accepted(fix)
        onChanged()
    }
    fun stop() {
        if (!running) return
        running = false; generation++; ready = false; querying = false
        messages?.removeListener(this); capabilities?.removeListener(this, PhoneLocationProtocol.CAPABILITY)
        session.failedConnection()
    }
}
