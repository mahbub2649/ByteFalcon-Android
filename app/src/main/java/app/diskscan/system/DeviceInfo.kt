package app.diskscan.system

import android.app.ActivityManager
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.os.Build
import android.os.StatFs
import android.util.Size
import android.view.WindowManager
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.sqrt

// All public-API readings: on an unrooted phone this is the deepest hardware view available.

data class SpecGroup(val title: String, val rows: List<Pair<String, String>>)

private fun readFirstLong(vararg paths: String): Long? {
    for (p in paths) runCatching { return File(p).readText().trim().toLong() }
    return null
}

private fun ghz(khz: Long) = "%.2f GHz".format(khz / 1_000_000.0)

/** Groups CPU cores by their max frequency (performance vs efficiency clusters). */
private fun cpuClusters(): String {
    val cores = (0 until Runtime.getRuntime().availableProcessors()).mapNotNull {
        readFirstLong("/sys/devices/system/cpu/cpu$it/cpufreq/cpuinfo_max_freq")
    }
    if (cores.isEmpty()) return "${Runtime.getRuntime().availableProcessors()} cores"
    return cores.groupingBy { it }.eachCount().toSortedMap().entries.reversed()
        .joinToString(" + ") { (freq, n) -> "$n × ${ghz(freq)}" }
}

/** Battery design capacity via the hidden PowerProfile, the same source Settings uses. No permission. */
private fun batteryCapacityMah(ctx: Context): Int? = runCatching {
    val cls = Class.forName("com.android.internal.os.PowerProfile")
    val inst = cls.getConstructor(Context::class.java).newInstance(ctx)
    (cls.getMethod("getBatteryCapacity").invoke(inst) as Double).roundToInt()
}.getOrNull()

private fun cameras(ctx: Context): List<Pair<String, String>> = runCatching {
    val cm = ctx.getSystemService(CameraManager::class.java)
    cm.cameraIdList.map { id ->
        val c = cm.getCameraCharacteristics(id)
        val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_FRONT -> "Front"
            CameraCharacteristics.LENS_FACING_BACK -> "Back"
            else -> "External"
        }
        val px: Size? = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val mp = px?.let { "%.1f MP".format(it.width.toLong() * it.height / 1_000_000.0) } ?: "?"
        "$facing camera ($id)" to mp
    }
}.getOrElse { emptyList() }

fun deviceSpecs(ctx: Context): List<SpecGroup> {
    val groups = mutableListOf<SpecGroup>()
    val am = ctx.getSystemService(ActivityManager::class.java)
    val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }

    groups += SpecGroup("Device", buildList {
        add("Manufacturer" to Build.MANUFACTURER)
        add("Brand" to Build.BRAND)
        add("Model" to Build.MODEL)
        add("Codename" to Build.DEVICE)
        if (Build.VERSION.SDK_INT >= 31) {
            add("Chipset" to "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        }
        add("Board" to Build.BOARD)
    })

    groups += SpecGroup("Android", listOf(
        "Version" to "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        "Security patch" to Build.VERSION.SECURITY_PATCH,
        "Build" to Build.DISPLAY,
        "Build fingerprint" to Build.FINGERPRINT,
        "Kernel" to (System.getProperty("os.version") ?: "?"),
        "Baseband" to (runCatching { Build.getRadioVersion() }.getOrNull() ?: "?"),
    ))

    groups += SpecGroup("Processor", listOf(
        "Architecture" to Build.SUPPORTED_ABIS.joinToString(", "),
        "Cores" to "${Runtime.getRuntime().availableProcessors()}",
        "Clusters" to cpuClusters(),
    ))

    groups += SpecGroup("Memory & storage", buildList {
        add("RAM" to fmtBytesGb(mi.totalMem))
        add("Low-memory threshold" to fmtBytesGb(mi.threshold))
        val data = runCatching { StatFs(ctx.filesDir.path) }.getOrNull()
        val ext = runCatching { StatFs(android.os.Environment.getExternalStorageDirectory().path) }.getOrNull()
        ext?.let { add("Internal storage" to "${fmtBytesGb(it.totalBytes)} (${fmtBytesGb(it.availableBytes)} free)") }
        data?.let { add("Data partition" to fmtBytesGb(it.totalBytes)) }
    })

    runCatching {
        val wm = ctx.getSystemService(WindowManager::class.java)
        val metrics = wm.currentWindowMetrics.bounds
        val dm = ctx.resources.displayMetrics
        val inches = sqrt((metrics.width() / dm.xdpi).let { it * it } + (metrics.height() / dm.ydpi).let { it * it })
        val display = ctx.display
        groups += SpecGroup("Display", buildList {
            add("Resolution" to "${metrics.width()} × ${metrics.height()} px")
            add("Density" to "${dm.densityDpi} dpi (${"%.1f".format(dm.density)}×)")
            add("Size" to "%.1f in".format(inches))
            display?.let { d ->
                add("Refresh rate" to "%.0f Hz".format(d.refreshRate))
                val modes = d.supportedModes.map { it.refreshRate.roundToInt() }.distinct().sorted()
                if (modes.size > 1) add("Supported rates" to modes.joinToString(", ") { "$it Hz" })
            }
        })
    }

    groups += SpecGroup("Battery", buildList {
        batteryCapacityMah(ctx)?.let { add("Design capacity" to "$it mAh") }
        val bm = ctx.getSystemService(android.os.BatteryManager::class.java)
        val now = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        if (now > 0) add("Charge counter" to "${now / 1000} mAh")
        add("Technology" to (ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            ?.getStringExtra(android.os.BatteryManager.EXTRA_TECHNOLOGY) ?: "?"))
    })

    val cams = cameras(ctx)
    if (cams.isNotEmpty()) groups += SpecGroup("Cameras", cams)

    return groups
}

private fun fmtBytesGb(n: Long): String {
    val gb = n / 1_000_000_000.0
    return if (gb >= 1) "%.1f GB".format(gb) else "%.0f MB".format(n / 1_000_000.0)
}

// ------------------------------------------------------------------ network info

data class NetInfo(
    val connected: Boolean,
    val transport: String,
    val downKbps: Int,
    val upKbps: Int,
    val iface: String?,
    val addresses: List<String>,
    val dns: List<String>,
    val gateways: List<String>,
    val privateDns: String?,
    val proxy: String?,
    val vpnActive: Boolean,
    val vpnApps: List<String>,
)

fun networkInfo(ctx: Context): NetInfo {
    val cm = ctx.getSystemService(ConnectivityManager::class.java)
    val active = cm.activeNetwork
    val caps: NetworkCapabilities? = active?.let { cm.getNetworkCapabilities(it) }
    val lp: LinkProperties? = active?.let { cm.getLinkProperties(it) }
    // A VPN shows up as a separate network with the VPN transport.
    var vpn = false
    val vpnApps = mutableListOf<String>()
    for (n in cm.allNetworks) {
        val c = cm.getNetworkCapabilities(n) ?: continue
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            vpn = true
            @Suppress("DEPRECATION")
            c.ownerUid.takeIf { it > 0 }?.let { uid ->
                runCatching { ctx.packageManager.getPackagesForUid(uid)?.firstOrNull() }.getOrNull()?.let { vpnApps += it }
            }
        }
    }
    val transport = when {
        caps == null -> "Not connected"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
        else -> "Other"
    }
    return NetInfo(
        connected = caps != null,
        transport = transport,
        downKbps = caps?.linkDownstreamBandwidthKbps ?: 0,
        upKbps = caps?.linkUpstreamBandwidthKbps ?: 0,
        iface = lp?.interfaceName,
        addresses = lp?.linkAddresses?.map { it.address.hostAddress ?: "" }?.filter { it.isNotEmpty() } ?: emptyList(),
        dns = lp?.dnsServers?.mapNotNull { it.hostAddress } ?: emptyList(),
        gateways = lp?.routes?.filter { it.hasGateway() }?.mapNotNull { it.gateway?.hostAddress }?.distinct() ?: emptyList(),
        privateDns = if (lp?.isPrivateDnsActive == true) (lp.privateDnsServerName ?: "on (automatic)") else null,
        proxy = lp?.httpProxy?.let { "${it.host}:${it.port}" },
        vpnActive = vpn,
        vpnApps = vpnApps.distinct(),
    )
}

// ------------------------------------------------------------------ camera & mic in use

/**
 * Watches whether the camera or microphone is being used by any app, the signal behind
 * Android's green privacy dots. Camera uses CameraManager availability (a camera becomes
 * "unavailable" when another app opens it); mic uses the active-recording list.
 */
class PrivacyMonitor(private val ctx: Context) {
    var cameraInUse = false; private set
    var micInUse = false; private set
    var micSources: List<String> = emptyList(); private set
    private var onChange: (() -> Unit)? = null

    private val cameraIds = runCatching { ctx.getSystemService(CameraManager::class.java).cameraIdList.toMutableSet() }.getOrDefault(mutableSetOf())
    private val openCameras = mutableSetOf<String>()

    private val camCb = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(id: String) { openCameras.remove(id); update() }
        override fun onCameraUnavailable(id: String) { if (id in cameraIds) openCameras.add(id); update() }
    }
    private val micCb = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) = readMic(configs)
    }

    private fun update() {
        cameraInUse = openCameras.isNotEmpty()
        onChange?.invoke()
    }
    private fun readMic(configs: List<AudioRecordingConfiguration>?) {
        micInUse = !configs.isNullOrEmpty()
        micSources = configs?.map { audioSourceName(it.clientAudioSource) }?.distinct() ?: emptyList()
        onChange?.invoke()
    }

    fun start(onChange: () -> Unit) {
        this.onChange = onChange
        val cm = ctx.getSystemService(CameraManager::class.java)
        cm.registerAvailabilityCallback(camCb, null)
        val am = ctx.getSystemService(AudioManager::class.java)
        am.registerAudioRecordingCallback(micCb, null)
        readMic(am.activeRecordingConfigurations)
    }
    fun stop() {
        runCatching { ctx.getSystemService(CameraManager::class.java).unregisterAvailabilityCallback(camCb) }
        runCatching { ctx.getSystemService(AudioManager::class.java).unregisterAudioRecordingCallback(micCb) }
        onChange = null
    }
}

private fun audioSourceName(source: Int) = when (source) {
    android.media.MediaRecorder.AudioSource.MIC -> "Microphone"
    android.media.MediaRecorder.AudioSource.CAMCORDER -> "Camcorder"
    android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "Voice/VoIP call"
    android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION -> "Voice recognition"
    android.media.MediaRecorder.AudioSource.UNPROCESSED -> "Raw microphone"
    else -> "Audio (source $source)"
}
