package app.diskscan.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.diskscan.system.AppInfo
import app.diskscan.system.SecurityContext
import app.diskscan.system.UsageInfo
import app.diskscan.system.hasUsageAccess
import app.diskscan.system.loadApps
import app.diskscan.system.securityContext
import app.diskscan.system.usageToday
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** State for the Monitor, Apps and Security sections. */
class SystemState(private val scope: CoroutineScope, private val ctx: Context, val toast: (String) -> Unit) {
    var apps by mutableStateOf<List<AppInfo>?>(null)
    var security by mutableStateOf(SecurityContext.EMPTY)
    var usage by mutableStateOf<Map<String, UsageInfo>>(emptyMap())
    var usageAccess by mutableStateOf(false)
    var selected by mutableStateOf<AppInfo?>(null)
    var loading by mutableStateOf(false)
    private var job: Job? = null

    /** Reloads apps, security context and usage; a call while a reload is running is ignored. */
    fun refresh() {
        if (job?.isActive == true) return
        job = scope.launch { reload() }
    }

    private suspend fun reload() {
        loading = true
        usageAccess = hasUsageAccess(ctx)
        val access = usageAccess
        val (a, s, u) = io { Triple(loadApps(ctx), securityContext(ctx), if (access) usageToday(ctx) else emptyMap()) }
        apps = a
        security = s
        usage = u
        selected = selected?.let { sel -> a.firstOrNull { it.pkg == sel.pkg } }  // gone if it was just uninstalled
        loading = false
    }

    /** Back from Settings or an uninstall dialog: permissions and the app list may have changed. */
    fun onResume() {
        if (apps != null || hasUsageAccess(ctx) != usageAccess) refresh()
    }

    fun byUid(): Map<Int, AppInfo> = apps.orEmpty().associateBy { it.uid }

    fun launch(pkg: String) {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return toast("This app has nothing to open")
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun appSettings(pkg: String) = ctx.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    fun uninstall(pkg: String) = ctx.startActivity(
        Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    fun requestUsageAccess() {
        val withPkg = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(withPkg) }.onFailure {
            ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

// ------------------------------------------------------------------ app icons

private object IconCache {
    val cache = LruCache<String, ImageBitmap>(400)
}

@Composable
fun AppIcon(pkg: String, size: Dp = 42.dp) {
    val ctx = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val icon by produceState<ImageBitmap?>(IconCache.cache.get(pkg), pkg) {
        if (value == null) value = io {
            runCatching {
                val d = ctx.packageManager.getApplicationIcon(pkg)
                val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
                d.setBounds(0, 0, px, px)
                d.draw(android.graphics.Canvas(bmp))
                bmp.asImageBitmap()
            }.getOrNull()
        }?.also { IconCache.cache.put(pkg, it) }
    }
    val bmp = icon
    if (bmp != null) Image(bmp, null, Modifier.size(size).clip(RoundedCornerShape(12.dp)))
    else Box(Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(Track))
}

@Composable
fun UsageAccessCard(sys: SystemState, why: String) {
    Glass(Modifier.fillMaxWidth()) {
        Text("Usage access needed", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Text(why, color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        GradientButton("Allow usage access", Modifier.fillMaxWidth()) { sys.requestUsageAccess() }
        Spacer(Modifier.height(6.dp))
        Text("Turn on the switch for ByteFalcon, then come back.", color = Faint, fontSize = 12.sp)
    }
}

fun fmtDuration(ms: Long): String = when {
    ms < 60_000 -> "${ms / 1000} s"
    ms < 3_600_000 -> "${ms / 60_000} min"
    else -> "%.1f h".format(ms / 3_600_000.0)
}
