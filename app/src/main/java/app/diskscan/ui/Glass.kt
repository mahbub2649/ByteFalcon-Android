package app.diskscan.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import android.os.SystemClock
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.diskscan.Entry
import app.diskscan.Kind
import app.diskscan.extOf

// ------------------------------------------------------------------ palette

val Bg = Color(0xFF070912)
val TextC = Color(0xFFEEF0FF)
val Muted = Color(0xA3E2E6FF)
val Faint = Color(0x66E2E6FF)
val Violet = Color(0xFF8B5CF6)
val Indigo = Color(0xFF6366F1)
val Cyan = Color(0xFF22D3EE)
val Ok = Color(0xFF34D399)
val Warn = Color(0xFFFBBF24)
val Bad = Color(0xFFFB7185)
val Track = Color(0x12FFFFFF)
val Brand = Brush.linearGradient(listOf(Violet, Indigo, Cyan))
val Mono = FontFamily.Monospace

fun hue(h: Float, s: Float = .78f, l: Float = .62f, a: Float = 1f) = Color.hsl(((h % 360f) + 360f) % 360f, s, l, a)

@Composable
fun DiskscanTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Violet, secondary = Cyan, background = Bg, surface = Color(0xFF14172B),
            onPrimary = Color.White, onBackground = TextC, onSurface = TextC,
        ),
    ) {
        CompositionLocalProvider(LocalContentColor provides TextC, content = content)
    }
}

// ------------------------------------------------------------------ background

/**
 * Three slowly drifting colour blobs behind the glass. Radial gradients are already soft, so no
 * blur pass is needed; the drift is so slow that 10 updates a second look continuous, and the
 * phase is read only while drawing, so nothing recomposes. (A blurred 60 fps version cost a
 * whole CPU core.)
 */
@Composable
fun GlassBackground() {
    val phase = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = SystemClock.uptimeMillis()
        while (true) {
            phase.longValue = SystemClock.uptimeMillis() - start
            delay(100)
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        fun pingPong(periodMs: Long): Float {
            val x = (phase.longValue % (2 * periodMs)).toFloat() / periodMs
            return if (x <= 1f) x else 2f - x
        }
        val a = pingPong(26_000)
        val b = pingPong(33_000)
        val m = size.minDimension
        fun blob(c: Color, x: Float, y: Float, r: Float, alpha: Float) =
            drawCircle(Brush.radialGradient(listOf(c.copy(alpha = alpha), Color.Transparent), Offset(x, y), r), r, Offset(x, y))
        blob(Color(0xFF7C3AED), size.width * (.1f + .35f * a), size.height * (.12f + .2f * b), m * .75f, .55f)
        blob(Color(0xFF0891B2), size.width * (.95f - .4f * b), size.height * (.85f - .25f * a), m * .8f, .5f)
        blob(Color(0xFFDB2777), size.width * (.6f - .3f * a), size.height * (.45f + .15f * b), m * .5f, .28f)
    }
}

// ------------------------------------------------------------------ surfaces

private val GlassFill = Brush.linearGradient(listOf(Color(0x1CFFFFFF), Color(0x0AFFFFFF)))
private val GlassEdge = Brush.linearGradient(listOf(Color(0x40FFFFFF), Color(0x0DFFFFFF), Color(0x1AFFFFFF)))

fun Modifier.glass(shape: Shape = RoundedCornerShape(22.dp)) =
    clip(shape).background(Color(0x66101326)).background(GlassFill).border(1.dp, GlassEdge, shape)

@Composable
fun Glass(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    padding: Dp = 18.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.glass(shape).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(padding),
        content = content,
    )
}

@Composable
fun GradientButton(text: String, modifier: Modifier = Modifier, danger: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val brush = if (danger) Brush.linearGradient(listOf(Color(0xFFF43F5E), Color(0xFFE11D48))) else Brand
    Box(
        modifier.clip(RoundedCornerShape(14.dp)).background(brush, alpha = if (enabled) 1f else .4f)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 20.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
}

@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier.glass(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 13.5.sp, color = if (enabled) TextC else Faint) }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(99.dp))
            .background(if (selected) Color(0x2EFFFFFF) else Color(0x0FFFFFFF))
            .border(1.dp, if (selected) Color(0x55FFFFFF) else Color(0x1AFFFFFF), RoundedCornerShape(99.dp))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    ) { Text(text, fontSize = 13.sp, color = if (selected) TextC else Muted) }
}

@Composable
fun SafetyPill(safety: String) {
    val (label, color) = when (safety) {
        "safe" -> "SAFE" to Ok
        "review" -> "REVIEW" to Warn
        else -> safety.uppercase() to Cyan
    }
    Text(
        label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = .6.sp,
        modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(color.copy(alpha = .15f)).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier = modifier, fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.3).sp)

@Composable
fun StatTile(label: String, value: String, sub: String, modifier: Modifier = Modifier, valueColor: Color = TextC) {
    Glass(modifier, padding = 14.dp) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(value, color = valueColor, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.5).sp, maxLines = 1)
        Text(sub, color = Faint, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ------------------------------------------------------------------ data visuals

@Composable
fun UsageRing(fraction: Float, size: Dp = 64.dp, stroke: Dp = 7.dp) {
    val color = when { fraction > .9f -> Bad; fraction > .75f -> Warn; else -> Ok }
    val animated by animateFloatAsState(fraction, tween(900, easing = FastOutSlowInEasing), label = "ring")
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = Stroke(stroke.toPx(), cap = StrokeCap.Round)
            val inset = stroke.toPx() / 2
            val arcSize = Size(this.size.width - 2 * inset, this.size.height - 2 * inset)
            drawArc(Track, 0f, 360f, false, Offset(inset, inset), arcSize, style = s)
            drawArc(color, -90f, 360f * animated, false, Offset(inset, inset), arcSize, style = s)
        }
        Text("${(fraction * 100).toInt()}%", fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SizeBar(fraction: Float, h: Float, modifier: Modifier = Modifier, height: Dp = 5.dp) {
    Box(modifier.height(height).clip(RoundedCornerShape(99.dp)).background(Track)) {
        Box(
            Modifier.fillMaxWidth(fraction.coerceIn(0.01f, 1f)).height(height).clip(RoundedCornerShape(99.dp))
                .background(Brush.horizontalGradient(listOf(hue(h), hue(h + 40f, .85f, .66f)))),
        )
    }
}

/** Coloured square with the file extension (or DIR), standing in for an icon. */
@Composable
fun KindBadge(e: Entry) {
    val h = if (e.isDir) 262f else Kind.of(e).hue
    val label = when {
        e.isDir -> "DIR"
        e.type == 'l' -> "LNK"
        else -> extOf(e.name).take(4).uppercase().ifEmpty { "FILE" }
    }
    Box(
        Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(hue(h, .7f, .55f, .18f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = hue(h, .85f, .74f), fontSize = if (label.length > 3) 9.sp else 10.5.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun GlassCheckbox(checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(7.dp)
    Box(
        Modifier.size(24.dp).clip(shape)
            .then(if (checked) Modifier.background(Brand) else Modifier.background(Color(0x14FFFFFF)).border(1.5.dp, Color(0x40FFFFFF), shape))
            .clickable(enabled = enabled) { onChange(!checked) },
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Canvas(Modifier.size(12.dp)) {
            val p = Path().apply {
                moveTo(size.width * .1f, size.height * .55f)
                lineTo(size.width * .4f, size.height * .85f)
                lineTo(size.width * .92f, size.height * .18f)
            }
            drawPath(p, Color.White, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

@Composable
fun Loading(text: String, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "spin")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "angle")
    Row(modifier.fillMaxWidth().padding(28.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(20.dp)) {
            drawArc(Track, 0f, 360f, false, style = Stroke(2.5.dp.toPx()))
            drawArc(Cyan, angle, 90f, false, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
        }
        Spacer(Modifier.width(12.dp))
        Text(text, color = Muted)
    }
}

@Composable
fun BrandText(text: String, fontSize: androidx.compose.ui.unit.TextUnit) =
    Text(text, style = TextStyle(brush = Brand, fontSize = fontSize, fontWeight = FontWeight.ExtraBold, letterSpacing = (-.5).sp))

@Composable
fun Note(text: String) = Text(text, color = Faint, fontSize = 12.5.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(12.dp))
