package app.diskscan.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.diskscan.Engine
import app.diskscan.Entry
import app.diskscan.Kind
import app.diskscan.SmartCat
import app.diskscan.TreeNode
import app.diskscan.ago
import app.diskscan.entries
import app.diskscan.fmtBytes
import app.diskscan.fmtNum
import app.diskscan.fmtPct
import app.diskscan.pick
import app.diskscan.toTree
import kotlinx.coroutines.launch
import org.json.JSONObject

private val ListPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 120.dp)

// ------------------------------------------------------------------ shared row

@Composable
fun FileRow(
    e: Entry,
    state: AppState,
    max: Long,
    subtitle: String,
    safe: Boolean = false,
    base: String = "",
    group: List<Int>? = null,
    onClick: (() -> Unit)? = null,
) {
    val checked = state.basket.containsKey(e.id)
    // Default tap: files open in their app, folders open in the Explorer.
    val tap = onClick ?: when {
        !e.isDir -> ({ state.open(e.id) })
        !e.mount -> ({ state.browse(e.id) })
        else -> null
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).glass(RoundedCornerShape(16.dp))
            .clickable(enabled = tap != null) { tap?.invoke() }.padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassCheckbox(checked, enabled = !e.mount) { on -> state.toggle(e.pick(safe, base), on, group) }
        Spacer(Modifier.width(10.dp))
        KindBadge(e)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.name, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) Text(subtitle, color = Faint, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            SizeBar(e.size.toFloat() / maxOf(max, 1), if (e.isDir) 262f else Kind.of(e).hue, Modifier.fillMaxWidth(.92f), 4.dp)
        }
        Spacer(Modifier.width(10.dp))
        Text(fmtBytes(e.size), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

private fun LazyListScope.header(title: String, sub: String = "") = item {
    Column(Modifier.padding(top = 4.dp, bottom = 10.dp)) {
        SectionTitle(title)
        if (sub.isNotEmpty()) Text(sub, color = Muted, fontSize = 13.sp)
    }
}

// ------------------------------------------------------------------ overview

@Composable
fun OverviewTab(state: AppState) {
    val s = state.summary ?: return
    val smart = state.smart
    LazyColumn(Modifier.fillMaxSize(), contentPadding = ListPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("Scanned", fmtBytes(s.size), "${fmtNum(s.dirs)} folders", Modifier.weight(1f))
                    StatTile("Files", fmtNum(s.files), if (s.errors > 0) "${fmtNum(s.errors)} unreadable" else "all readable", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("Speed", fmtNum(((s.files + s.dirs) / maxOf(s.seconds, 0.001)).toLong()) + "/s",
                        "${"%.2f".format(s.seconds)} s · ${s.threads} threads", Modifier.weight(1f))
                    StatTile("Free", fmtBytes(s.diskFree), "of ${fmtBytes(s.diskTotal)}", Modifier.weight(1f),
                        valueColor = if (s.diskFree < s.diskTotal / 10) Bad else TextC)
                }
            }
        }
        item {
            Glass(Modifier.fillMaxWidth()) {
                Text("Storage", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Spacer(Modifier.height(12.dp))
                val used = s.diskTotal - s.diskFree
                val here = minOf(s.size, used)
                val safe = minOf(smart?.safeTotal ?: 0, here)
                val other = maxOf(0, used - here)
                Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(99.dp)).background(Track)) {
                    if (here - safe > 0) Box(Modifier.weight((here - safe).toFloat()).fillMaxHeight().background(Brush.horizontalGradient(listOf(Violet, Indigo))))
                    if (safe > 0) Box(Modifier.weight(safe.toFloat()).fillMaxHeight().background(Ok))
                    if (other > 0) Box(Modifier.weight(other.toFloat()).fillMaxHeight().background(Color(0x38FFFFFF)))
                    if (s.diskFree > 0) Box(Modifier.weight(s.diskFree.toFloat()).fillMaxHeight())
                }
                Spacer(Modifier.height(10.dp))
                Legend(Indigo, "Scanned ${fmtBytes(here)}")
                Legend(Ok, "Safe to free ${if (smart != null) fmtBytes(safe) else "…"}")
                Legend(Color(0x66FFFFFF), "Apps & system ${fmtBytes(other)}")
                Legend(Track, "Free ${fmtBytes(s.diskFree)}")
                if (smart != null && smart.safeTotal > 0) {
                    Spacer(Modifier.height(14.dp))
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Ok.copy(alpha = .12f)).padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(fmtBytes(smart.safeTotal), color = Ok, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Text("can be freed safely", color = Muted, fontSize = 12.5.sp)
                        }
                        GradientButton("Review") { state.tab = Tab.Smart }
                    }
                }
            }
        }
        item {
            Glass(Modifier.fillMaxWidth()) {
                Text("Biggest items", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Spacer(Modifier.height(8.dp))
                val max = s.top.maxOfOrNull { it.size } ?: 1
                s.top.filter { it.size > 0 }.forEachIndexed { i, e ->
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                            .clickable { if (e.isDir) state.browse(e.id) else state.open(e.id) }.padding(vertical = 7.dp),
                    ) {
                        Row {
                            Text(e.name + if (e.isDir) "/" else "", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                            Text(fmtBytes(e.size), color = Muted, fontSize = 13.5.sp)
                        }
                        Spacer(Modifier.height(5.dp))
                        SizeBar(e.size.toFloat() / maxOf(max, 1), (i * 37f + 250f) % 360f, Modifier.fillMaxWidth())
                    }
                }
            }
        }
        if (smart != null && smart.cats.isNotEmpty()) item {
            Glass(Modifier.fillMaxWidth()) {
                Text("Smart suggestions", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Spacer(Modifier.height(6.dp))
                for (c in smart.cats.take(5)) Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    SafetyPill(c.safety)
                    Spacer(Modifier.width(10.dp))
                    Text(c.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                    Text(fmtBytes(c.total), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Spacer(Modifier.width(8.dp))
                    GhostButton("Select") { selectCategory(state, c) }
                }
            }
        }
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Spacer(Modifier.width(8.dp))
        Text(text, color = Muted, fontSize = 12.5.sp)
    }
}

private fun selectCategory(state: AppState, c: SmartCat) {
    state.select(c.items.filter { !it.mount }.map { it.pick(c.safe) })
    state.toast("Selected ${c.items.size} item${if (c.items.size == 1) "" else "s"} from ${c.title}")
}

// ------------------------------------------------------------------ explorer

@Composable
fun ExplorerTab(state: AppState) {
    var node by remember { mutableStateOf<JSONObject?>(null) }
    var tree by remember { mutableStateOf<TreeNode?>(null) }
    val listState = rememberLazyListState()
    LaunchedEffect(state.dirId, state.version) {
        val id = state.dirId
        val (n, t) = io { Engine.get("node", "id=$id") to Engine.get("tree", "id=$id").toTree() }
        node = n
        tree = t
        state.dirId = n.getJSONObject("node").optInt("id")  // the engine falls back to the root if id vanished
        listState.scrollToItem(0)
    }
    val n = node ?: return Loading("Loading…")
    val crumbs = n.optJSONArray("crumbs")?.let { a -> List(a.length()) { a.getJSONObject(it) } } ?: emptyList()
    BackHandler(enabled = crumbs.size > 1) { state.dirId = crumbs[crumbs.size - 2].optInt("id") }
    val self = n.getJSONObject("node")
    val path = self.optString("path")
    val dirSize = self.optLong("size").coerceAtLeast(1)
    val children = n.optJSONArray("children").entries()
    val max = children.maxOfOrNull { it.size } ?: 1

    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = ListPadding) {
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                crumbs.forEachIndexed { i, c ->
                    if (i > 0) Text(" / ", color = Faint)
                    Text(
                        c.optString("name"), color = if (i == crumbs.lastIndex) TextC else Muted,
                        fontWeight = if (i == crumbs.lastIndex) FontWeight.SemiBold else FontWeight.Normal, fontSize = 14.sp,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { state.dirId = c.optInt("id") }.padding(6.dp),
                    )
                }
            }
        }
        item {
            val t = tree
            if (t != null && t.children.isNotEmpty()) {
                Treemap(
                    t, Modifier.fillMaxWidth().height(300.dp).glass(RoundedCornerShape(18.dp)).padding(3.dp),
                    onOpen = { state.dirId = it },
                    onFile = { id -> state.open(id) },
                )
                Spacer(Modifier.height(10.dp))
            }
        }
        items(children, key = { it.id }) { e ->
            val share = fmtPct(e.size.toDouble() / dirSize)
            FileRow(
                e, state, max,
                subtitle = if (e.isDir) "$share · ${fmtNum(e.files.toLong())} files" + (if (e.mount) " · other drive" else "") else "$share · ${ago(e.mtime)}",
                base = path, onClick = if (e.isDir && !e.mount) ({ state.dirId = e.id }) else null,
            )
        }
        if (children.isEmpty()) item { Note("This folder is empty.") }
        n.optInt("restCount").takeIf { it > 0 }?.let { item { Note("+ ${fmtNum(it.toLong())} more items · ${fmtBytes(n.optLong("restSize"))}") } }
    }
}

// ------------------------------------------------------------------ smart clean

@Composable
fun SmartTab(state: AppState, onClearAppCaches: () -> Unit) {
    val smart = state.smart ?: return Loading("Analyzing your files…")
    var open by remember { mutableStateOf(setOf<String>()) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = ListPadding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Column(Modifier.padding(bottom = 4.dp)) {
                SectionTitle("Smart Clean")
                Text("${fmtBytes(smart.safeTotal)} safe to free", color = Muted, fontSize = 13.sp)
                val safeCats = smart.cats.filter { it.safe }
                if (safeCats.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    GradientButton("Select everything safe", Modifier.fillMaxWidth()) { safeCats.forEach { selectCategory(state, it) } }
                }
            }
        }
        item {
            Glass(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CatIcon("A", 200f)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Other apps' caches", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Text("Android keeps each app's cache private. It can clear all of them at once; apps keep working and rebuild what they need.",
                            color = Muted, fontSize = 12.5.sp)
                    }
                }
                Spacer(Modifier.height(12.dp))
                GhostButton("Clear all app caches", Modifier.fillMaxWidth(), onClick = onClearAppCaches)
            }
        }
        items(smart.cats, key = { it.id }) { c ->
            val expanded = c.id in open
            Glass(Modifier.fillMaxWidth(), padding = 0.dp) {
                Column(Modifier.clickable { open = if (expanded) open - c.id else open + c.id }.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CatIcon(c.title.take(1), catHue(c.id))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(c.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f, fill = false))
                                Spacer(Modifier.width(8.dp))
                                SafetyPill(c.safety)
                            }
                            Text("${fmtBytes(c.total)} · ${fmtNum(c.count.toLong())} item${if (c.count == 1) "" else "s"}", color = Muted, fontSize = 12.5.sp)
                        }
                        GhostButton("Select") { selectCategory(state, c) }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(c.description, color = Faint, fontSize = 12.5.sp)
                    Text(if (expanded) "Hide items ▴" else "Show items ▾", color = Cyan, fontSize = 12.5.sp, modifier = Modifier.padding(top = 8.dp))
                }
                if (expanded) Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
                    val max = c.items.maxOfOrNull { it.size } ?: 1
                    for (e in c.items.take(100)) FileRow(e, state, max, e.path, safe = c.safe)
                    if (c.count > 100) Note("Showing the largest 100 of ${fmtNum(c.count.toLong())}. \"Select\" includes the largest ${c.items.size}.")
                }
            }
        }
        if (smart.cats.isEmpty()) item { Note("Nothing obvious to clean here.") }
    }
}

@Composable
private fun CatIcon(letter: String, h: Float) {
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(hue(h, .7f, .55f, .2f)), contentAlignment = Alignment.Center) {
        Text(letter, color = hue(h, .9f, .75f), fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

private fun catHue(id: String) = when (id) {
    "trash" -> 0f; "thumbs" -> 275f; "mediacache" -> 265f; "partial" -> 190f; "installers" -> 38f
    "olddownloads" -> 300f; "whatsapp" -> 145f; "telegram" -> 205f; "screenshots" -> 230f; else -> 15f
}

// ------------------------------------------------------------------ largest

@Composable
fun LargestTab(state: AppState) {
    var data by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(state.version) { data = io { Engine.get("largest", "limit=500") } }
    val d = data ?: return Loading("Finding the largest files…")
    val all = d.optJSONArray("items").entries()
    val counts = all.groupingBy { Kind.of(it) }.eachCount()
    val items = state.largestKind?.let { k -> all.filter { Kind.of(it) == k } } ?: all
    val max = items.maxOfOrNull { it.size } ?: 1
    LazyColumn(Modifier.fillMaxSize(), contentPadding = ListPadding) {
        header("Largest files", "top ${all.size} of ${fmtNum(d.optLong("count"))} files")
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("All", state.largestKind == null) { state.largestKind = null }
                for (k in Kind.entries) counts[k]?.let { Chip("${k.label} $it", state.largestKind == k) { state.largestKind = k } }
            }
        }
        items(items, key = { it.id }) { e -> FileRow(e, state, max, "${e.dir} · ${ago(e.mtime)}") }
    }
}

// ------------------------------------------------------------------ old files

@Composable
fun OldTab(state: AppState) {
    var data by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(state.version, state.oldDays, state.oldMin) {
        data = null
        data = io { Engine.get("old", "days=${state.oldDays}&min=${state.oldMin}&limit=500") }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = ListPadding) {
        header("Old files", "not modified for a long time")
        item {
            Column(Modifier.padding(bottom = 8.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((d, label) in listOf(90 to "3 months", 180 to "6 months", 365 to "1 year", 730 to "2 years"))
                        Chip(label, state.oldDays == d) { state.oldDays = d }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((m, label) in listOf(1_000_000L to "> 1 MB", 10_000_000L to "> 10 MB", 50_000_000L to "> 50 MB", 100_000_000L to "> 100 MB"))
                        Chip(label, state.oldMin == m) { state.oldMin = m }
                }
            }
        }
        val d = data
        if (d == null) item { Loading("Searching…") }
        else {
            val items = d.optJSONArray("items").entries()
            val max = items.maxOfOrNull { it.size } ?: 1
            item { Text("${fmtNum(d.optLong("count"))} files · ${fmtBytes(d.optLong("total"))}", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp)) }
            items(items, key = { it.id }) { e -> FileRow(e, state, max, "${e.dir} · ${ago(e.mtime)}") }
            if (items.isEmpty()) item { Note("No files match. Try a shorter age or a smaller size.") }
        }
    }
}

// ------------------------------------------------------------------ duplicates

@Composable
fun DupesTab(state: AppState) {
    LaunchedEffect(Unit) { if (state.dupes == null) state.loadDupes() }
    val d = state.dupes
    val stateCode = d?.optInt("state") ?: 0
    LazyColumn(Modifier.fillMaxSize(), contentPadding = ListPadding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        header("Duplicates", "identical files, compared by content")
        item {
            Glass(Modifier.fillMaxWidth()) {
                Text("Files at least", color = Muted, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((m, label) in listOf(100_000L to "100 KB", 1_000_000L to "1 MB", 10_000_000L to "10 MB", 50_000_000L to "50 MB"))
                        Chip(label, state.dupeMin == m) { state.dupeMin = m }
                }
                Spacer(Modifier.height(12.dp))
                GradientButton(if (stateCode == 2) "Search again" else "Find duplicates", Modifier.fillMaxWidth(), enabled = stateCode != 1) { state.startDupes() }
                if (stateCode == 0) {
                    Spacer(Modifier.height(10.dp))
                    Text("Compared by size, then the first and last 16 KB, then a full 128-bit hash. Most files never need to be read in full.",
                        color = Faint, fontSize = 12.5.sp)
                }
            }
        }
        if (stateCode == 1 && d != null) item {
            val bytesTotal = d.optLong("bytesTotal")
            val frac = if (bytesTotal > 0) d.optLong("bytesDone").toFloat() / bytesTotal
                       else d.optLong("filesDone").toFloat() / maxOf(d.optLong("filesTotal"), 1)
            Glass(Modifier.fillMaxWidth()) {
                Text(d.optString("stage").replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                SizeBar(frac, 262f, Modifier.fillMaxWidth(), 8.dp)
                Spacer(Modifier.height(8.dp))
                Text(if (bytesTotal > 0) "${fmtBytes(d.optLong("bytesDone"))} of ${fmtBytes(bytesTotal)} hashed"
                     else "${fmtNum(d.optLong("filesDone"))} of ${fmtNum(d.optLong("filesTotal"))} files", color = Muted, fontSize = 12.5.sp)
            }
        }
        if (stateCode == 2 && d != null) {
            val ga = d.optJSONArray("groups")
            val groups = List(ga?.length() ?: 0) { ga!!.getJSONObject(it) }
            item {
                Glass(Modifier.fillMaxWidth()) {
                    Text(fmtBytes(d.optLong("wastedTotal")), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Warn)
                    Text("wasted by ${fmtNum(d.optLong("groupCount"))} groups of identical files", color = Muted, fontSize = 13.sp)
                    if (groups.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GhostButton("Keep newest", Modifier.weight(1f)) { keepOne(state, groups, newest = true) }
                            GhostButton("Keep oldest", Modifier.weight(1f)) { keepOne(state, groups, newest = false) }
                        }
                    }
                }
            }
            items(groups) { g ->
                val files = g.optJSONArray("files").entries()
                val ids = files.map { it.id }
                Glass(Modifier.fillMaxWidth(), padding = 10.dp) {
                    Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                        Text("${files.size} copies × ${fmtBytes(g.optLong("bytes"))}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text("${fmtBytes(g.optLong("wasted"))} wasted", color = Warn, fontSize = 13.sp)
                    }
                    for (f in files) FileRow(f, state, g.optLong("bytes"), "${f.dir} · ${ago(f.mtime)}", group = ids)
                }
            }
        }
    }
}

private fun keepOne(state: AppState, groups: List<JSONObject>, newest: Boolean) {
    for (g in groups) {
        val files = g.optJSONArray("files").entries().sortedBy { if (newest) -it.mtime else it.mtime }
        files.drop(1).forEach { state.basket[it.id] = it.pick() }
        state.basket.remove(files.first().id)
    }
    state.toast("Selected the extra copies; kept the ${if (newest) "newest" else "oldest"} file of each group.")
}

// ------------------------------------------------------------------ file types

@Composable
fun TypesTab(state: AppState) {
    var data by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(state.version) { data = io { Engine.get("types") } }
    val d = data ?: return Loading("Counting file types…")
    val a = d.optJSONArray("types")
    val types = List(a?.length() ?: 0) { a!!.getJSONObject(it) }
    val byKind = types.groupBy { Kind.ofExt(it.optString("ext")) }.mapValues { (_, v) -> v.sumOf { it.optLong("size") } }
        .toList().sortedByDescending { it.second }
    val total = byKind.sumOf { it.second }.coerceAtLeast(1)
    val max = types.maxOfOrNull { it.optLong("size") } ?: 1

    LazyColumn(Modifier.fillMaxSize(), contentPadding = ListPadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        header("File types")
        item {
            Glass(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(200.dp)) {
                        val w = 26.dp.toPx()
                        val arc = Size(size.width - w, size.height - w)
                        val tl = Offset(w / 2, w / 2)
                        drawArc(Track, 0f, 360f, false, tl, arc, style = Stroke(w))
                        var start = -90f
                        for ((k, s) in byKind) {
                            val sweep = 360f * s / total
                            drawArc(hue(k.hue), start, (sweep - 1.2f).coerceAtLeast(0f), false, tl, arc, style = Stroke(w))
                            start += sweep
                        }
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(fmtBytes(total), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text("in files", color = Muted, fontSize = 12.sp)
                    }
                }
                for ((k, s) in byKind) Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(11.dp).clip(CircleShape).background(hue(k.hue)))
                    Spacer(Modifier.width(10.dp))
                    Text(k.label, Modifier.weight(1f), fontSize = 14.sp)
                    Text("${fmtBytes(s)} · ${fmtPct(s.toDouble() / total)}", color = Muted, fontSize = 13.sp)
                }
            }
        }
        items(types) { t ->
            val ext = t.optString("ext")
            val k = Kind.ofExt(ext)
            Row(Modifier.fillMaxWidth().glass(RoundedCornerShape(14.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (ext.isEmpty()) "(no extension)" else ".$ext", fontFamily = Mono, fontSize = 14.sp)
                    Text("${k.label} · ${fmtNum(t.optLong("count"))} files", color = Faint, fontSize = 11.5.sp)
                    Spacer(Modifier.height(6.dp))
                    SizeBar(t.optLong("size").toFloat() / max, k.hue, Modifier.fillMaxWidth(.9f), 4.dp)
                }
                Text(fmtBytes(t.optLong("size")), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        }
    }
}
