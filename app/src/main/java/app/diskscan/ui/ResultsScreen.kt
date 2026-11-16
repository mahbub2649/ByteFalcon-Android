package app.diskscan.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.diskscan.EngineException
import app.diskscan.fmtBytes
import app.diskscan.fmtNum
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun ResultsScreen(state: AppState, onClearAppCaches: () -> Unit) {
    val s = state.summary ?: return
    var reviewing by remember { mutableStateOf(false) }
    BackHandler { if (state.tab != Tab.Overview) state.tab = Tab.Overview else state.screen = Screen.Start }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 14.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.display, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${fmtBytes(s.size)} · ${fmtNum(s.files)} files · ${"%.2f".format(s.seconds)} s",
                        color = Muted, fontSize = 12.5.sp, maxLines = 1)
                }
                GhostButton("Rescan") { state.rescan() }
                Spacer(Modifier.width(8.dp))
                GhostButton("New") { state.screen = Screen.Start }
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val safe = state.smart?.safeTotal ?: 0
                for (t in Tab.entries) Chip(if (t == Tab.Smart && safe > 0) "${t.label} · ${fmtBytes(safe)}" else t.label, state.tab == t) { state.tab = t }
            }
            Box(Modifier.weight(1f)) {
                when (state.tab) {
                    Tab.Overview -> OverviewTab(state)
                    Tab.Explorer -> ExplorerTab(state)
                    Tab.Smart -> SmartTab(state, onClearAppCaches)
                    Tab.Largest -> LargestTab(state)
                    Tab.Dupes -> DupesTab(state)
                    Tab.Old -> OldTab(state)
                    Tab.Types -> TypesTab(state)
                }
            }
        }
        BasketBar(state, Modifier.align(Alignment.BottomCenter)) { reviewing = true }
        if (reviewing) ReviewDialog(state) { reviewing = false }
    }
}

@Composable
private fun BasketBar(state: AppState, modifier: Modifier, onReview: () -> Unit) {
    AnimatedVisibility(
        state.basket.isNotEmpty(), modifier.navigationBarsPadding().padding(14.dp),
        enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut(),
    ) {
        Row(
            Modifier.fillMaxWidth().background(Color(0xE6101326), RoundedCornerShape(20.dp)).glass(RoundedCornerShape(20.dp))
                .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(Brand), contentAlignment = Alignment.Center) {
                Text(if (state.basket.size > 999) "999+" else "${state.basket.size}", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.White)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(fmtBytes(state.basketSize), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("selected", color = Muted, fontSize = 11.5.sp)
            }
            Text("Clear", color = Muted, fontSize = 14.sp, modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { state.basket.clear() }.padding(10.dp))
            GradientButton("Review & clean", onClick = onReview)
        }
    }
}

@Composable
private fun ReviewDialog(state: AppState, onDismiss: () -> Unit) {
    var phase by remember { mutableIntStateOf(0) }  // 0 review, 1 deleting, 2 done
    var result by remember { mutableStateOf<JSONObject?>(null) }
    var ack by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val picks = state.basket.values.sortedByDescending { it.size }
    val allSafe = picks.all { it.safe }

    Dialog(onDismissRequest = { if (phase != 1) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxWidth(.94f).heightIn(max = 680.dp).background(Color(0xF2111427), RoundedCornerShape(24.dp))
                .glass(RoundedCornerShape(24.dp)).padding(20.dp),
        ) {
            when (phase) {
                0 -> {
                    Text("Review cleanup", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("${fmtNum(picks.size.toLong())} items · ${fmtBytes(picks.sumOf { it.size })}", color = Muted, fontSize = 13.5.sp)
                    Spacer(Modifier.height(12.dp))
                    LazyColumn(Modifier.heightIn(max = 300.dp).glass(RoundedCornerShape(14.dp))) {
                        items(picks, key = { it.id }) { p ->
                            Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(p.path, fontSize = 12.5.sp, fontFamily = Mono, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text(fmtBytes(p.size), color = Muted, fontSize = 12.5.sp, modifier = Modifier.padding(horizontal = 8.dp))
                                Text("✕", color = Faint, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                                    state.basket.remove(p.id); if (state.basket.isEmpty()) onDismiss()
                                }.padding(10.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        if (allSafe) "Everything selected is cache or leftovers that apps rebuild on their own."
                        else "Files are deleted permanently. Android has no undo for this.",
                        color = if (allSafe) Ok else Warn, fontSize = 13.5.sp,
                    )
                    if (!allSafe) Row(Modifier.padding(top = 12.dp).clickable { ack = !ack }, verticalAlignment = Alignment.CenterVertically) {
                        GlassCheckbox(ack) { ack = it }
                        Spacer(Modifier.width(10.dp))
                        Text("I understand this can't be undone", fontSize = 13.5.sp)
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        GhostButton("Cancel", onClick = onDismiss)
                        Spacer(Modifier.width(10.dp))
                        GradientButton("Delete permanently", danger = true, enabled = allSafe || ack) {
                            phase = 1
                            scope.launch {
                                try {
                                    val r = state.clean(picks.map { it.id })
                                    result = r
                                    state.afterClean(r)
                                    phase = 2
                                } catch (e: EngineException) {
                                    state.toast(e.message ?: "cleanup failed"); onDismiss()
                                }
                            }
                        }
                    }
                }
                1 -> {
                    Text("Deleting…", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Loading("${fmtNum(picks.size.toLong())} items")
                }
                else -> {
                    val r = result ?: JSONObject()
                    val rows = r.optJSONArray("results")
                    val errors = (0 until (rows?.length() ?: 0)).map { rows!!.getJSONObject(it) }.filter { !it.optBoolean("ok") }
                    Text(if (errors.isEmpty()) "All clean" else "Done, with some problems", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Column(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        BrandText(fmtBytes(r.optLong("freed")), 40.sp)
                        Text("freed · ${fmtBytes(r.optLong("diskFree"))} now free", color = Muted, fontSize = 13.5.sp)
                    }
                    if (errors.isNotEmpty()) LazyColumn(Modifier.heightIn(max = 220.dp).glass(RoundedCornerShape(14.dp)).padding(10.dp)) {
                        items(errors) { e ->
                            Column(Modifier.padding(vertical = 4.dp)) {
                                Text(e.optString("path"), fontSize = 12.sp, fontFamily = Mono, color = Bad, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(e.optString("error"), fontSize = 12.sp, color = Muted)
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    GradientButton("Done", Modifier.fillMaxWidth(), onClick = onDismiss)
                }
            }
        }
    }
}
