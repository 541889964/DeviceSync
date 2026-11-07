package com.example.devicesync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

class SyncNodeService : Service(), SensorEventListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null

    data class Peer(val socket: Socket, val deviceId: String, val type: String)
    private val peers = ConcurrentHashMap<String, Peer>()

    private val myId: String by lazy {
        Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            ?: "unknown-${System.currentTimeMillis()}"
    }
    private val myType: String by lazy {
        if (resources.configuration.smallestScreenWidthDp >= 600) "tablet" else "phone"
    }

    private var nsdManager: NsdManager? = null
    private var regListener: NsdManager.RegistrationListener? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val sensorManager by lazy { getSystemService(SensorManager::class.java) }
    private var lastShake = 0L

    private val batteryReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = broadcastSelf()
    }
    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = broadcastSelf()
        override fun onLost(network: Network) = broadcastSelf()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = broadcastSelf()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForegroundNotification()
        acquireLocks()
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(batteryReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(batteryReceiver, filter)
        }
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(netCallback)
        startServer()
        registerMdns()
        sensorManager.registerListener(this,
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
            SensorManager.SENSOR_DELAY_UI)
        KeepAliveGuide.enqueueWatchdog(this)
        broadcastSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (wakeLock?.isHeld != true || wifiLock?.isHeld != true) acquireLocks()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val pi = android.app.PendingIntent.getService(
            this, 1, Intent(this, SyncNodeService::class.java),
            android.app.PendingIntent.FLAG_ONE_SHOT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        getSystemService(android.app.AlarmManager::class.java).set(
            android.app.AlarmManager.ELAPSED_REALTIME,
            android.os.SystemClock.elapsedRealtime() + 1000, pi
        )
        super.onTaskRemoved(rootIntent)
    }

    private fun startForegroundNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("sync", "设备同步", NotificationManager.IMPORTANCE_MIN)
                .apply { setShowBadge(false) }
        )
        val n = NotificationCompat.Builder(this, "sync")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("设备同步运行中")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1001, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(1001, n)
        }
    }

    private fun acquireLocks() {
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "DeviceSync::Cpu"
        ).apply { setReferenceCounted(false); acquire() }
        wifiLock = applicationContext.getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DeviceSync::Wifi")
            .apply { setReferenceCounted(false); acquire() }
    }

    private fun releaseLocks() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
    }

    private fun startServer() {
        scope.launch {
            runCatching {
                val ss = ServerSocket(PORT).apply { reuseAddress = true }
                serverSocket = ss
                while (isActive) {
                    val s = ss.accept()
                    val reader = s.getInputStream().bufferedReader()
                    val hs = JSONObject(reader.readLine() ?: continue)
                    val id = hs.optString("deviceId")
                    if (id.isBlank() || id == myId) { s.close(); continue }
                    peers.remove(id)?.socket?.close()
                    peers[id] = Peer(s, id, hs.optString("deviceType", "phone"))
                    attachReader(id, s)
                    pushTo(id, selfJson())
                }
            } catch (_: Exception) { }
        }
    }

    private fun attachReader(id: String, socket: Socket) {
        scope.launch {
            try {
                val r = socket.getInputStream().bufferedReader()
                while (isActive) {
                    val line = r.readLine() ?: break
                    val o = runCatching { JSONObject(line) }.getOrNull() ?: continue
                    when (o.optString("type")) {
                        "snapshot" -> PeerStateRepo.updatePeer(line)
                        "notification" -> PeerStateRepo.pushNotification(
                            "${o.optString("app")}: ${o.optString("title")}"
                        )
                        "shake" -> broadcastSelf()
                    }
                }
            } finally {
                peers.remove(id)?.socket?.close()
                PeerStateRepo.removePeer(id)
            }
        }
    }

    fun connectTo(peerId: String, host: String) {
        scope.launch {
            runCatching {
                val s = Socket(host, PORT)
                val hs = JSONObject().apply {
                    put("deviceId", myId); put("deviceType", myType)
                }.toString()
                s.getOutputStream().write((hs + "\n").toByteArray())
                s.getOutputStream().flush()
                peers.remove(peerId)?.socket?.close()
                peers[peerId] = Peer(s, peerId, "unknown")
                attachReader(peerId, s)
                pushTo(peerId, selfJson())
            }
        }
    }

    fun broadcastSelf() {
        scope.launch {
            val json = selfJson()
            PeerStateRepo.updateSelf(json)
            peers.values.forEach { pushTo(it.deviceId, json) }
        }
    }

    fun broadcastRaw(json: String) {
        scope.launch {
            peers.values.forEach { p ->
                runCatching {
                    p.socket.getOutputStream().write((json + "\n").toByteArray())
                    p.socket.getOutputStream().flush()
                }.onFailure { peers.remove(p.deviceId) }
            }
        }
    }

    private fun pushTo(id: String, json: String) {
        val p = peers[id] ?: return
        runCatching {
            p.socket.getOutputStream().write((json + "\n").toByteArray())
            p.socket.getOutputStream().flush()
        }.onFailure {
            peers.remove(id)
            PeerStateRepo.removePeer(id)
        }
    }

    private fun selfJson(): String {
        val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val lvl = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (lvl >= 0) lvl * 100 / scale else -1
        val charging = b?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) in
                listOf(BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL)
        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val net = when {
            caps == null -> "offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            else -> "other"
        }
        val stat = StatFs(Environment.getDataDirectory().path)
        val freeMb = (stat.availableBytes / (1024 * 1024)).toInt()

        return JSONObject().apply {
            put("type", "snapshot")
            put("deviceId", myId)
            put("deviceType", myType)
            put("battery", pct)
            put("charging", charging)
            put("network", net)
            put("storageFreeMb", freeMb)
            put("latencyMs", 0)
            put("ts", System.currentTimeMillis())
        }.toString()
    }

    private fun registerMdns() {
        nsdManager = getSystemService(NsdManager::class.java)
        val info = NsdServiceInfo().apply {
            serviceName = "dsync-$myType-$myId"
            serviceType = "_dsync._tcp"
            port = PORT
        }
        regListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {}
            override fun onRegistrationFailed(i: NsdServiceInfo, c: Int) {}
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, c: Int) {}
        }
        runCatching { nsdManager?.registerService(info, NsdManager.PROTOCOL_DNS_SD, regListener) }
        discover()
    }

    private fun discover() {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, c: Int) {}
            override fun onStopDiscoveryFailed(t: String, c: Int) {}
            override fun onServiceLost(i: NsdServiceInfo) {}
            override fun onServiceFound(i: NsdServiceInfo) {
                val parts = i.serviceName.split("-")
                if (parts.size < 3 || parts[0] != "dsync") return
                val peerId = parts[2]
                if (peerId == myId) return
                if (myId > peerId) return
                runCatching {
                    nsdManager?.resolveService(i, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(s: NsdServiceInfo, e: Int) {}
                        override fun onServiceResolved(s: NsdServiceInfo) {
                            s.host?.hostAddress?.let { connectTo(peerId, it) }
                        }
                    })
                }
            }
        }
        runCatching {
            nsdManager?.discoverServices("_dsync._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val mag = sqrt(event.values[0] * event.values[0] +
                event.values[1] * event.values[1] +
                event.values[2] * event.values[2])
        if (mag > 2.5f * SensorManager.GRAVITY_EARTH) {
            val now = System.currentTimeMillis()
            if (now - lastShake > 800) {
                lastShake = now
                broadcastRaw(JSONObject().put("type", "shake").toString())
                broadcastSelf()
            }
        }
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    override fun onDestroy() {
        instance = null
        runCatching { unregisterReceiver(batteryReceiver) }
        runCatching {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(netCallback)
        }
        sensorManager.unregisterListener(this)
        regListener?.let { runCatching { nsdManager?.unregisterService(it) } }
        peers.values.forEach { runCatching { it.socket.close() } }
        peers.clear()
        runCatching { serverSocket?.close() }
        releaseLocks()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val PORT = 8899
        @Volatile var instance: SyncNodeService? = null
    }
}
