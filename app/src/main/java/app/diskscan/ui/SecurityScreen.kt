package app.diskscan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.diskscan.system.AppInfo
import app.diskscan.system.Flag
import app.diskscan.system.flagsFor

@Composable
fun SecurityScreen(sys: SystemState, openApp: (AppInfo) -> Unit) {
    LaunchedEffect(Unit) { if (sys.apps == null) sys.refresh() }
    val apps = sys.apps
    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { SectionTitle("Security"); Text("Apps with powerful access, hidden apps and unusual activity", color = Muted, fontSize = 13.sp) }
        if (apps == null) { item { Loading("Checking installed apps…") }; return@LazyColumn }

        val scored = apps.map { it to flagsFor(it, sys.security, sys.usage[it.pkg]) }.filter { it.second.isNotEmpty() }
        val risky = scored.filter { (_, f) -> f.sumOf { it.weight } >= 5 }.sortedByDescending { (_, f) -> f.sumOf { it.weight } }
        item {
            Glass(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (risky.isEmpty()) "Nothing stands out" else "${risky.size} app${if (risky.size == 1) "" else "s"} worth a look",
                            fontSize = 20.sp, fontWeight = FontWeight.Bold, color = if (risky.isEmpty()) Ok else Warn)
                        Text("Checked ${apps.size} apps. A flag isn't proof of anything; it shows where an app has power over your data.",
                            color = Muted, fontSize = 12.5.sp)
                    }
                }
            }
        }
        if (!sys.usageAccess) item { UsageAccessCard(sys, "Also check which apps were busy in the background today.") }
        if (risky.isNotEmpty()) {
            item { Text("Most access", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.padding(top = 6.dp)) }
            items(risky, key = { "r" + it.first.pkg }) { (a, f) -> FlaggedRow(a, f.joinToString(" · ") { it.title }, openApp) }
        }
        for (flag in Flag.entries) {
            val hits = scored.filter { flag in it.second }.map { it.first }
            if (hits.isEmpty()) continue
            item {
                Column(Modifier.padding(top = 10.dp)) {
                    Text("${flag.title} (${hits.size})", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text(flag.detail, color = Faint, fontSize = 12.5.sp)
                }
            }
            items(hits, key = { flag.name + it.pkg }) { a -> FlaggedRow(a, if (a.system) "system app" else a.pkg, openApp) }
        }
    }
}

@Composable
private fun FlaggedRow(a: AppInfo, sub: String, openApp: (AppInfo) -> Unit) {
    Row(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp)).clickable { openApp(a) }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(a.pkg, 38.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(a.label, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, color = Faint, fontSize = 11.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text("›", color = Faint, fontSize = 20.sp, modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Track).padding(horizontal = 8.dp))
    }
}
