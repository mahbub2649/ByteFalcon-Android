package app.diskscan.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.diskscan.fmtBytes
import app.diskscan.system.NetInfo
import app.diskscan.system.PrivacyMonitor
import app.diskscan.system.RecordingResult
import app.diskscan.system.SensorLive
import app.diskscan.system.SensorMeta
import app.diskscan.system.SensorRecorder
import app.diskscan.system.SpecGroup
import app.diskscan.system.deviceSpecs
import app.diskscan.system.listSensors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Long-lived so a recording keeps running while you move around the app. */
class DeviceState(private val scope: CoroutineScope, private val ctx: Context, val toast: (String) -> Unit) {
    val recorder = SensorRecorder(ctx)
    var sensors by mutableStateOf<List<SensorMeta>>(emptyList())
    val selected = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()
    var rateUs by androidx.compose.runtime.mutableIntStateOf(20000)  // 50 Hz
    var label by mutableStateOf("")
    var recState by mutableStateOf(app.diskscan.system.RecordingState(false, 0, 0, 0))  // Compose-observed
    var lastResult by mutableStateOf<RecordingResult?>(null)
    var hasSensorPerms by mutableStateOf(false)

    val recording get() = recState.running

    fun loadSensors() = scope.launch { if (sensors.isEmpty()) sensors = io { listSensors(ctx) } }

    fun toggle(key: String) { selected[key] = !(selected[key] ?: false) }
    fun selectedMetas() = sensors.filter { selected[it.key] == true }

    fun startRecording() {
        val metas = selectedMetas()
        if (metas.isEmpty()) { toast("Pick at least one sensor first"); return }
        if (!recorder.start(metas, rateUs, label)) { toast("Couldn't start recording"); return }
        lastResult = null
        recState = recorder.state()
        scope.launch {
            while (recorder.running) { recState = recorder.state(); delay(300) }
        }
    }

    fun stopRecording() {
        lastResult = recorder.stop()
        recState = recorder.state()
        lastResult?.let { toast("Saved ${it.samples} samples to ${it.folder.substringAfterLast("/")}") }
    }
}

private enum class DeviceTab(val label: String) { Specs("Specs"), Sensors("Sensors"), Privacy("Privacy") }

@Composable
fun DeviceScreen(dev: DeviceState, onRequestSensorPerms: () -> Unit) {
    var tab by remember { mutableStateOf(DeviceTab.Specs) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)) { SectionTitle("Device") }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (t in DeviceTab.entries) Chip(t.label, tab == t) { tab = t }
        }
        Box(Modifier.weight(1f)) {
            when (tab) {
                DeviceTab.Specs -> SpecsTab()
                DeviceTab.Sensors -> SensorsTab(dev, onRequestSensorPerms)
                DeviceTab.Privacy -> PrivacyTab()
            }
        }
    }
}

// ------------------------------------------------------------------ specs

@Composable
private fun SpecsTab() {
    val ctx = LocalContext.current
    val groups by produceState<List<SpecGroup>?>(null) { value = io { deviceSpecs(ctx) } }
    val g = groups ?: return Loading("Reading hardware…")
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 120.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(g) { group ->
            Glass(Modifier.fillMaxWidth()) {
                Text(group.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Cyan)
                Spacer(Modifier.height(8.dp))
                for ((k, v) in group.rows) Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(k, color = Muted, fontSize = 13.sp, modifier = Modifier.width(140.dp))
                    Text(v, fontSize = 13.sp, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

// ------------------------------------------------------------------ sensors

@Composable
private fun SensorsTab(dev: DeviceState, onRequestSensorPerms: () -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { dev.loadSensors() }
    val live = remember { SensorLive(ctx) }
    var tick by remember { mutableLongStateOf(0L) }
    DisposableEffect(Unit) {
        live.onSample = { tick++ }
        onDispose { live.release() }  // quit the background thread only when leaving the tab
    }
    LaunchedEffect(dev.sensors, dev.hasSensorPerms) {
        live.unwatchAll()
        dev.sensors.forEach { if (it.permission == null || dev.hasSensorPerms) live.watch(it) }
    }
    val running = dev.recording
    @Suppress("UNUSED_EXPRESSION") tick  // read so live sensor values recompose

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 120.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { RecorderCard(dev) }
        val gated = dev.sensors.any { it.permission != null } && !dev.hasSensorPerms
        if (gated) item {
            Glass(Modifier.fillMaxWidth()) {
                Text("Some sensors need permission", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text("Step counter, step detector and heart rate need the activity-recognition and body-sensors permissions.", color = Muted, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                GhostButton("Grant sensor permissions", Modifier.fillMaxWidth(), onClick = onRequestSensorPerms)
            }
        }
        item { Text("${dev.sensors.size} sensors", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
        items(dev.sensors, key = { it.key }) { meta ->
            val values = synchronized(live.latest) { live.latest[meta.key]?.copyOf() }
            val hist = synchronized(live.latest) { live.history[meta.key]?.toList() }
            SensorRow(meta, values, hist, dev.selected[meta.key] == true, enabled = !running) { dev.toggle(meta.key) }
        }
    }
}

@Composable
private fun RecorderCard(dev: DeviceState) {
    val st = dev.recState
    val running = st.running
    Glass(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Record a session", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
            if (running) Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(Bad))
                Spacer(Modifier.width(6.dp))
                Text("REC", color = Bad, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
        Text("Pick sensors below, choose a rate, and record timestamped CSV for your ML projects.", color = Muted, fontSize = 12.5.sp)
        Spacer(Modifier.height(10.dp))
        if (!running) {
            Text("Sample rate", color = Muted, fontSize = 12.5.sp)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((hz, us) in listOf(10 to 100000, 25 to 40000, 50 to 20000, 100 to 10000, 200 to 5000, 0 to 0))
                    Chip(if (hz == 0) "Fastest" else "$hz Hz", dev.rateUs == us) { dev.rateUs = us }
            }
            Spacer(Modifier.height(12.dp))
            val n = dev.selectedMetas().size
            GradientButton(if (n == 0) "Select sensors to record" else "Start recording ($n selected)", Modifier.fillMaxWidth(), enabled = n > 0) { dev.startRecording() }
        } else {
            Row {
                Column(Modifier.weight(1f)) { Text("Samples", color = Muted, fontSize = 12.sp); Text("${st.sampleCount}", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                Column(Modifier.weight(1f)) { Text("Elapsed", color = Muted, fontSize = 12.sp); Text(fmtDuration(st.elapsedMs), fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                Column(Modifier.weight(1f)) { Text("Sensors", color = Muted, fontSize = 12.sp); Text("${st.sensorCount}", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
            }
            Spacer(Modifier.height(12.dp))
            GradientButton("Stop & save", Modifier.fillMaxWidth(), danger = true) { dev.stopRecording() }
        }
        dev.lastResult?.let { r ->
            Spacer(Modifier.height(10.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ok.copy(alpha = .12f)).padding(12.dp)) {
                Text("Saved ${r.files} files · ${r.samples} samples · ${"%.1f".format(r.seconds)} s", color = Ok, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                Text(r.folder, color = Muted, fontSize = 11.5.sp, fontFamily = Mono)
            }
        }
    }
}

@Composable
private fun SensorRow(meta: SensorMeta, values: FloatArray?, history: List<FloatArray>?, selected: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp)).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlassCheckbox(selected, enabled = enabled, onChange = { onToggle() })
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(meta.name, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${meta.typeName} · ${meta.vendor}", color = Faint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (meta.wakeup) Text("wake", color = Faint, fontSize = 10.sp, modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(Track).padding(horizontal = 5.dp, vertical = 1.dp))
        }
        if (values != null) {
            Spacer(Modifier.height(8.dp))
            Row {
                Column(Modifier.weight(1f)) {
                    val shown = meta.axes.take(values.size)
                    Text(shown.mapIndexed { i, ax -> "$ax ${"%.3f".format(values[i])}" }.joinToString("   "),
                        fontFamily = Mono, fontSize = 12.sp, color = Cyan, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (meta.unit.isNotEmpty()) Text(meta.unit, color = Faint, fontSize = 10.5.sp)
                }
                if ((history?.size ?: 0) > 2) SensorSparkline(history!!, Modifier.width(96.dp).height(34.dp))
            }
        }
        Text("range ±${"%.0f".format(meta.maxRange)} · ${if (meta.maxRateHz > 0) "up to ${meta.maxRateHz} Hz" else "on-change"} · ${"%.2f".format(meta.powerMa)} mA",
            color = Faint, fontSize = 10.5.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun SensorSparkline(history: List<FloatArray>, modifier: Modifier) {
    val colors = listOf(Cyan, Violet, Ok)
    Canvas(modifier) {
        val axes = minOf(3, history.firstOrNull()?.size ?: 0)
        if (axes == 0 || history.size < 2) return@Canvas
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        for (s in history) for (a in 0 until minOf(axes, s.size)) { lo = minOf(lo, s[a]); hi = maxOf(hi, s[a]) }
        val span = (hi - lo).coerceAtLeast(1e-3f)
        for (a in 0 until axes) {
            val p = Path()
            history.forEachIndexed { i, s ->
                if (a >= s.size) return@forEachIndexed
                val x = size.width * i / (history.size - 1)
                val y = size.height - (s[a] - lo) / span * size.height
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            drawPath(p, colors[a], style = Stroke(1.5.dp.toPx()))
        }
    }
}

// ------------------------------------------------------------------ privacy

@Composable
private fun PrivacyTab() {
    val ctx = LocalContext.current
    val monitor = remember { PrivacyMonitor(ctx) }
    var tick by remember { mutableLongStateOf(0L) }
    DisposableEffect(Unit) {
        runCatching { monitor.start { tick++ } }
        onDispose { monitor.stop() }
    }
    @Suppress("UNUSED_EXPRESSION") tick
    var log by remember { mutableStateOf(emptyList<app.diskscan.system.Access>()) }
    var canRead by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {  // re-read every 10 s while this tab is open, so new accesses appear
            canRead = app.diskscan.system.AccessLog.hasPermission(ctx)
            log = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { app.diskscan.system.AccessLog.refresh(ctx) }.getOrDefault(log) }
            kotlinx.coroutines.delay(10_000)
        }
    }
    val fmt = remember { java.text.SimpleDateFormat("EEE d MMM, HH:mm:ss", java.util.Locale.getDefault()) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 120.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LiveIndicator("Camera", monitor.cameraInUse, if (monitor.cameraInUse) "In use now" else "Not in use", Modifier.weight(1f))
                LiveIndicator("Microphone", monitor.micInUse, if (monitor.micInUse) monitor.micSources.joinToString(", ").ifEmpty { "In use now" } else "Not in use", Modifier.weight(1f))
            }
        }
        item {
            Glass(Modifier.fillMaxWidth()) {
                Text("Camera & mic access log", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                if (!canRead) Text("Needs one-time permission from a computer:\nadb shell pm grant app.diskscan android.permission.DUMP", color = Muted, fontSize = 13.sp)
                else if (log.isEmpty()) Text("No camera or microphone use recorded.", color = Muted, fontSize = 13.sp)
                else Text("${log.size} accesses. Android keeps the latest per app; this log keeps them all from now on.", color = Muted, fontSize = 12.sp)
            }
        }
        items(log.take(300), key = { "${it.pkg}|${it.op}|${it.timeMs}" }) { a ->
            Glass(Modifier.fillMaxWidth(), padding = 12.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (a.op == "Camera") Bad else Warn))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${a.op} · ${a.state}" + if (a.duration.isNotEmpty()) " · ${a.duration}" else "", color = Muted, fontSize = 12.sp)
                    }
                    Text(fmt.format(java.util.Date(a.timeMs)), color = Faint, fontSize = 12.sp)
                }
            }
        }
        item {
            Glass(Modifier.fillMaxWidth()) {
                Text("How this works", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text("These are the same signals behind Android's green camera and microphone dots. The indicators turn on the moment any app starts using the camera or mic, even in the background.",
                    color = Muted, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                GhostButton("Open Android's privacy dashboard", Modifier.fillMaxWidth()) {
                    runCatching { ctx.startActivity(android.content.Intent("android.settings.PRIVACY_SETTINGS").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
        }
        item {
            Glass(Modifier.fillMaxWidth()) {
                Text("Screen recording", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text("Android does not let any app detect when another app is recording the screen, so no tool can show this honestly. Your own screen recordings and casts show a status-bar indicator; watch for one you didn't start.",
                    color = Muted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun LiveIndicator(title: String, active: Boolean, sub: String, modifier: Modifier) {
    val color = if (active) Bad else Ok
    Glass(modifier, padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(8.dp))
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
        Spacer(Modifier.height(8.dp))
        Text(if (active) "ACTIVE" else "Idle", color = color, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(sub, color = Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

// ------------------------------------------------------------------ network card (used by Monitor)

@Composable
fun NetworkCard(info: NetInfo) {
    Glass(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Connection", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(info.transport, color = if (info.connected) Cyan else Faint, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(8.dp))
        if (info.vpnActive) NetRow("VPN", (info.vpnApps.joinToString(", ").ifEmpty { "active" }) + " — traffic routed through a VPN", Ok)
        info.privateDns?.let { NetRow("Private DNS", it, Ok) }
        info.iface?.let { NetRow("Interface", it) }
        if (info.addresses.isNotEmpty()) NetRow("This device", info.addresses.joinToString(", "))
        if (info.gateways.isNotEmpty()) NetRow("Gateway", info.gateways.joinToString(", "))
        if (info.dns.isNotEmpty()) NetRow("DNS servers", info.dns.joinToString(", "))
        info.proxy?.let { NetRow("Proxy", it, Warn) }
        Spacer(Modifier.height(6.dp))
        Text("Per-app connections with remote server addresses need a local VPN; it would stay on your phone but is a separate feature. Ask to enable it.",
            color = Faint, fontSize = 11.5.sp)
    }
}

@Composable
private fun NetRow(k: String, v: String, valueColor: Color = TextC) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(k, color = Muted, fontSize = 12.5.sp, modifier = Modifier.width(110.dp))
        Text(v, color = valueColor, fontSize = 12.5.sp, fontFamily = Mono, modifier = Modifier.weight(1f))
    }
}
