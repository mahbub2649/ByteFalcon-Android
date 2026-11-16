package app.diskscan.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.diskscan.fmtBytes
import app.diskscan.fmtNum

@Composable
fun PermissionScreen(onGrant: () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Glass(padding = 26.dp) {
            BrandText("ByteFalcon", 34.sp)
            Spacer(Modifier.height(6.dp))
            Text("See what's eating your storage, and clean it up safely.", color = Muted, fontSize = 16.sp)
            Spacer(Modifier.height(22.dp))
            Text("One permission needed", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Spacer(Modifier.height(8.dp))
            for (line in listOf(
                "\"All files access\" lets the native scanner read every folder in shared storage at full speed.",
                "Nothing leaves your phone. ByteFalcon has no internet permission.",
                "Nothing is deleted until you review it and confirm.",
            )) Row(Modifier.padding(vertical = 5.dp)) {
                Box(Modifier.padding(top = 7.dp).size(6.dp).clip(CircleShape).background(Brand))
                Spacer(Modifier.width(12.dp))
                Text(line, color = Muted, fontSize = 14.sp)
            }
            Spacer(Modifier.height(24.dp))
            GradientButton("Allow all files access", Modifier.fillMaxWidth(), onClick = onGrant)
            Spacer(Modifier.height(10.dp))
            Text("Turn on the switch for ByteFalcon, then come back.", color = Faint, fontSize = 12.5.sp,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun HomeScreen(state: AppState) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(18.dp)) {
        Spacer(Modifier.height(18.dp))
        Text("See what's eating", fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-.8).sp, lineHeight = 36.sp)
        BrandText("your storage.", 32.sp)
        Spacer(Modifier.height(10.dp))
        Text("A native scanner reads thousands of files a second, finds caches, leftovers and duplicates, and cleans them up safely.",
            color = Muted, fontSize = 14.5.sp)
        Spacer(Modifier.height(26.dp))

        for (v in state.volumes) {
            val used = (v.total - v.free).toFloat() / maxOf(v.total, 1)
            Glass(Modifier.fillMaxWidth().padding(bottom = 12.dp), onClick = { state.startScan(v.path) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    UsageRing(used)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(v.label, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                        Text("${fmtBytes(v.free)} free of ${fmtBytes(v.total)}", color = if (v.free < v.total / 10) Bad else Muted, fontSize = 13.5.sp)
                        Text(v.path, color = Faint, fontSize = 11.5.sp, fontFamily = Mono)
                    }
                    GradientButton("Scan") { state.startScan(v.path) }
                }
            }
        }

        val root = state.volumes.firstOrNull()?.path
        if (root != null) {
            Spacer(Modifier.height(10.dp))
            Text("QUICK SCAN", color = Faint, fontSize = 11.5.sp, letterSpacing = 1.sp)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((label, rel) in listOf("Download" to "/Download", "Camera" to "/DCIM", "Pictures" to "/Pictures",
                    "WhatsApp" to "/Android/media/com.whatsapp", "Movies" to "/Movies", "Music" to "/Music")) {
                    Chip(label, false) { state.startScan(root + rel) }
                }
            }
        }
        if (state.volumes.isEmpty()) Loading("Looking for storage…")
    }
}

@Composable
fun ScanningScreen(state: AppState) {
    BackHandler { state.cancelScan() }
    val p = state.progress
    val files = p.optLong("files")
    val dirs = p.optLong("dirs")
    val secs = p.optDouble("seconds", 0.0).let { if (it.isNaN()) 0.0 else it }
    val t = rememberInfiniteTransition(label = "orb")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "angle")

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val w = 7.dp.toPx()
                drawArc(Color(0x14FFFFFF), 0f, 360f, false, style = Stroke(w))
                drawArc(Brush.sweepGradient(listOf(Color.Transparent, Violet, Cyan)), angle, 120f, false, style = Stroke(w, cap = StrokeCap.Round))
            }
            Box(Modifier.size(170.dp).clip(CircleShape).background(Brush.radialGradient(listOf(Violet.copy(alpha = .3f), Color.Transparent))))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(fmtNum(files), fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp)
                Text("files", color = Muted, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(28.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Folders", fmtNum(dirs), "scanned", Modifier.weight(1f))
            StatTile("Found", fmtBytes(p.optLong("bytes")), "on disk", Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Speed", fmtNum(((files + dirs) / maxOf(secs, 0.05)).toLong()) + "/s", "entries", Modifier.weight(1f))
            StatTile("Elapsed", "%.1f s".format(secs), "so far", Modifier.weight(1f))
        }
        Spacer(Modifier.height(18.dp))
        Text(p.optString("current"), color = Faint, fontSize = 12.sp, fontFamily = Mono, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Spacer(Modifier.height(22.dp))
        GhostButton("Cancel") { state.cancelScan() }
    }
}
