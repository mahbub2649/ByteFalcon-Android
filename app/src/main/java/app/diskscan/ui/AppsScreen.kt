package app.diskscan.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.diskscan.ago
import app.diskscan.fmtBytes
import app.diskscan.system.AppInfo
import app.diskscan.system.CommonlyRemoved
import app.diskscan.system.PermGroup
import app.diskscan.system.SpecialAccess
import app.diskscan.system.appStorage
import app.diskscan.system.dataUsage
import app.diskscan.system.flagsFor
import app.diskscan.system.installerLabel
import java.text.DateFormat
import java.util.Date

private enum class AppFilter(val label: String) { User("Installed"), System("System"), Removable("Commonly removed"), Disabled("Disabled"), All("All") }
private enum class AppSort(val label: String) { Name("Name"), Recent("Recently used"), Permissions("Most access") }

@Composable
fun AppsScreen(sys: SystemState) {
    LaunchedEffect(Unit) { if (sys.apps == null) sys.refresh() }
    sys.selected?.let { AppDetail(sys, it); return }
    var filter by remember { mutableStateOf(AppFilter.User) }
    var sort by remember { mutableStateOf(AppSort.Name) }
    var query by remember { mutableStateOf("") }
    val apps = sys.apps

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            SectionTitle("Apps")
            Text("${apps?.count { !it.system } ?: 0} installed · ${apps?.count { it.system } ?: 0} system", color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().glass(RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 12.dp)) {
                if (query.isEmpty()) Text("Search apps or package names", color = Faint, fontSize = 14.sp)
                BasicTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                    textStyle = TextStyle(color = TextC, fontSize = 14.sp), cursorBrush = SolidColor(Cyan))
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (f in AppFilter.entries) Chip(f.label, filter == f) { filter = f }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (s in AppSort.entries) Chip("Sort: ${s.label}", sort == s) { sort = s }
            }
        }
        if (apps == null) { item { Loading("Reading installed apps…") }; return@LazyColumn }
        if (filter == AppFilter.Removable) item {
            Note("Preinstalled apps that people commonly remove with no side effects. Removing system apps needs a computer; open an app to see how.")
        }
        val q = query.trim().lowercase()
        val shown = apps.filter {
            when (filter) {
                AppFilter.User -> !it.system
                AppFilter.System -> it.system
                AppFilter.Removable -> it.system && it.pkg in CommonlyRemoved
                AppFilter.Disabled -> !it.enabled
                AppFilter.All -> true
            } && (q.isEmpty() || q in it.label.lowercase() || q in it.pkg)
        }.let { list ->
            when (sort) {
                AppSort.Name -> list
                AppSort.Recent -> list.sortedByDescending { sys.usage[it.pkg]?.lastUsed ?: 0 }
                AppSort.Permissions -> list.sortedByDescending { a -> a.groups.count { it.sensitive } * 10 + a.groups.size }
            }
        }
        items(shown, key = { it.pkg }) { a -> AppRow(sys, a) }
        if (shown.isEmpty()) item { Note("No apps match.") }
    }
}

@Composable
private fun AppRow(sys: SystemState, a: AppInfo) {
    val flags = flagsFor(a, sys.security, sys.usage[a.pkg])
    Row(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp)).clickable { sys.selected = a }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(a.pkg)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(a.label, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(a.pkg, color = Faint, fontSize = 11.sp, fontFamily = Mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val groups = a.groups.filter { it.sensitive }
            if (groups.isNotEmpty() || flags.isNotEmpty()) Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (g in groups) PermDot(g)
                if (flags.isNotEmpty()) Text(
                    "${flags.size} flag${if (flags.size == 1) "" else "s"}", color = Warn, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Warn.copy(alpha = .14f)).padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            sys.usage[a.pkg]?.lastUsed?.takeIf { it > 0 }?.let { Text(ago(it / 1000), color = Muted, fontSize = 11.5.sp) }
            if (!a.enabled) Text("disabled", color = Faint, fontSize = 11.sp)
            else if (a.system) Text("system", color = Faint, fontSize = 11.sp)
        }
    }
}

@Composable
private fun PermDot(g: PermGroup) {
    val h = when (g) {
        PermGroup.CAMERA -> 330f; PermGroup.MICROPHONE -> 15f; PermGroup.LOCATION -> 145f; PermGroup.SMS -> 200f
        PermGroup.CONTACTS -> 45f; PermGroup.CALLS -> 275f; else -> 230f
    }
    Box(Modifier.size(20.dp).clip(RoundedCornerShape(6.dp)).background(hue(h, .7f, .55f, .2f)), contentAlignment = Alignment.Center) {
        Text(g.letter, color = hue(h, .9f, .75f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AppDetail(sys: SystemState, a: AppInfo) {
    BackHandler { sys.selected = null }
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val storage by produceState<app.diskscan.system.AppStorage?>(null, a.pkg, sys.usageAccess) {
        if (sys.usageAccess) value = io { appStorage(ctx, a.pkg) }
    }
    val data by produceState<Pair<Long, Long>?>(null, a.uid, sys.usageAccess) {
        if (sys.usageAccess) value = io { dataUsage(ctx, System.currentTimeMillis() - 30 * 86_400_000L)[a.uid] ?: (0L to 0L) }
    }
    val usage = sys.usage[a.pkg]
    val flags = flagsFor(a, sys.security, usage)
    val fmt = DateFormat.getDateInstance(DateFormat.MEDIUM)

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("‹ Apps", color = Cyan, fontSize = 15.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { sys.selected = null }.padding(vertical = 6.dp))
        }
        item {
            Glass(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(a.pkg, 56.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.label, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(a.pkg, color = Faint, fontSize = 11.5.sp, fontFamily = Mono)
                        Text("Version ${a.version.ifEmpty { "?" }} · ${if (a.system) "system app" else installerLabel(a.installer, a.pkg)}", color = Muted, fontSize = 12.5.sp)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (a.launchable) GhostButton("Open", Modifier.weight(1f)) { sys.launch(a.pkg) }
                    GhostButton("Permissions", Modifier.weight(1f)) { sys.appSettings(a.pkg) }
                    if (!a.system || a.updatedSystem) GradientButton(if (a.system) "Remove updates" else "Uninstall", Modifier.weight(1.2f), danger = true) { sys.uninstall(a.pkg) }
                }
            }
        }
        if (flags.isNotEmpty()) item {
            Glass(Modifier.fillMaxWidth()) {
                Text("Worth knowing", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Warn)
                for (f in flags) Column(Modifier.padding(top = 8.dp)) {
                    Text(f.title, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    Text(f.detail, color = Muted, fontSize = 12.5.sp)
                }
            }
        }
        item {
            Glass(Modifier.fillMaxWidth()) {
                Text("Allowed to use", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                val groups = a.groups
                if (groups.isEmpty()) Text("No sensitive permissions.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                for (g in groups) Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PermDot(g)
                    Spacer(Modifier.width(10.dp))
                    Text(g.label + if (g == PermGroup.LOCATION && "android.permission.ACCESS_BACKGROUND_LOCATION" in a.granted) " (all the time)" else "",
                        fontSize = 14.sp, color = if (g.sensitive) TextC else Muted)
                }
                val special = SpecialAccess.filterKeys { it in a.requested }.values
                if (special.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("Can also request", color = Muted, fontSize = 12.5.sp)
                    Text(special.joinToString(" · "), color = Faint, fontSize = 12.5.sp)
                }
                Spacer(Modifier.height(10.dp))
                Text("Change these in Android's settings →", color = Cyan, fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { sys.appSettings(a.pkg) }.padding(vertical = 4.dp))
            }
        }
        item {
            Glass(Modifier.fillMaxWidth()) {
                Text("Activity & storage", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Spacer(Modifier.height(6.dp))
                if (!sys.usageAccess) Text("Allow usage access (Monitor or Security tab) to see storage, data and activity.", color = Muted, fontSize = 13.sp)
                else {
                    Fact("Last used", usage?.lastUsed?.takeIf { it > 0 }?.let { ago(it / 1000) } ?: "not in the last day")
                    Fact("On screen today", fmtDuration(usage?.foregroundMs ?: 0))
                    Fact("Background service today", fmtDuration(usage?.fgServiceMs ?: 0))
                    storage?.let { Fact("App size", fmtBytes(it.app)); Fact("Data", fmtBytes(it.data)); Fact("Cache", fmtBytes(it.cache)) }
                    data?.let { Fact("Data used (30 days)", "Wi-Fi ${fmtBytes(it.first)} · Mobile ${fmtBytes(it.second)}") }
                }
                Fact("Installed", fmt.format(Date(a.installed)))
                Fact("Updated", fmt.format(Date(a.updated)))
                Fact("Built for Android API", "${a.targetSdk}")
            }
        }
        if (a.system) item { BloatwareCard(a) { clipboard.setText(AnnotatedString(it)); sys.toast("Command copied") } }
    }
}

@Composable
private fun Fact(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(k, color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(v, fontSize = 13.sp)
    }
}

@Composable
private fun BloatwareCard(a: AppInfo, copy: (String) -> Unit) {
    val remove = "adb shell pm uninstall -k --user 0 ${a.pkg}"
    val restore = "adb shell cmd package install-existing ${a.pkg}"
    Glass(Modifier.fillMaxWidth()) {
        Text("Remove this system app", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Text(
            if (a.pkg in CommonlyRemoved) "People commonly remove this one without side effects."
            else "System apps can be essential. Look the package name up before removing it; removing the wrong one can break calls, the launcher or updates.",
            color = if (a.pkg in CommonlyRemoved) Ok else Warn, fontSize = 12.5.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text("No app can remove system apps without root. From a computer with USB debugging, this removes it for your user (reversible, survives reboots):",
            color = Muted, fontSize = 12.5.sp)
        CommandRow(remove, copy)
        Text("To bring it back:", color = Muted, fontSize = 12.5.sp)
        CommandRow(restore, copy)
    }
}

@Composable
private fun CommandRow(cmd: String, copy: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(10.dp)).background(Track).clickable { copy(cmd) }.padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(cmd, fontFamily = Mono, fontSize = 11.5.sp, color = Cyan, modifier = Modifier.weight(1f))
        Text("Copy", color = Muted, fontSize = 12.sp)
    }
}
