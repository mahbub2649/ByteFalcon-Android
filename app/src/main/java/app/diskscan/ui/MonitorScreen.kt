package app.diskscan.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.diskscan.fmtBytes
import app.diskscan.system.Vitals
import app.diskscan.system.closeBackgroundApps
import app.diskscan.system.dataUsage
import app.diskscan.system.sampleVitals
import app.diskscan.system.thermalLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val History = 60

@Composable
fun MonitorScreen(sys: SystemState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var v by remember { mutableStateOf<Vitals?>(null) }
    val down = remember { mutableStateListOf<Float>() }
    val up = remember { mutableStateListOf<Float>() }
    var cleanResult by remember { mutableStateOf<String?>(null) }
    var cleaning by remember { mutableStateOf(false) }
    var days by remember { mutableIntStateOf(1) }
    var usage by remember { mutableStateOf<List<Triple<String, String, Pair<Long, Long>>>?>(null) }

    LaunchedEffect(Unit) {
        if (sys.apps == null) sys.refresh()
        var last: Vitals? = null
        while (true) {
            val s = io { sampleVitals(ctx) }
            last?.let { p ->
                val dt = ((s.at - p.at).coerceAtLeast(1)) / 1000f
                down.add(((s.rx - p.rx).coerceAtLeast(0) / dt)); up.add(((s.tx - p.tx).coerceAtLeast(0) / dt))
                while (down.size > History) down.removeAt(0)
                while (up.size > History) up.removeAt(0)
            }
            last = s
            v = s
            delay(1000)
        }
    }
    LaunchedEffect(days, sys.usageAccess, sys.apps) {
        if (!sys.usageAccess || sys.apps == null) return@LaunchedEffect
        usage = null
        val byUid = sys.byUid()
        usage = io {
            dataUsage(ctx, System.currentTimeMillis() - days * 86_400_000L).entries
                .filter { it.value.first + it.value.second > 0 }
                .map { (uid, bytes) ->
                    val app = byUid[uid]
                    val label = app?.label ?: when (uid) { -4 -> "Removed apps"; -5 -> "Hotspot & tethering"; 1000 -> "Android system"; else -> "System (uid $uid)" }
                    Triple(app?.pkg ?: "", label, bytes)
                }.sortedByDescending { it.third.first + it.third.second }
        }
    }

    val s = v
    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SectionTitle("Monitor"); Text("Live, updated every second", color = Muted, fontSize = 13.sp) }
        if (s == null) { item { Loading("Reading sensors…") }; return@LazyColumn }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val usedFrac = 1f - s.memAvail.toFloat() / s.memTotal
                Glass(Modifier.weight(1f), padding = 14.dp) {
                    Text("Memory", color = Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    UsageRing(usedFrac, 70.dp)
                    Spacer(Modifier.height(8.dp))
                    Text("${fmtBytes(s.memTotal - s.memAvail)} used", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text("${fmtBytes(s.memAvail)} free of ${fmtBytes(s.memTotal)}", color = Faint, fontSize = 11.5.sp)
                    if (s.lowMemory) Text("Low memory", color = Bad, fontSize = 12.sp)
                }
                Glass(Modifier.weight(1f), padding = 14.dp) {
                    Text("Battery", color = Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    UsageRing(1f - s.battery / 100f, 70.dp)  // ring shows what's used, so it turns red when the battery is low
                    Spacer(Modifier.height(8.dp))
                    Text("${s.battery}% · ${"%.1f".format(s.batteryTempC)} °C", fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                        color = if (s.batteryTempC >= 42) Bad else TextC)
                    Text("${if (s.charging) "Charging" else "On battery"} · ${s.currentMa} mA · ${"%.2f".format(s.voltageMv / 1000f)} V",
                        color = Faint, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        item {
            Glass(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Processor", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Text(thermalLabel(s.thermal), color = if (s.thermal >= 3) Bad else if (s.thermal >= 1) Warn else Ok, fontSize = 13.sp)
                }
                Spacer(Modifier.height(10.dp))
                if (s.coreLoad.isEmpty()) Text("This phone hides CPU frequencies from apps.", color = Faint, fontSize = 12.5.sp)
                else {
                    Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                        s.coreLoad.forEach { f ->
                            Box(
                                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(Track),
                                contentAlignment = Alignment.BottomCenter,
                            ) {
                                Box(Modifier.fillMaxWidth().fillMaxHeight(f.coerceAtLeast(.04f))
                                    .background(Brush.verticalGradient(listOf(Cyan, Violet))))
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Clock speed per core, as a share of its maximum · average ${"%.0f".format(s.coreLoad.average() * 100)}%",
                        color = Faint, fontSize = 11.5.sp)
                }
            }
        }
        item {
            Glass(Modifier.fillMaxWidth()) {
                Text("Network", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Spacer(Modifier.height(10.dp))
                Row {
                    Column(Modifier.weight(1f)) {
                        Text("↓ ${fmtBytes((down.lastOrNull() ?: 0f).toLong())}/s", color = Cyan, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("download", color = Faint, fontSize = 11.5.sp)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("↑ ${fmtBytes((up.lastOrNull() ?: 0f).toLong())}/s", color = Violet, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("upload", color = Faint, fontSize = 11.5.sp)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Sparkline(down, up, Modifier.fillMaxWidth().height(80.dp))
                Spacer(Modifier.height(6.dp))
                Text("Since boot: ${fmtBytes(s.rx)} down · ${fmtBytes(s.tx)} up", color = Faint, fontSize = 11.5.sp)
            }
        }
        item {
            val net by produceState(initialValue = null as app.diskscan.system.NetInfo?, days) {
                // A failing system query must never take the whole app down.
                value = runCatching { io { app.diskscan.system.networkInfo(ctx) } }.getOrNull()
            }
            net?.let { NetworkCard(it) }
        }
        item {
            Glass(Modifier.fillMaxWidth()) {
                Text("Free up memory", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text("Stops other apps' background processes. Android manages memory well on its own and restarts what it needs, so the gain is usually temporary; use it when the phone feels sluggish.",
                    color = Muted, fontSize = 12.5.sp)
                Spacer(Modifier.height(12.dp))
                GradientButton(if (cleaning) "Closing…" else "Close background apps", Modifier.fillMaxWidth(), enabled = !cleaning && sys.apps != null) {
                    cleaning = true
                    scope.launch {
                        val before = io { sampleVitals(ctx).memAvail }
                        val n = io { closeBackgroundApps(ctx, sys.apps.orEmpty()) }
                        delay(1500)
                        val gained = io { sampleVitals(ctx).memAvail } - before
                        cleanResult = if (gained > 0) "Asked $n apps to stop · ${fmtBytes(gained)} of memory freed"
                                      else "Asked $n apps to stop · nothing to free right now; Android had already cleaned up"
                        cleaning = false
                    }
                }
                cleanResult?.let { Spacer(Modifier.height(8.dp)); Text(it, color = Ok, fontSize = 13.sp) }
            }
        }
        item {
            Column {
                Spacer(Modifier.height(4.dp))
                Text("Data used by apps", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((d, label) in listOf(1 to "Today", 7 to "7 days", 30 to "30 days")) Chip(label, days == d) { days = d }
                }
            }
        }
        if (!sys.usageAccess) item { UsageAccessCard(sys, "Android only shares how much data each app used with apps you allow.") }
        else {
            val list = usage
            if (list == null) item { Loading("Adding up data usage…") }
            else {
                val max = list.maxOfOrNull { it.third.first + it.third.second } ?: 1
                items(list.take(60)) { (pkg, label, bytes) ->
                    Row(Modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (pkg.isNotEmpty()) AppIcon(pkg, 38.dp) else Box(Modifier.width(38.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(label, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("Wi-Fi ${fmtBytes(bytes.first)} · Mobile ${fmtBytes(bytes.second)}", color = Faint, fontSize = 11.5.sp)
                            Spacer(Modifier.height(6.dp))
                            SizeBar((bytes.first + bytes.second).toFloat() / max, 190f, Modifier.fillMaxWidth(.92f), 4.dp)
                        }
                        Text(fmtBytes(bytes.first + bytes.second), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    }
                }
                if (list.isEmpty()) item { Note("No data used in this period.") }
            }
        }
    }
}

@Composable
private fun Sparkline(down: List<Float>, up: List<Float>, modifier: Modifier) {
    Canvas(modifier) {
        val max = (down + up).maxOrNull()?.coerceAtLeast(1024f) ?: 1024f
        fun line(values: List<Float>, color: Color) {
            if (values.size < 2) return
            val step = size.width / (History - 1)
            val x0 = size.width - (values.size - 1) * step
            val p = Path()
            values.forEachIndexed { i, value ->
                val pt = Offset(x0 + i * step, size.height - value / max * size.height * .92f)
                if (i == 0) p.moveTo(pt.x, pt.y) else p.lineTo(pt.x, pt.y)
            }
            val fill = Path().apply { addPath(p); lineTo(size.width, size.height); lineTo(x0, size.height); close() }
            drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = .28f), Color.Transparent)))
            drawPath(p, color, style = Stroke(2.dp.toPx()))
        }
        line(up, Violet)
        line(down, Cyan)
    }
}
