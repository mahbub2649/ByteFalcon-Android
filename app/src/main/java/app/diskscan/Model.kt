package app.diskscan

import org.json.JSONArray
import org.json.JSONObject

data class Entry(
    val id: Int,
    val name: String,
    val type: Char,  // d = folder, f = file, l = link, o = other
    val size: Long,  // bytes on disk
    val mtime: Long,
    val files: Int,
    val mount: Boolean,
    val path: String,  // display path, when the endpoint provides one
    val dir: String,   // display path of the parent folder, when provided
) {
    val isDir get() = type == 'd'
}

fun JSONObject.toEntry() = Entry(
    id = optInt("id"),
    name = optString("name"),
    type = optString("type", "f").firstOrNull() ?: 'f',
    size = optLong("size"),
    mtime = optLong("mtime"),
    files = optInt("files"),
    mount = optBoolean("mount"),
    path = optString("path"),
    dir = optString("dir"),
)

fun JSONArray?.entries(): List<Entry> = if (this == null) emptyList() else List(length()) { getJSONObject(it).toEntry() }

data class TreeNode(val entry: Entry, val children: List<TreeNode>, val restSize: Long, val restCount: Int)

fun JSONObject.toTree(): TreeNode = TreeNode(
    entry = toEntry(),
    children = optJSONArray("children")?.let { a -> List(a.length()) { a.getJSONObject(it).toTree() } } ?: emptyList(),
    restSize = optLong("restSize"),
    restCount = optInt("restCount"),
)

data class Volume(val path: String, val label: String, val total: Long, val free: Long)

data class Summary(
    val root: String,
    val display: String,
    val rootId: Int,
    val size: Long,
    val files: Long,
    val dirs: Long,
    val errors: Long,
    val seconds: Double,
    val threads: Int,
    val diskTotal: Long,
    val diskFree: Long,
    val top: List<Entry>,
)

fun JSONObject.toSummary() = Summary(
    root = optString("root"),
    display = optString("display"),
    rootId = optInt("rootId"),
    size = optLong("size"),
    files = optLong("files"),
    dirs = optLong("dirs"),
    errors = optLong("errors"),
    seconds = optDouble("seconds"),
    threads = optInt("threads"),
    diskTotal = optLong("diskTotal"),
    diskFree = optLong("diskFree"),
    top = optJSONArray("top").entries(),
)

data class SmartCat(
    val id: String,
    val title: String,
    val safety: String,
    val description: String,
    val total: Long,
    val count: Int,
    val items: List<Entry>,
) {
    val safe get() = safety == "safe"
}

data class Smart(val safeTotal: Long, val cats: List<SmartCat>)

fun JSONObject.toSmart(): Smart {
    val a = optJSONArray("categories") ?: JSONArray()
    return Smart(optLong("safeTotal"), List(a.length()) {
        val c = a.getJSONObject(it)
        SmartCat(c.optString("id"), c.optString("title"), c.optString("safety"), c.optString("description"),
            c.optLong("total"), c.optInt("count"), c.optJSONArray("items").entries())
    })
}

/** Something selected for cleanup. */
data class Pick(val id: Int, val name: String, val path: String, val size: Long, val safe: Boolean)

fun Entry.pick(safe: Boolean = false, base: String = "") =
    Pick(id, name, path.ifEmpty { if (dir.isNotEmpty()) "$dir/$name" else if (base.isNotEmpty()) "$base/$name" else name }, size, safe)

// ------------------------------------------------------------------ formatting

fun fmtBytes(n: Long): String {
    if (n <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = n.toDouble()
    var i = 0
    while (v >= 1000 && i < units.lastIndex) { v /= 1000; i++ }
    return when {
        i == 0 -> "${v.toLong()} B"
        v >= 100 -> "%.0f %s".format(v, units[i])
        else -> "%.1f %s".format(v, units[i])
    }
}

fun fmtNum(n: Long): String = "%,d".format(n)

fun fmtPct(p: Double): String = when {
    p >= 0.995 -> "100%"
    p > 0 && p < 0.001 -> "<0.1%"
    p < 0.1 -> "%.1f%%".format(p * 100)
    else -> "%.0f%%".format(p * 100)
}

fun ago(sec: Long): String {
    if (sec <= 0) return ""
    val d = System.currentTimeMillis() / 1000 - sec
    return when {
        d < 3600 -> "${maxOf(1, d / 60)} min ago"
        d < 86400 -> "${d / 3600} h ago"
        d < 86400 * 45 -> "${d / 86400} days ago"
        d < 86400 * 365 -> "${d / 86400 / 30} mo ago"
        else -> "%.1f yr ago".format(d / 86400.0 / 365)
    }
}

// ------------------------------------------------------------------ file kinds

enum class Kind(val label: String, val hue: Float, exts: String) {
    VIDEO("Video", 330f, "mp4 mkv avi mov webm 3gp m4v ts"),
    IMAGE("Images", 275f, "jpg jpeg png gif webp heic heif bmp dng raw svg avif"),
    AUDIO("Audio", 190f, "mp3 m4a aac wav flac ogg opus amr mid"),
    ARCHIVE("Apps & archives", 38f, "apk apks xapk zip rar 7z tar gz tgz xz iso obb"),
    DOC("Documents", 212f, "pdf doc docx xls xlsx ppt pptx txt csv epub odt md json xml html"),
    DATA("Data & databases", 255f, "db sqlite sqlite3 log dat bin pack idx so"),
    OTHER("Other", 222f, "");

    val extensions = exts.split(' ').filter { it.isNotEmpty() }.toSet()

    companion object {
        private val byExt = entries.flatMap { k -> k.extensions.map { it to k } }.toMap()
        fun ofExt(ext: String) = byExt[ext] ?: OTHER
        fun of(e: Entry) = ofExt(extOf(e.name))
    }
}

fun extOf(name: String): String {
    val i = name.lastIndexOf('.')
    return if (i > 0 && name.length - i <= 9) name.substring(i + 1).lowercase() else ""
}
