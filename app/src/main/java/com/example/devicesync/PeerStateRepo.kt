package com.example.devicesync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

data class PeerSnapshot(
    val deviceId: String,
    val deviceType: String,
    val battery: Int,
    val charging: Boolean,
    val network: String,
    val storageFreeMb: Int,
    val latencyMs: Long,
    val ts: Long
)

object PeerStateRepo {
    private val _self = MutableStateFlow<PeerSnapshot?>(null)
    val self: StateFlow<PeerSnapshot?> = _self.asStateFlow()

    private val _peers = MutableStateFlow<Map<String, PeerSnapshot>>(emptyMap())
    val peers: StateFlow<Map<String, PeerSnapshot>> = _peers.asStateFlow()

    private val _notifications = MutableStateFlow<List<String>>(emptyList())
    val notifications: StateFlow<List<String>> = _notifications.asStateFlow()

    fun updateSelf(json: String) {
        parse(json)?.let { _self.value = it }
    }

    fun updatePeer(json: String) {
        val p = parse(json) ?: return
        _peers.value = _peers.value + (p.deviceId to p)
    }

    fun removePeer(id: String) {
        _peers.value = _peers.value - id
    }

    fun pushNotification(text: String) {
        _notifications.value = (_notifications.value + text).takeLast(20)
    }

    private fun parse(json: String): PeerSnapshot? = runCatching {
        val o = JSONObject(json)
        PeerSnapshot(
            deviceId = o.getString("deviceId"),
            deviceType = o.optString("deviceType", "phone"),
            battery = o.optInt("battery", -1),
            charging = o.optBoolean("charging", false),
            network = o.optString("network", "--"),
            storageFreeMb = o.optInt("storageFreeMb", -1),
            latencyMs = o.optLong("latencyMs", 0),
            ts = o.optLong("ts", 0)
        )
    }.getOrNull()
}
