package app.diskscan.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.diskscan.Kind
import app.diskscan.TreeNode
import app.diskscan.fmtBytes
import app.diskscan.fmtNum

private class Cell(val rect: Rect, val node: TreeNode?, val label: String, val sub: String, val hue: Float, val depth: Int, val header: Boolean)

/**
 * Squarified treemap (Bruls, Huizing & van Wijk): lays out rows of rectangles along the shorter
 * side, adding items to a row while that keeps their aspect ratios closer to square.
 */
private fun squarify(areas: List<Float>, rect: Rect): List<Rect> {
    val out = ArrayList<Rect>(areas.size)
    var x = rect.left; var y = rect.top; var w = rect.width; var h = rect.height
    fun worst(sum: Float, min: Float, max: Float, side: Float) = maxOf(side * side * max / (sum * sum), sum * sum / (side * side * min))
    var i = 0
    while (i < areas.size) {
        val side = minOf(w, h)
        if (side <= 0f) { repeat(areas.size - i) { out.add(Rect(x, y, x, y)) }; break }
        var sum = areas[i]; var min = sum; var max = sum; var j = i + 1
        while (j < areas.size) {
            val a = areas[j]
            if (worst(sum + a, minOf(min, a), maxOf(max, a), side) > worst(sum, min, max, side)) break
            sum += a; min = minOf(min, a); max = maxOf(max, a); j++
        }
        if (w >= h) {
            val cw = sum / h; var cy = y
            for (k in i until j) { val ch = areas[k] / cw; out.add(Rect(x, cy, x + cw, cy + ch)); cy += ch }
            x += cw; w -= cw
        } else {
            val rh = sum / w; var cx = x
            for (k in i until j) { val cwid = areas[k] / rh; out.add(Rect(cx, y, cx + cwid, y + rh)); cx += cwid }
            y += rh; h -= rh
        }
        i = j
    }
    return out
}

private fun layout(nodes: List<TreeNode>, rest: Long, restCount: Int, rect: Rect): List<Pair<TreeNode?, Rect>> {
    val items = nodes.filter { it.entry.size > 0 }
    val sizes = items.map { it.entry.size } + if (rest > 0) listOf(rest) else emptyList()
    val total = sizes.sum().toFloat().coerceAtLeast(1f)
    val rects = squarify(sizes.map { it / total * rect.width * rect.height }, rect)
    return rects.mapIndexed { i, r -> (if (i < items.size) items[i] else null) to r }
}

private fun buildCells(tree: TreeNode, w: Float, h: Float, density: Float): List<Cell> {
    val cells = ArrayList<Cell>()
    val gap = 2f * density
    val headerH = 20f * density
    layout(tree.children, tree.restSize, tree.restCount, Rect(0f, 0f, w, h)).forEachIndexed { i, (node, r) ->
        val inner = r.deflate(gap / 2)
        if (node == null) {
            cells.add(Cell(inner, null, "${fmtNum(tree.restCount.toLong())} smaller items", fmtBytes(tree.restSize), 222f, 0, false))
            return@forEachIndexed
        }
        val e = node.entry
        val h1 = if (e.isDir) (i * 47f + 255f) % 360f else Kind.of(e).hue
        val nested = e.isDir && node.children.isNotEmpty() && inner.width > 70 * density && inner.height > 60 * density
        cells.add(Cell(inner, node, e.name, fmtBytes(e.size), h1, 0, nested))
        if (nested) {
            val area = Rect(inner.left + 3 * density, inner.top + headerH, inner.right - 3 * density, inner.bottom - 3 * density)
            layout(node.children, node.restSize, node.restCount, area).forEach { (child, cr) ->
                if (cr.width < 3 || cr.height < 3) return@forEach
                val ce = child?.entry
                val h2 = when { ce == null -> 222f; ce.isDir -> h1; else -> Kind.of(ce).hue }
                cells.add(Cell(cr.deflate(gap / 2), child, ce?.name ?: "", ce?.let { fmtBytes(it.size) } ?: "", h2, 1, false))
            }
        }
    }
    return cells
}

@Composable
fun Treemap(tree: TreeNode, modifier: Modifier = Modifier, onOpen: (Int) -> Unit, onFile: (Int) -> Unit) {
    val measurer = rememberTextMeasurer()
    var size by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current.density
    val cells = remember(tree, size, density) {
        if (size.width == 0) emptyList() else buildCells(tree, size.width.toFloat(), size.height.toFloat(), density)
    }
    Canvas(
        modifier.onSizeChanged { size = it }.pointerInput(cells) {
            detectTapGestures { p ->
                val hit = cells.lastOrNull { it.node != null && it.rect.contains(p) } ?: return@detectTapGestures
                val e = hit.node!!.entry
                if (e.isDir) onOpen(e.id) else onFile(e.id)
            }
        },
    ) {
        val radius = CornerRadius(7.dp.toPx(), 7.dp.toPx())
        val bold = TextStyle(color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
        val small = TextStyle(color = Color(0xCCFFFFFF), fontSize = 10.sp)
        for (c in cells) {
            val r = c.rect
            if (r.width <= 0f || r.height <= 0f) continue
            val rest = c.node == null
            val fill = when {
                rest -> Color(0x0DFFFFFF)
                c.node!!.entry.isDir && c.depth == 0 -> hue(c.hue, .6f, .45f, .24f)
                else -> hue(c.hue, .72f, .58f, if (c.depth == 0) .34f else .36f)
            }
            drawRoundRect(fill, r.topLeft, r.size, radius)
            drawRoundRect(if (rest) Color(0x26FFFFFF) else hue(c.hue, .85f, .72f, .45f), r.topLeft, r.size, radius, style = Stroke(1.dp.toPx()))
            val tw = (r.width - 10.dp.toPx()).toInt()
            if (tw > 30 && r.height > 18.dp.toPx()) {
                val title = measurer.measure(c.label, bold, overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = tw))
                drawText(title, topLeft = Offset(r.left + 6.dp.toPx(), r.top + 3.dp.toPx()))
                if (!c.header && r.height > 36.dp.toPx()) {
                    val sub = measurer.measure(c.sub, small, maxLines = 1, overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = tw))
                    drawText(sub, topLeft = Offset(r.left + 6.dp.toPx(), r.top + 3.dp.toPx() + title.size.height))
                } else if (c.header) {
                    val sub = measurer.measure(c.sub, small, maxLines = 1)
                    val x = r.left + 10.dp.toPx() + title.size.width
                    if (x + sub.size.width < r.right - 4.dp.toPx()) drawText(sub, topLeft = Offset(x, r.top + 4.dp.toPx()))
                }
            }
        }
    }
}
