package app.diskscan.system

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** A sensor and its fixed characteristics, with a key that survives list reloads. */
data class SensorMeta(
    val sensor: Sensor,
    val key: String,
    val name: String,
    val vendor: String,
    val typeName: String,
    val axes: List<String>,
    val unit: String,
    val resolution: Float,
    val maxRange: Float,
    val powerMa: Float,
    val minDelayUs: Int,
    val maxRateHz: Int,
    val wakeup: Boolean,
    val permission: String?,  // non-null if a runtime permission is required to read it
)

private fun sensorKey(s: Sensor) = "${s.type}:${s.name}"

/** Axis labels and units for the common sensor types, so CSV columns are self-describing. */
private fun axesAndUnit(type: Int): Pair<List<String>, String> = when (type) {
    Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_GRAVITY -> listOf("x", "y", "z") to "m/s²"
    Sensor.TYPE_ACCELEROMETER_UNCALIBRATED -> listOf("x", "y", "z", "bx", "by", "bz") to "m/s²"
    Sensor.TYPE_GYROSCOPE -> listOf("x", "y", "z") to "rad/s"
    Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> listOf("x", "y", "z", "bx", "by", "bz") to "rad/s"
    Sensor.TYPE_MAGNETIC_FIELD -> listOf("x", "y", "z") to "µT"
    Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> listOf("x", "y", "z", "bx", "by", "bz") to "µT"
    Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> listOf("x", "y", "z", "w", "acc") to "unit quaternion"
    Sensor.TYPE_ORIENTATION -> listOf("azimuth", "pitch", "roll") to "°"
    Sensor.TYPE_LIGHT -> listOf("lux") to "lx"
    Sensor.TYPE_PROXIMITY -> listOf("distance") to "cm"
    Sensor.TYPE_PRESSURE -> listOf("pressure") to "hPa"
    Sensor.TYPE_AMBIENT_TEMPERATURE, Sensor.TYPE_TEMPERATURE -> listOf("temp") to "°C"
    Sensor.TYPE_RELATIVE_HUMIDITY -> listOf("humidity") to "%"
    Sensor.TYPE_STEP_COUNTER -> listOf("steps") to "count"
    Sensor.TYPE_STEP_DETECTOR, Sensor.TYPE_SIGNIFICANT_MOTION -> listOf("event") to "trigger"
    Sensor.TYPE_HEART_RATE -> listOf("bpm") to "bpm"
    else -> listOf("v0", "v1", "v2") to ""
}

/** Runtime permission a sensor type needs to deliver data, or null if it's freely readable. */
private fun requiredPermission(type: Int): String? = when (type) {
    Sensor.TYPE_STEP_COUNTER, Sensor.TYPE_STEP_DETECTOR -> android.Manifest.permission.ACTIVITY_RECOGNITION
    Sensor.TYPE_HEART_RATE, Sensor.TYPE_HEART_BEAT -> android.Manifest.permission.BODY_SENSORS
    else -> null
}

fun listSensors(ctx: Context): List<SensorMeta> {
    val sm = ctx.getSystemService(SensorManager::class.java)
    return sm.getSensorList(Sensor.TYPE_ALL).map { s ->
        val (axes, unit) = axesAndUnit(s.type)
        SensorMeta(
            sensor = s,
            key = sensorKey(s),
            name = s.name,
            vendor = s.vendor,
            typeName = s.stringType.removePrefix("android.sensor.").removePrefix("qti.sensor."),
            axes = axes,
            unit = unit,
            resolution = s.resolution,
            maxRange = s.maximumRange,
            powerMa = s.power,
            minDelayUs = s.minDelay,
            maxRateHz = if (s.minDelay > 0) (1_000_000 / s.minDelay) else 0,
            wakeup = s.isWakeUpSensor,
            permission = requiredPermission(s.type),
        )
    }.sortedBy { it.name.lowercase() }
}

/**
 * Live sampling for a set of sensors. Keeps the latest reading and a short history for charts.
 * Separate from recording so the screen can show values without writing anything.
 */
class SensorLive(private val ctx: Context) {
    private val sm = ctx.getSystemService(SensorManager::class.java)
    private val thread = HandlerThread("sensor-live").apply { start() }
    private val handler = Handler(thread.looper)
    private val listeners = mutableMapOf<String, SensorEventListener>()

    val latest = HashMap<String, FloatArray>()
    val history = HashMap<String, ArrayDeque<FloatArray>>()  // up to 120 recent samples
    var onSample: (() -> Unit)? = null

    fun watch(meta: SensorMeta) {
        if (listeners.containsKey(meta.key)) return
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val v = e.values.copyOf()
                synchronized(latest) {
                    latest[meta.key] = v
                    val h = history.getOrPut(meta.key) { ArrayDeque() }
                    h.addLast(v)
                    while (h.size > 120) h.removeFirst()
                }
                onSample?.invoke()
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        listeners[meta.key] = l
        sm.registerListener(l, meta.sensor, SensorManager.SENSOR_DELAY_UI, handler)
    }

    fun unwatchAll() {
        listeners.values.forEach { sm.unregisterListener(it) }
        listeners.clear()
    }

    fun release() {
        unwatchAll()
        thread.quitSafely()
    }
}

data class RecordingState(val running: Boolean, val sampleCount: Long, val elapsedMs: Long, val sensorCount: Int)
data class RecordingResult(val folder: String, val files: Int, val samples: Long, val seconds: Double)

/**
 * Records selected sensors to CSV. One file per sensor plus a README describing the device,
 * the sensors and the columns, so a session is self-contained for an ML pipeline. Files land
 * in Documents/ByteFalcon/sensors/<timestamp>/ (reachable with the app's all-files access).
 */
class SensorRecorder(private val ctx: Context) {
    private val sm = ctx.getSystemService(SensorManager::class.java)
    private var thread: HandlerThread? = null
    private val writers = mutableMapOf<String, BufferedWriter>()
    private val listeners = mutableListOf<SensorEventListener>()
    private val count = AtomicLong(0)
    private var startedAtMs = 0L
    private var startedAtNanos = 0L
    private var folder: File? = null
    private var sensorCount = 0

    val running get() = thread != null

    fun state() = RecordingState(running, count.get(),
        if (startedAtMs > 0) System.currentTimeMillis() - startedAtMs else 0L, sensorCount)

    /** rateUs: desired period in microseconds (e.g. SensorManager.SENSOR_DELAY_FASTEST is 0). */
    fun start(metas: List<SensorMeta>, rateUs: Int, label: String): Boolean {
        if (running || metas.isEmpty()) return false
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "ByteFalcon/sensors/$stamp")
        if (!dir.mkdirs() && !dir.isDirectory) return false
        folder = dir
        sensorCount = metas.size
        count.set(0)
        startedAtMs = System.currentTimeMillis()
        startedAtNanos = SystemClock.elapsedRealtimeNanos()
        val th = HandlerThread("sensor-rec").apply { start() }
        thread = th
        val handler = Handler(th.looper)

        writeReadme(dir, metas, rateUs, label)
        for (meta in metas) {
            val safe = meta.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val w = File(dir, "$safe.csv").bufferedWriter()
            w.write("timestamp_ns,elapsed_s," + meta.axes.joinToString(","))
            w.newLine()
            writers[meta.key] = w
            val l = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) {
                    val elapsed = (e.timestamp - startedAtNanos) / 1e9
                    val sb = StringBuilder(64)
                    sb.append(e.timestamp).append(',').append(elapsed)
                    for (v in e.values) sb.append(',').append(v)
                    synchronized(w) { w.write(sb.toString()); w.newLine() }
                    count.incrementAndGet()
                }
                override fun onAccuracyChanged(s: Sensor?, a: Int) {}
            }
            listeners += l
            sm.registerListener(l, meta.sensor, rateUs, handler)
        }
        return true
    }

    fun stop(): RecordingResult {
        listeners.forEach { sm.unregisterListener(it) }
        listeners.clear()
        synchronized(this) { writers.values.forEach { runCatching { it.flush(); it.close() } } }
        val files = writers.size
        writers.clear()
        thread?.quitSafely()
        thread = null
        val seconds = (System.currentTimeMillis() - startedAtMs) / 1000.0
        val result = RecordingResult(folder?.absolutePath ?: "", files, count.get(), seconds)
        startedAtMs = 0
        return result
    }

    private fun writeReadme(dir: File, metas: List<SensorMeta>, rateUs: Int, label: String) {
        File(dir, "README.txt").bufferedWriter().use { w ->
            w.write("ByteFalcon sensor recording\n")
            w.write("Label: ${label.ifBlank { "(none)" }}\n")
            w.write("Started: ${Date()}\n")
            w.write("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}\n")
            w.write("Requested period: ${if (rateUs == 0) "fastest" else "$rateUs us"}\n\n")
            w.write("Each sensor has its own CSV. Columns: timestamp_ns (event clock, elapsedRealtimeNanos),\n")
            w.write("elapsed_s (seconds since recording start), then one column per axis.\n\n")
            for (m in metas) {
                w.write("${m.name}\n")
                w.write("  type=${m.typeName} vendor=${m.vendor} unit=${m.unit} axes=${m.axes.joinToString(",")}\n")
                w.write("  resolution=${m.resolution} maxRange=${m.maxRange} maxRate=${m.maxRateHz}Hz\n")
            }
        }
    }
}
