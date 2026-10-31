package com.omniverse.superapp

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySession
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareNetworkInfo
import android.net.wifi.aware.WifiAwareNetworkSpecifier
import android.net.wifi.aware.WifiAwareSession
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject
import java.util.UUID

/**
 * Native radio discovery for The Omniverse.
 *
 * - BLE: advertises service UUID 0xFFA0 with the first 16 bytes of the node hash as
 *   service data, and scans for the same UUID. Identity/proximity only (no host).
 * - Wi-Fi Aware (NAN): publishes and subscribes service "omniverse" with
 *   serviceSpecificInfo = {"h": nodeHash, "p": port}. On API 29+ a NAN data path is
 *   requested (PSK) so the peer's IPv6 address can be reported as host.
 *
 * MethodChannel "omni/radio": start(nodeHashHex, port), stop(), status().
 * EventChannel "omni/radio/peers": maps {transport, nodeHash, host?, port?, rssi?}.
 *
 * Runtime permissions are NOT requested here; missing ones are reported via status().
 */
@SuppressLint("MissingPermission")
class OmniRadioDiscovery(private val context: Context, messenger: BinaryMessenger) :
    MethodChannel.MethodCallHandler, EventChannel.StreamHandler {

    companion object {
        private val SERVICE_UUID: ParcelUuid =
            ParcelUuid(UUID.fromString("0000FFA0-0000-1000-8000-00805F9B34FB"))
        private const val AWARE_SERVICE = "omniverse"
        private const val AWARE_PSK = "omniverse-mesh-dev"
        private const val MAX_ERRORS = 20
    }

    private val main = Handler(Looper.getMainLooper())
    private val methodChannel = MethodChannel(messenger, "omni/radio")
    private val eventChannel = EventChannel(messenger, "omni/radio/peers")
    private var sink: EventChannel.EventSink? = null

    private var running = false
    private var nodeHash: String = ""
    private var port: Int = 0
    private val errors = ArrayList<String>()

    // BLE state
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var bleAdvertising = false
    private var bleScanning = false

    // Wi-Fi Aware state
    private var awareSession: WifiAwareSession? = null
    private var publishSession: PublishDiscoverySession? = null
    private var subscribeSession: SubscribeDiscoverySession? = null
    private var awareState: String = "idle"
    private val networkCallbacks = ArrayList<ConnectivityManager.NetworkCallback>()
    private val requestedPeers = HashSet<PeerHandle>()
    private val peerHashes = HashMap<PeerHandle, Pair<String, Int?>>()
    private var messageId = 1

    init {
        methodChannel.setMethodCallHandler(this)
        eventChannel.setStreamHandler(this)
    }

    // ---- Flutter plumbing ----

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        try {
            when (call.method) {
                "start" -> {
                    val hash = call.argument<String>("nodeHashHex") ?: ""
                    val p = call.argument<Int>("port") ?: 5005
                    start(hash, p)
                    result.success(status())
                }
                "stop" -> {
                    stop()
                    result.success(status())
                }
                "status" -> result.success(status())
                else -> result.notImplemented()
            }
        } catch (t: Throwable) {
            recordError("${call.method}: ${t.message}")
            result.success(status())
        }
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        sink = events
    }

    override fun onCancel(arguments: Any?) {
        sink = null
    }

    private fun emit(peer: Map<String, Any?>) {
        main.post {
            try {
                sink?.success(peer.filterValues { it != null })
            } catch (t: Throwable) {
                recordError("emit: ${t.message}")
            }
        }
    }

    private fun recordError(msg: String) {
        synchronized(errors) {
            errors.add(msg)
            while (errors.size > MAX_ERRORS) errors.removeAt(0)
        }
    }

    // ---- Permissions ----

    private fun has(perm: String): Boolean =
        context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    private fun missingBlePermissions(): List<String> {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                "android.permission.BLUETOOTH_SCAN",
                "android.permission.BLUETOOTH_ADVERTISE",
                "android.permission.BLUETOOTH_CONNECT"
            )
        } else {
            listOf("android.permission.ACCESS_FINE_LOCATION")
        }
        return needed.filter { !has(it) }
    }

    private fun missingAwarePermissions(): List<String> {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf("android.permission.NEARBY_WIFI_DEVICES")
        } else {
            listOf("android.permission.ACCESS_FINE_LOCATION")
        }
        return (needed + listOf(
            "android.permission.ACCESS_WIFI_STATE",
            "android.permission.CHANGE_WIFI_STATE"
        )).filter { !has(it) }
    }

    private fun awareSupported(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)

    fun status(): Map<String, Any?> {
        val awareAvailable = try {
            awareSupported() &&
                (context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager)?.isAvailable == true
        } catch (t: Throwable) {
            false
        }
        val errs = synchronized(errors) { ArrayList(errors) }
        return mapOf(
            "running" to running,
            "nodeHash" to nodeHash,
            "port" to port,
            "sdkInt" to Build.VERSION.SDK_INT,
            "bleAdvertising" to bleAdvertising,
            "bleScanning" to bleScanning,
            "wifiAwareSupported" to awareSupported(),
            "wifiAwareAvailable" to awareAvailable,
            "wifiAwareState" to awareState,
            "wifiAwareDataPath" to (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q),
            "missingPermissions" to (missingBlePermissions() + missingAwarePermissions()).distinct(),
            "errors" to errs
        )
    }

    // ---- Lifecycle ----

    /** Runtime ("dangerous") permissions still missing, for the activity to request. */
    fun missingRuntimePermissions(): List<String> =
        (missingBlePermissions() + missingAwarePermissions())
            .filter { it != "android.permission.ACCESS_WIFI_STATE" && it != "android.permission.CHANGE_WIFI_STATE" }
            .distinct()

    /** Re-run start() with the same identity (e.g. after the user grants permissions). */
    fun restart() {
        if (running && nodeHash.isNotEmpty()) start(nodeHash, port)
    }

    fun start(hash: String, p: Int) {
        if (running) stop()
        nodeHash = hash.lowercase()
        port = p
        running = true
        synchronized(errors) { errors.clear() }
        try {
            startBle()
        } catch (t: Throwable) {
            recordError("ble: ${t.message}")
        }
        try {
            startAware()
        } catch (t: Throwable) {
            recordError("wifi_aware: ${t.message}")
        }
    }

    fun stop() {
        running = false
        try {
            stopBle()
        } catch (t: Throwable) {
            recordError("ble stop: ${t.message}")
        }
        try {
            stopAware()
        } catch (t: Throwable) {
            recordError("wifi_aware stop: ${t.message}")
        }
    }

    // ---- BLE ----

    private fun hashPrefixBytes(): ByteArray {
        val clean = nodeHash.filter { it.isDigit() || it in 'a'..'f' }
        val out = ByteArray(16)
        var i = 0
        while (i < 16 && i * 2 + 1 < clean.length) {
            out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            i++
        }
        return out
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            bleAdvertising = true
        }

        override fun onStartFailure(errorCode: Int) {
            bleAdvertising = false
            recordError("ble advertise failed: $errorCode")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            handleScan(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { handleScan(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            bleScanning = false
            recordError("ble scan failed: $errorCode")
        }
    }

    private fun handleScan(result: ScanResult?) {
        try {
            val r = result ?: return
            val data = r.scanRecord?.getServiceData(SERVICE_UUID) ?: return
            val hex = data.joinToString("") { "%02x".format(it.toInt() and 0xff) }
            emit(mapOf("transport" to "ble", "nodeHash" to hex, "rssi" to r.rssi))
        } catch (t: Throwable) {
            recordError("ble scan result: ${t.message}")
        }
    }

    private fun startBle() {
        val missing = missingBlePermissions()
        if (missing.isNotEmpty()) {
            recordError("ble: missing permissions $missing")
            return
        }
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            recordError("ble: bluetooth unavailable or off")
            return
        }
        advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            recordError("ble: advertising not supported")
        } else {
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(false)
                .build()
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addServiceUuid(SERVICE_UUID)
                .addServiceData(SERVICE_UUID, hashPrefixBytes())
                .build()
            advertiser?.startAdvertising(settings, data, advertiseCallback)
        }
        scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            recordError("ble: scanner unavailable")
        } else {
            val filters = listOf(ScanFilter.Builder().setServiceUuid(SERVICE_UUID).build())
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                .build()
            scanner?.startScan(filters, settings, scanCallback)
            bleScanning = true
        }
    }

    private fun stopBle() {
        if (bleAdvertising || advertiser != null) {
            try { advertiser?.stopAdvertising(advertiseCallback) } catch (e: Throwable) { recordError("cleanup: ${e.message}") }
        }
        if (bleScanning || scanner != null) {
            try { scanner?.stopScan(scanCallback) } catch (e: Throwable) { recordError("cleanup: ${e.message}") }
        }
        advertiser = null
        scanner = null
        bleAdvertising = false
        bleScanning = false
    }

    // ---- Wi-Fi Aware ----

    private fun localInfo(): ByteArray =
        JSONObject().put("h", nodeHash).put("p", port).toString().toByteArray(Charsets.UTF_8)

    private fun parseInfo(bytes: ByteArray?): Pair<String, Int?>? {
        if (bytes == null || bytes.isEmpty()) return null
        return try {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            val h = o.optString("h", "")
            if (h.isEmpty()) null else Pair(h, if (o.has("p")) o.optInt("p") else null)
        } catch (t: Throwable) {
            null
        }
    }

    private fun startAware() {
        if (!awareSupported()) {
            awareState = "unsupported"
            return
        }
        val missing = missingAwarePermissions()
        if (missing.isNotEmpty()) {
            awareState = "missing_permissions"
            recordError("wifi_aware: missing permissions $missing")
            return
        }
        val mgr = context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
        if (mgr == null || !mgr.isAvailable) {
            awareState = "unavailable"
            recordError("wifi_aware: not available (Wi-Fi off or busy?)")
            return
        }
        awareState = "attaching"
        mgr.attach(object : AttachCallback() {
            override fun onAttached(session: WifiAwareSession) {
                if (!running) {
                    session.close()
                    return
                }
                awareSession = session
                awareState = "attached"
                try {
                    publish(session)
                    subscribe(session)
                } catch (t: Throwable) {
                    recordError("wifi_aware session: ${t.message}")
                }
            }

            override fun onAttachFailed() {
                awareState = "attach_failed"
                recordError("wifi_aware: attach failed")
            }
        }, main)
    }

    private fun publish(session: WifiAwareSession) {
        val config = PublishConfig.Builder()
            .setServiceName(AWARE_SERVICE)
            .setServiceSpecificInfo(localInfo())
            .build()
        session.publish(config, object : DiscoverySessionCallback() {
            override fun onPublishStarted(s: PublishDiscoverySession) {
                publishSession = s
                awareState = "active"
            }

            // Subscribers message us after discovery; that gives us their PeerHandle,
            // which the publisher side needs to accept a data path.
            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                val info = parseInfo(message)
                if (info != null) {
                    peerHashes[peerHandle] = info
                    emit(mapOf("transport" to "wifi_aware", "nodeHash" to info.first, "port" to info.second))
                }
                publishSession?.let { requestDataPath(it, peerHandle) }
            }

            override fun onSessionTerminated() {
                publishSession = null
            }
        }, main)
    }

    private fun subscribe(session: WifiAwareSession) {
        val config = SubscribeConfig.Builder()
            .setServiceName(AWARE_SERVICE)
            .build()
        session.subscribe(config, object : DiscoverySessionCallback() {
            override fun onSubscribeStarted(s: SubscribeDiscoverySession) {
                subscribeSession = s
                awareState = "active"
            }

            override fun onServiceDiscovered(
                peerHandle: PeerHandle,
                serviceSpecificInfo: ByteArray?,
                matchFilter: MutableList<ByteArray>?
            ) {
                val info = parseInfo(serviceSpecificInfo) ?: return
                if (info.first == nodeHash) return
                peerHashes[peerHandle] = info
                emit(mapOf("transport" to "wifi_aware", "nodeHash" to info.first, "port" to info.second))
                val s = subscribeSession ?: return
                try {
                    // Tell the publisher who we are so it can accept the data path.
                    s.sendMessage(peerHandle, messageId++, localInfo())
                } catch (t: Throwable) {
                    recordError("wifi_aware sendMessage: ${t.message}")
                }
                requestDataPath(s, peerHandle)
            }

            override fun onSessionTerminated() {
                subscribeSession = null
            }
        }, main)
    }

    private fun requestDataPath(session: DiscoverySession, peerHandle: PeerHandle) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return // no host below API 29
        if (!requestedPeers.add(peerHandle)) return
        try {
            requestDataPathQ(session, peerHandle)
        } catch (t: Throwable) {
            requestedPeers.remove(peerHandle)
            recordError("wifi_aware data path: ${t.message}")
        }
    }

    @SuppressLint("NewApi")
    private fun requestDataPathQ(session: DiscoverySession, peerHandle: PeerHandle) {
        val specifier = WifiAwareNetworkSpecifier.Builder(session, peerHandle)
            .setPskPassphrase(AWARE_PSK)
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(specifier)
            .build()
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                try {
                    val info = caps.transportInfo as? WifiAwareNetworkInfo ?: return
                    val addr = info.peerIpv6Addr ?: return
                    val known = peerHashes[peerHandle] ?: return
                    val peerPort = if (info.port > 0) info.port else known.second
                    emit(
                        mapOf(
                            "transport" to "wifi_aware",
                            "nodeHash" to known.first,
                            "host" to addr.hostAddress,
                            "port" to peerPort
                        )
                    )
                } catch (t: Throwable) {
                    recordError("wifi_aware caps: ${t.message}")
                }
            }

            override fun onUnavailable() {
                requestedPeers.remove(peerHandle)
                recordError("wifi_aware data path unavailable")
            }

            override fun onLost(network: Network) {
                requestedPeers.remove(peerHandle)
            }
        }
        networkCallbacks.add(cb)
        cm.requestNetwork(request, cb)
    }

    private fun stopAware() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        networkCallbacks.forEach { cb ->
            try { cm?.unregisterNetworkCallback(cb) } catch (e: Throwable) { recordError("cleanup: ${e.message}") }
        }
        networkCallbacks.clear()
        requestedPeers.clear()
        peerHashes.clear()
        try { publishSession?.close() } catch (e: Throwable) { recordError("cleanup: ${e.message}") }
        try { subscribeSession?.close() } catch (e: Throwable) { recordError("cleanup: ${e.message}") }
        try { awareSession?.close() } catch (e: Throwable) { recordError("cleanup: ${e.message}") }
        publishSession = null
        subscribeSession = null
        awareSession = null
        awareState = "idle"
    }
}
