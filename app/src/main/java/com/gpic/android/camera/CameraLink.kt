package com.gpic.android.camera

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import com.gpic.android.camera.osmosis.ble.BleAdvert
import com.gpic.android.camera.osmosis.ble.BleConstants
import com.gpic.android.camera.osmosis.ble.Brand
import com.gpic.android.camera.osmosis.ble.CameraModel
import com.gpic.android.camera.osmosis.ble.GattClient
import com.gpic.android.camera.osmosis.camera.CameraSession
import com.gpic.android.camera.osmosis.camera.PathAddressing
import com.gpic.android.camera.osmosis.core.CameraFile
import com.gpic.android.camera.osmosis.core.MediaSession
import com.gpic.android.camera.osmosis.core.StorageRules
import com.gpic.android.camera.osmosis.duml.DjiMessage
import com.gpic.android.camera.osmosis.duml.OsmoCommands
import com.gpic.android.camera.osmosis.duml.DjiPairMessagePayload
import com.gpic.android.camera.osmosis.net.CameraNet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID
import java.util.concurrent.TimeUnit

/** A DJI camera seen in a BLE scan. */
data class FoundCamera(val address: String, val name: String, val model: CameraModel, val modelId: Int?, val rssi: Int)

sealed interface CameraState {
    data object Idle : CameraState
    data class Scanning(val found: List<FoundCamera>, val active: Boolean) : CameraState
    /** [step] is user-facing; [progress] 0..1 through the connect sequence. */
    data class Connecting(val camera: FoundCamera, val step: String, val progress: Float) : CameraState
    data class AwaitingApproval(val camera: FoundCamera) : CameraState
    data class Ready(val camera: FoundCamera, val files: List<CameraFile>, val hasInternet: Boolean) : CameraState
    data class Failed(val message: String, val camera: FoundCamera? = null) : CameraState
}

/**
 * GPic's driver for a DJI Osmo camera over BLE + WiFi, built on the vendored osmosis protocol code
 * (see camera/osmosis/NOTICE.md). Mirrors osmosis's hardware-verified connect sequence:
 *
 *   GATT ready → 0x00/0x2b wake → SetPairingPIN → (approve on camera) → 0x53/0x10 wake →
 *   GetWifiSsid/GetWifiPassword → join the camera AP → UDP datalink: register, playback, list.
 *
 * Unlike osmosis it never calls bindProcessToNetwork: only camera sockets are bound (CameraNet), so
 * Google uploads keep using the phone's default network while the camera is connected.
 */
@SuppressLint("MissingPermission")
@RequiresApi(Build.VERSION_CODES.Q)
class CameraLink(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val cm = app.getSystemService(ConnectivityManager::class.java)
    private val prefs = app.getSharedPreferences("gpic_camera", Context.MODE_PRIVATE)
    /** Camera WiFi passphrases (per MAC): encrypted like the Google credential. */
    private val secrets by lazy {
        runCatching {
            androidx.security.crypto.EncryptedSharedPreferences.create(
                app, "gpic_camera_secure",
                androidx.security.crypto.MasterKey.Builder(app).setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM).build(),
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrElse { prefs }
    }

    private val _state = MutableStateFlow<CameraState>(CameraState.Idle)
    val state: StateFlow<CameraState> = _state.asStateFlow()

    /** Connect/protocol trace; `adb logcat -s GPicCamera`. */
    private fun log(s: String) { Log.i(TAG, s) }

    // ---- scan --------------------------------------------------------------------------------------

    private val adapter get() = app.getSystemService(BluetoothManager::class.java)?.adapter
    private val found = LinkedHashMap<String, FoundCamera>()
    private val devices = HashMap<String, BluetoothDevice>()

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device
            val rec = result.scanRecord
            val name = rec?.deviceName ?: runCatching { dev.name }.getOrNull()
            var modelId: Int? = null
            var djiCid = false
            rec?.manufacturerSpecificData?.let { msd ->
                for (i in 0 until msd.size()) {
                    if (!BleConstants.isDjiCompanyId(msd.keyAt(i))) continue
                    djiCid = true
                    modelId = BleAdvert.decode(msd.valueAt(i) ?: continue).modelId
                }
            }
            val brand = Brand.of(dev.address, name, djiCid)
            // Cameras only: the whole room is visible to an unfiltered scan, and none of it is ours.
            if (brand == Brand.UNKNOWN && modelId == null) return
            val model = CameraModel.resolve(modelId, name, brand)
            if (model.isDrone) return   // drone offload is out of scope for GPic
            val cam = FoundCamera(dev.address, name ?: model.name, model, modelId, result.rssi)
            devices[dev.address] = dev
            if (found.put(dev.address, cam) == null) log("scan: found ${cam.name} (${model.name}, id=$modelId)")
            (_state.value as? CameraState.Scanning)?.let { _state.value = it.copy(found = found.values.toList()) }
        }

        override fun onScanFailed(errorCode: Int) {
            _state.value = CameraState.Failed("Bluetooth scan failed (code $errorCode). Toggle Bluetooth and retry.")
        }
    }

    fun startScan() {
        val a = adapter
        if (a == null || !a.isEnabled) {
            _state.value = CameraState.Failed("Turn on Bluetooth to find the camera.")
            return
        }
        disconnect()
        found.clear()
        _state.value = CameraState.Scanning(emptyList(), active = true)
        a.bluetoothLeScanner?.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb)
        log("scan: started")
        // Bounded: an open-ended LOW_LATENCY scan drains battery and gets throttled by the OS.
        main.postDelayed(scanTimeout, SCAN_MS)
    }

    private val scanTimeout = Runnable { stopScan() }

    fun stopScan() {
        main.removeCallbacks(scanTimeout)
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCb) }
        (_state.value as? CameraState.Scanning)?.let { _state.value = it.copy(active = false) }
    }

    // ---- connect: BLE ------------------------------------------------------------------------------

    private var gatt: GattClient? = null
    private var camera: FoundCamera? = null
    private var ssid: String? = null
    private var pass: String? = null
    private var paired = false
    private var credsRequested = false
    private var wifiStarted = false
    @Volatile private var generation = 0

    fun connect(cam: FoundCamera) {
        stopScan()
        val dev = devices[cam.address] ?: adapter?.getRemoteDevice(cam.address)
        if (dev == null) { _state.value = CameraState.Failed("Camera not found — scan again.", cam); return }
        disconnect()
        val gen = ++generation
        camera = cam
        ssid = cam.name
        pass = null
        paired = false; credsRequested = false; wifiStarted = false
        step("Connecting over Bluetooth…", 0.05f)
        log("connect: ${cam.name} ${cam.address} model=${cam.model.name}")
        gatt = GattClient(app, object : GattClient.Listener {
            override fun onLog(s: String) = log(s)
            override fun onReady(gatt: GattClient) {
                main.post { if (gen == generation) onGattReady() }
            }
            override fun onNotification(sourceChar: UUID, raw: ByteArray, parsed: DjiMessage?) {
                main.post { if (gen == generation) onNotify(parsed) }
            }
            override fun onDisconnected() {
                main.post {
                    if (gen != generation) return@post
                    log("ble: disconnected (paired=$paired wifi=$wifiStarted)")
                    stopBleKeepalive()
                    // A drop after the WiFi handoff is normal; before it, the connect failed.
                    if (!wifiStarted) fail("Bluetooth link dropped before pairing finished. Wake the camera and retry.")
                }
            }
        }).also { it.connect(dev) }
        // Overall guard: nothing below has its own timeout for "camera never answered".
        main.postDelayed({
            if (gen == generation && _state.value !is CameraState.Ready && _state.value !is CameraState.Failed && !wifiStarted &&
                _state.value !is CameraState.AwaitingApproval)
                fail("Camera didn't respond. Make sure it's on and awake (screen on), then retry.")
        }, CONNECT_TIMEOUT_MS)
    }

    private fun onGattReady() {
        step("Pairing…", 0.15f)
        gatt?.writeCommand(OsmoCommands.sessionPing(OsmoCommands.SESSION_WAKE))
        main.postDelayed({ sendPair() }, 120)
        // fff5 is write-without-response; re-send until the camera answers (same identity each time).
        for (d in longArrayOf(2500, 5000)) main.postDelayed({ if (!paired && _state.value !is CameraState.AwaitingApproval) sendPair() }, d)
    }

    private fun sendPair() {
        gatt?.writeCommand(OsmoCommands.setPairingPin(camera?.model?.pairingToken ?: "osmo", identifier = pairIdentity()))
        log("ble: sent SetPairingPIN")
    }

    /** One identity per install, persisted: a known identity skips the approval prompt next time. */
    private fun pairIdentity(): String =
        prefs.getString("pair_identity", null) ?: DjiPairMessagePayload.newInstallIdentity().also {
            prefs.edit().putString("pair_identity", it).apply()
        }

    private fun onNotify(m: DjiMessage?) {
        m ?: return
        // The camera sends some messages as requests and drops the link (~6 s) if they go unanswered.
        if (m.flags == 0x40) {
            val respTarget = ((m.target and 0xFF) shl 8) or ((m.target shr 8) and 0xFF)
            val payload = if (m.cmdSet == 0x00 && m.cmdId == 0x81) OsmoCommands.APP_DEVICE_INFO else m.payload
            gatt?.writeCommand(DjiMessage(respTarget, m.id, (m.type and 0xFFFF00) or 0xC0, payload).encode())
            if (m.cmdSet == 0x07 && m.cmdId == 0x46) { log("ble: pairing approved on camera"); onPaired() }
            return
        }
        if (m.cmdSet != 0x07) return
        val p = m.payload
        when (m.cmdId) {
            0x45 -> when (if (p.size >= 2) p[1].toInt() and 0xFF else -1) {
                0x01 -> { log("ble: already paired"); onPaired() }
                0x02 -> { log("ble: approval required"); camera?.let { _state.value = CameraState.AwaitingApproval(it) } }
                else -> log("ble: pair reply ${p.joinToString("") { "%02x".format(it) }}")
            }
            0x46 -> onPaired()
            0x07 -> packString(p)?.takeIf { it.isNotEmpty() }?.let { ssid = it; log("ble: ssid=$it") }
            0x0E -> {
                val pw = packString(p)
                if (!pw.isNullOrEmpty()) {
                    pass = pw
                    camera?.let { secrets.edit().putString("pass_${it.address}", pw).apply() }
                    log("ble: got WiFi password (${pw.length} chars)")
                    startWifi()
                }
            }
        }
    }

    private fun onPaired() {
        if (credsRequested) return
        paired = true; credsRequested = true
        step("Waking camera WiFi…", 0.3f)
        startBleKeepalive()
        // Paced like the official app: back-to-back fff5 writes get dropped.
        main.postDelayed({ gatt?.writeCommand(OsmoCommands.session5310()) }, 100)
        main.postDelayed({ gatt?.writeCommand(OsmoCommands.wifiQuery(0x07, id = 0x8007)) }, 900)
        main.postDelayed({ gatt?.writeCommand(OsmoCommands.wifiQuery(0x0E, id = 0x800E)) }, 1400)
        main.postDelayed({
            if (wifiStarted) return@postDelayed
            val saved = camera?.let { secrets.getString("pass_${it.address}", null) }
            if (saved != null) { log("ble: no password reply — using the saved one"); pass = saved; startWifi() }
            else fail("The camera didn't share its WiFi password. Open DJI Mimo once to activate the camera, then retry.")
        }, 4500)
    }

    private fun packString(p: ByteArray): String? {
        if (p.size < 2) return null
        val len = p[1].toInt() and 0xFF
        return if (2 + len > p.size) null else String(p, 2, len, Charsets.US_ASCII)
    }

    private val bleKeepalive = object : Runnable {
        override fun run() {
            gatt?.writeCommand(OsmoCommands.sessionPing(OsmoCommands.SESSION_KEEPALIVE))
            main.postDelayed(this, 1000)
        }
    }
    private fun startBleKeepalive() { main.removeCallbacks(bleKeepalive); main.postDelayed(bleKeepalive, 1000) }
    private fun stopBleKeepalive() = main.removeCallbacks(bleKeepalive)

    // ---- connect: WiFi -----------------------------------------------------------------------------

    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var cameraNetwork: Network? = null
    private var rejoins = 0

    private fun startWifi() {
        if (wifiStarted) return
        val s = ssid ?: return fail("Camera didn't report its WiFi name.")
        val pw = pass ?: return fail("No WiFi password for the camera.")
        if (app.getSystemService(WifiManager::class.java)?.isWifiEnabled == false)
            return fail("Turn on WiFi — the camera sends files over its own WiFi network.")
        wifiStarted = true
        // The AP takes a few seconds to come up after the wake; the system dialog keeps searching.
        step("Joining camera WiFi \"$s\" — tap Connect when Android asks", 0.45f)
        main.postDelayed({ requestCameraNetwork(s, pw) }, 3000)
    }

    private fun requestCameraNetwork(s: String, pw: String) {
        netCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        val spec = WifiNetworkSpecifier.Builder().setSsid(s).apply {
            if (camera?.model?.wpa3 == true) setWpa3Passphrase(pw) else setWpa2Passphrase(pw)
        }.build()
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec)
            .build()
        val gen = generation
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (gen != generation) return
                log("wifi: camera network available")
                cameraNetwork = network
                CameraNet.network = network
                // A rejoin hands us a new Network: the old session's socket is bound to the dead one.
                val rejoin = session != null
                Thread({ openSession(gen, rejoin) }, "camera-session").start()
            }
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = Unit
            override fun onUnavailable() {
                // A phone without 5 GHz never even sees the AP; DJI cameras commonly run it on 5 GHz.
                val only24 = app.getSystemService(WifiManager::class.java)?.is5GHzBandSupported == false
                val msg = if (only24) "This phone only has 2.4 GHz WiFi and couldn't find \"$s\". The camera's WiFi is likely on 5 GHz — use a phone with 5 GHz WiFi, or the USB-C cable."
                else "Couldn't join the camera's WiFi \"$s\" (cancelled, or the camera's WiFi went to sleep). Wake the camera and retry."
                main.post { if (gen == generation) fail(msg) }
            }
            override fun onLost(network: Network) {
                main.post {
                    if (gen != generation) return@post
                    log("wifi: camera network lost")
                    CameraNet.network = null
                    // A WifiNetworkSpecifier request doesn't reconnect by itself; re-issue it.
                    if (rejoins++ < MAX_REJOINS) requestCameraNetwork(s, pw)
                    else fail("Camera WiFi dropped. Bring the phone closer and reconnect.")
                }
            }
        }
        netCallback = cb
        cm.requestNetwork(req, cb)
    }

    // ---- datalink + listing ------------------------------------------------------------------------

    @Volatile private var session: MediaSession? = null

    private fun openSession(gen: Int, rejoin: Boolean = false) {
        val cam = camera ?: return
        if (rejoin) {
            // Keep the file list the UI already has; just re-establish the datalink + keepalive.
            session?.let { runCatching { it.close() } }
            val s = CameraSession(::log, cam.model.datalinkPort, cam.model.tcpPoke)
            session = s
            runCatching { s.fetchFileList(CAMERA_IP) }
            if (gen == generation) s.startKeepAlive()
            log("datalink: re-established after WiFi rejoin (ok=${s.handshakeOk})")
            return
        }
        step("Reading files from the camera…", 0.65f)
        fun open(m: CameraModel): Pair<MediaSession, List<CameraFile>> {
            log("datalink: udp/${m.datalinkPort} poke=${m.tcpPoke}")
            val s = CameraSession(::log, m.datalinkPort, m.tcpPoke)
            s.onFetchProgress = { pct -> step("Reading files from the camera…", 0.65f + pct * 0.3f / 100) }
            session = s
            return s to runCatching { s.fetchFileList(CAMERA_IP) }.getOrElse { log("datalink error: ${it.message}"); emptyList() }
        }
        var (s, files) = open(cam.model)
        if (!s.handshakeOk && gen == generation) {
            runCatching { s.close() }
            val alt = open(cam.model.alternate())
            s = alt.first; files = alt.second
        }
        if (gen != generation) { runCatching { s.close() }; return }
        if (!s.handshakeOk) { main.post { fail("Camera WiFi joined, but it didn't answer the file-list request.") }; return }
        // The newest page is ~45 files; walk older pages so the whole card is offered.
        val all = files.toMutableList()
        var pages = 0
        while (s.moreAvailable && pages++ < MAX_PAGES && gen == generation) {
            val more = runCatching { s.fetchNextPage() }.getOrElse { emptyList() }
            if (more.isEmpty()) break
            all += more
            step("Reading files from the camera… ${all.size}", 0.95f)
        }
        // Holds the AP up, keeps playback mode, and stops the camera sleeping mid-transfer.
        s.startKeepAlive()
        val resolved = all.filter { !it.isIndexed }.map { it.copy(storage = storageFor(it)) }
            .distinctBy { "${it.storage}:${it.path}" }
        log("datalink: ${resolved.size} files listed")
        main.post {
            if (gen == generation) _state.value = CameraState.Ready(cam, resolved, hasInternet())
        }
    }

    /** `/v2?storage=` mount for a file: known from the store-specific query, else guessed + probed. */
    private fun storageFor(f: CameraFile): Int {
        if (camera?.model?.singleSdStorage == true) return 0
        if (f.storageKnown) return f.storage
        val guess = StorageRules.mountGuess(false, f.handle, f.cmdHandle) ?: 0
        return storageProbe.getOrPut(guess) {
            if (head(PathAddressing.byPath(guess, f.path)) == 200) guess
            else if (head(PathAddressing.byPath(1 - guess, f.path)) == 200) 1 - guess else guess
        }
    }
    private val storageProbe = HashMap<Int, Int>()

    // ---- HTTP --------------------------------------------------------------------------------------

    /** OkHttp bound to the camera network; null when not connected. */
    fun httpClient(): OkHttpClient? {
        val n = cameraNetwork ?: return null
        return httpClient?.takeIf { httpNetwork == n } ?: OkHttpClient.Builder()
            .socketFactory(n.socketFactory)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            // Camera cuts long reads periodically; the caller resumes with a Range request instead.
            .retryOnConnectionFailure(false)
            .build().also { httpClient = it; httpNetwork = n }
    }
    private var httpClient: OkHttpClient? = null
    private var httpNetwork: Network? = null

    fun url(path: String) = "http://$CAMERA_IP$path"

    private fun head(path: String): Int = runCatching {
        httpClient()!!.newCall(Request.Builder().url(url(path)).head().build()).execute().use { it.code }
    }.getOrDefault(-1)

    /**
     * Whether the phone has a validated internet network *other than* the camera right now. Most phones
     * drop home WiFi when they join the camera, so this is usually mobile data or nothing.
     */
    fun hasInternet(): Boolean = cm.allNetworks.any { n ->
        n != cameraNetwork && cm.getNetworkCapabilities(n)?.let {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } == true
    }

    // ---- teardown ----------------------------------------------------------------------------------

    fun disconnect() {
        generation++
        main.removeCallbacksAndMessages(null)
        stopBleKeepalive()
        session?.let { s -> Thread { runCatching { s.close() } }.start() }
        session = null
        gatt?.let { runCatching { it.disconnect() }; runCatching { it.close() } }
        gatt = null
        netCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        netCallback = null
        cameraNetwork = null
        CameraNet.network = null
        httpClient = null
        storageProbe.clear()
        rejoins = 0
        if (_state.value !is CameraState.Failed) _state.value = CameraState.Idle
    }

    private fun step(text: String, progress: Float) {
        val cam = camera ?: return
        main.post { if (_state.value !is CameraState.Ready && _state.value !is CameraState.Failed) _state.value = CameraState.Connecting(cam, text, progress) }
    }

    private fun fail(msg: String) {
        log("FAILED: $msg")
        val cam = camera
        disconnect()
        _state.value = CameraState.Failed(msg, cam)
    }

    companion object {
        const val TAG = "GPicCamera"
        const val CAMERA_IP = "192.168.2.1"
        private const val SCAN_MS = 12_000L
        private const val CONNECT_TIMEOUT_MS = 25_000L
        private const val MAX_REJOINS = 3
        private const val MAX_PAGES = 60
    }
}
