package app.diskscan.system

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/** One camera/microphone access by an app, as recorded by Android's app-ops service. */
data class Access(val pkg: String, val label: String, val op: String, val timeMs: Long, val state: String, val duration: String)

/**
 * Reads camera/mic access history from `dumpsys appops`. That needs the DUMP permission,
 * which only adb can grant:  adb shell pm grant app.diskscan android.permission.DUMP
 * Android keeps only the latest access per app and state, so each read is merged into
 * a local log file; the history grows every time the app looks.
 */
object AccessLog {
    private val stamp = Regex("""Access:\s*\[([^\]]*)\]\s*(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d{3})(?:.*duration=\+?(\S+))?""")
    private val opLine = Regex("""^([A-Z_]+) \(""")
    private val wanted = mapOf("CAMERA" to "Camera", "RECORD_AUDIO" to "Microphone")

    fun hasPermission(ctx: Context) = ctx.checkSelfPermission(android.Manifest.permission.DUMP) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun file(ctx: Context) = File(ctx.filesDir, "access_log.tsv")

    /** Merges the current app-ops snapshot into the saved log; returns all entries, newest first. */
    fun refresh(ctx: Context): List<Access> {
        val seen = LinkedHashMap<String, Access>()
        runCatching {
            file(ctx).forEachLine { l ->
                val p = l.split('\t')
                if (p.size >= 5) seen["${p[0]}|${p[1]}|${p[2]}"] = Access(p[0], p[0], p[1], p[2].toLong(), p[3], p[4])
            }
        }
        if (hasPermission(ctx)) runCatching { parse(dump()).forEach { seen.putIfAbsent("${it.pkg}|${it.op}|${it.timeMs}", it) } }
        val all = seen.values.sortedByDescending { it.timeMs }.take(2000)
        runCatching { file(ctx).writeText(all.joinToString("") { "${it.pkg}\t${it.op}\t${it.timeMs}\t${it.state}\t${it.duration}\n" }) }
        val pm = ctx.packageManager
        val labels = HashMap<String, String>()
        return all.map { a ->
            a.copy(label = labels.getOrPut(a.pkg) {
                runCatching { pm.getApplicationLabel(pm.getApplicationInfo(a.pkg, 0)).toString() }.getOrDefault(a.pkg)
            })
        }
    }

    private fun dump(): String {
        val p = ProcessBuilder("dumpsys", "appops").redirectErrorStream(true).start()
        return p.inputStream.bufferedReader().readText().also { p.waitFor() }
    }

    private fun parse(text: String): List<Access> {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        val out = ArrayList<Access>()
        var pkg: String? = null
        var op: String? = null
        for (raw in text.lineSequence()) {
            val t = raw.trim()
            when {
                t.startsWith("Uid ") -> { pkg = null; op = null }
                t.startsWith("Package ") && t.endsWith(":") -> { pkg = t.removePrefix("Package ").removeSuffix(":"); op = null }
                opLine.containsMatchIn(t) -> op = wanted[opLine.find(t)!!.groupValues[1]]
                pkg != null && op != null -> stamp.find(t)?.let { m ->
                    val ms = runCatching { fmt.parse(m.groupValues[2])!!.time }.getOrNull() ?: return@let
                    out += Access(pkg, pkg, op, ms, stateName(m.groupValues[1]), m.groupValues[3])
                }
            }
        }
        return out
    }

    private fun stateName(s: String) = when (s.trim().substringBefore('-')) {
        "top" -> "on screen"; "fgsvc", "fg" -> "foreground service"; "bg" -> "background"
        "cch" -> "cached (background)"; "pers" -> "system"; else -> s
    }
}
