package app.diskscan.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.diskscan.Engine
import app.diskscan.EngineException
import app.diskscan.Kind
import app.diskscan.Pick
import app.diskscan.Smart
import app.diskscan.Summary
import app.diskscan.Volume
import app.diskscan.toSmart
import app.diskscan.toSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class Screen { Start, Scanning, Results }

enum class Tab(val label: String) {
    Overview("Overview"), Explorer("Explorer"), Smart("Smart Clean"), Largest("Largest"),
    Dupes("Duplicates"), Old("Old files"), Types("Types"),
}

/** Native calls block, so they always run on the IO dispatcher. */
suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

class AppState(private val scope: CoroutineScope, val toast: (String) -> Unit, private val openPath: (String) -> Unit) {
    var screen by mutableStateOf(Screen.Start)
    var volumes by mutableStateOf<List<Volume>>(emptyList())
    var progress by mutableStateOf(JSONObject())
    var summary by mutableStateOf<Summary?>(null)
    var tab by mutableStateOf(Tab.Overview)
    var smart by mutableStateOf<Smart?>(null)
    var dirId by mutableIntStateOf(-1)
    var version by mutableIntStateOf(0)  // bumped after every cleanup so open tabs reload
    val basket = mutableStateMapOf<Int, Pick>()

    var dupes by mutableStateOf<JSONObject?>(null)
    var dupeMin by mutableLongStateOf(1_000_000L)
    var oldDays by mutableIntStateOf(365)
    var oldMin by mutableLongStateOf(50_000_000L)
    var largestKind by mutableStateOf<Kind?>(null)

    val basketSize get() = basket.values.sumOf { it.size }

    fun refreshVolumes() = scope.launch {
        runCatching { io { Engine.get("info") } }.onSuccess { info ->
            val a = info.optJSONArray("volumes") ?: JSONArray()
            volumes = List(a.length()) {
                val v = a.getJSONObject(it)
                Volume(v.optString("path"), v.optString("label"), v.optLong("total"), v.optLong("free"))
            }
        }
    }

    fun startScan(path: String) = scope.launch {
        try {
            io { Engine.post("scan", JSONObject().put("path", path)) }
        } catch (e: EngineException) {
            toast(e.message ?: "scan failed"); return@launch
        }
        basket.clear(); smart = null; dupes = null
        progress = JSONObject()
        screen = Screen.Scanning
        while (true) {
            progress = io { Engine.get("progress") }
            if (!progress.optBoolean("running")) break
            delay(120)
        }
        val err = progress.optString("error")
        if (err.isNotEmpty()) { toast(err); screen = Screen.Start; return@launch }
        loadResults()
    }

    fun cancelScan() = scope.launch { io { Engine.post("cancel") } }

    private suspend fun loadResults() {
        val s = io { Engine.get("summary").toSummary() }
        summary = s
        dirId = s.rootId
        tab = Tab.Overview
        screen = Screen.Results
        version++
        loadSmart()
    }

    fun loadSmart() = scope.launch { smart = runCatching { io { Engine.get("smart").toSmart() } }.getOrNull() }

    fun rescan() = summary?.let { startScan(it.root) }

    /** Opens a scanned file in whatever app handles its type. */
    fun open(id: Int) = scope.launch {
        try {
            openPath(io { Engine.get("path", "id=$id") }.getString("path"))
        } catch (e: EngineException) {
            toast(e.message ?: "can't open")
        }
    }

    fun browse(id: Int) {
        dirId = id
        tab = Tab.Explorer
    }

    /** Returns false (and refuses) if selecting would leave no copy of a duplicate group. */
    fun toggle(pick: Pick, on: Boolean, group: List<Int>? = null): Boolean {
        if (on && group != null && group.filter { it != pick.id }.all { basket.containsKey(it) }) {
            toast("Keep at least one copy of every duplicate.")
            return false
        }
        if (on) basket[pick.id] = pick else basket.remove(pick.id)
        return true
    }

    fun select(picks: List<Pick>) = picks.forEach { basket[it.id] = it }

    suspend fun clean(ids: List<Int>): JSONObject =
        io { Engine.post("clean", JSONObject().put("ids", JSONArray(ids))) }

    fun afterClean(result: JSONObject) = scope.launch {
        val r = result.optJSONArray("results") ?: JSONArray()
        for (i in 0 until r.length()) r.getJSONObject(i).takeIf { it.optBoolean("ok") }?.let { basket.remove(it.optInt("id")) }
        summary = io { Engine.get("summary").toSummary() }
        smart = null
        if (dupes?.optInt("state") == 2) dupes = io { Engine.get("dupes") }
        version++
        loadSmart()
    }

    fun startDupes() = scope.launch {
        try {
            io { Engine.post("dupes/start", JSONObject().put("minSize", dupeMin)) }
        } catch (e: EngineException) {
            toast(e.message ?: ""); return@launch
        }
        while (true) {
            dupes = io { Engine.get("dupes") }
            if (dupes?.optInt("state") != 1) break
            delay(300)
        }
    }

    fun loadDupes() = scope.launch {
        dupes = io { Engine.get("dupes") }
        if (dupes?.optInt("state") == 1) startPolling()
    }

    private fun startPolling() = scope.launch {
        while (dupes?.optInt("state") == 1) {
            delay(300)
            dupes = io { Engine.get("dupes") }
        }
    }
}
