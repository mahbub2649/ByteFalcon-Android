package app.diskscan.system

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.os.storage.StorageManager
import android.provider.Settings
import android.provider.Telephony
import java.io.File

// Everything here uses Android's public APIs: without root, no app can see other apps'
// processes or kernel internals, so these are the deepest signals available.

data class AppInfo(
    val pkg: String,
    val label: String,
    val uid: Int,
    val system: Boolean,
    val updatedSystem: Boolean,
    val enabled: Boolean,
    val launchable: Boolean,
    val version: String,
    val targetSdk: Int,
    val installed: Long,
    val updated: Long,
    val installer: String?,
    val granted: Set<String>,
    val requested: Set<String>,
) {
    val groups: List<PermGroup> get() = PermGroup.entries.filter { g -> g.perms.any { it in granted } }
}

enum class PermGroup(val label: String, val letter: String, val sensitive: Boolean, vararg val perms: String) {
    CAMERA("Camera", "C", true, "android.permission.CAMERA"),
    MICROPHONE("Microphone", "M", true, "android.permission.RECORD_AUDIO"),
    LOCATION("Location", "L", true, "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION"),
    SMS("SMS", "S", true, "android.permission.READ_SMS", "android.permission.SEND_SMS", "android.permission.RECEIVE_SMS",
        "android.permission.RECEIVE_MMS"),
    CONTACTS("Contacts", "K", true, "android.permission.READ_CONTACTS", "android.permission.WRITE_CONTACTS", "android.permission.GET_ACCOUNTS"),
    CALLS("Call log", "P", true, "android.permission.READ_CALL_LOG", "android.permission.WRITE_CALL_LOG", "android.permission.PROCESS_OUTGOING_CALLS"),
    PHONE("Phone", "T", false, "android.permission.READ_PHONE_STATE", "android.permission.CALL_PHONE", "android.permission.READ_PHONE_NUMBERS",
        "android.permission.ANSWER_PHONE_CALLS"),
    MEDIA("Photos & media", "F", false, "android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE",
        "android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_AUDIO"),
    CALENDAR("Calendar", "D", false, "android.permission.READ_CALENDAR", "android.permission.WRITE_CALENDAR"),
    BODY("Body sensors & activity", "B", false, "android.permission.BODY_SENSORS", "android.permission.ACTIVITY_RECOGNITION"),
    NEARBY("Nearby devices", "N", false, "android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT",
        "android.permission.NEARBY_WIFI_DEVICES"),
    NOTIFY("Notifications", "!", false, "android.permission.POST_NOTIFICATIONS"),
}

/** Install-time abilities worth knowing about (requested, not necessarily switched on). */
val SpecialAccess = linkedMapOf(
    "android.permission.ACCESS_BACKGROUND_LOCATION" to "Location while in the background",
    "android.permission.SYSTEM_ALERT_WINDOW" to "Draw over other apps",
    "android.permission.REQUEST_INSTALL_PACKAGES" to "Install other apps",
    "android.permission.MANAGE_EXTERNAL_STORAGE" to "All files access",
    "android.permission.PACKAGE_USAGE_STATS" to "Usage access",
    "android.permission.QUERY_ALL_PACKAGES" to "See all installed apps",
    "android.permission.RECEIVE_BOOT_COMPLETED" to "Starts when the phone boots",
    "android.permission.INTERNET" to "Internet",
    "android.permission.READ_PRIVILEGED_PHONE_STATE" to "Privileged phone state",
)

// App stores, plus the phone maker's and partners' preload channels.
private val StoreInstallers = setOf(
    "com.android.vending", "com.heytap.market", "com.oppo.market", "com.oneplus.market", "com.huawei.appmarket",
    "com.sec.android.app.samsungapps", "com.amazon.venezia", "com.xiaomi.market", "com.xiaomi.mipicks", "org.fdroid.fdroid",
    "com.oplus.sau", "com.facebook.system",
)

private val OemPrefixes = listOf("net.oneplus.", "com.oneplus.", "com.oplus.", "com.heytap.", "com.coloros.", "com.oppo.", "com.nearme.")

/** No installer recorded and an OEM package name: the phone maker preinstalled it at the factory. */
fun preinstalledByOem(a: AppInfo) = a.installer == null && OemPrefixes.any { a.pkg.startsWith(it) }

fun installerLabel(installer: String?, pkg: String = ""): String = when (installer) {
    null -> if (OemPrefixes.any { pkg.startsWith(it) }) "Preinstalled by phone maker" else "Unknown source"
    "com.oplus.sau" -> "OnePlus/OPPO system updater"
    "com.facebook.system" -> "Facebook preinstaller"
    "com.android.vending" -> "Google Play"
    "com.google.android.packageinstaller", "com.android.packageinstaller" -> "APK file (sideloaded)"
    "com.heytap.market", "com.oppo.market", "com.oneplus.market" -> "OnePlus/OPPO App Market"
    "org.fdroid.fdroid" -> "F-Droid"
    else -> installer
}

/** Preinstalled apps that are commonly removed without side effects. Everything else: research first. */
val CommonlyRemoved = setOf(
    "com.facebook.appmanager", "com.facebook.services", "com.facebook.system", "com.facebook.katana", "com.facebook.orca",
    "com.instagram.android", "com.netflix.partner.activation", "com.netflix.mediaclient", "com.linkedin.android",
    "com.booking", "com.amazon.mShop.android.shopping", "com.amazon.appmanager", "com.spotify.music",
    "com.google.android.apps.youtube.music", "com.google.android.videos", "com.google.android.apps.tachyon",
    "com.google.android.apps.podcasts", "com.google.android.apps.magazines", "com.google.android.apps.subscriptions.red",
)

/**
 * Phones carry 500+ packages, and labels and install sources cost a binder call each, so the
 * per-app work runs in parallel and "has a launcher icon" comes from one query for all apps.
 */
@Suppress("DEPRECATION")
fun loadApps(ctx: Context): List<AppInfo> {
    val pm = ctx.packageManager
    val launchable = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .map { it.activityInfo.packageName }.toSet()
    return pm.getInstalledPackages(PackageManager.GET_PERMISSIONS).parallelStream().map { pi: PackageInfo ->
        val ai = pi.applicationInfo ?: return@map null
        val requested = pi.requestedPermissions ?: emptyArray()
        val flags = pi.requestedPermissionsFlags ?: IntArray(0)
        val granted = requested.filterIndexed { i, _ -> i < flags.size && flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0 }.toSet()
        AppInfo(
            pkg = pi.packageName,
            label = ai.loadLabel(pm).toString(),
            uid = ai.uid,
            system = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0,
            updatedSystem = ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0,
            enabled = ai.enabled,
            launchable = pi.packageName in launchable,
            version = pi.versionName ?: "",
            targetSdk = ai.targetSdkVersion,
            installed = pi.firstInstallTime,
            updated = pi.lastUpdateTime,
            installer = runCatching { pm.getInstallSourceInfo(pi.packageName).installingPackageName }.getOrNull(),
            granted = granted,
            requested = requested.toSet(),
        )
    }.collect(java.util.stream.Collectors.toList()).filterNotNull().sortedBy { it.label.lowercase() }
}

// ------------------------------------------------------------------ security audit

data class SecurityContext(val accessibility: Set<String>, val listeners: Set<String>, val admins: Set<String>, val defaultSms: String?) {
    companion object { val EMPTY = SecurityContext(emptySet(), emptySet(), emptySet(), null) }
}

private fun componentPackages(value: String?): Set<String> =
    value.orEmpty().split(':').mapNotNull { it.substringBefore('/').takeIf { p -> p.isNotBlank() } }.toSet()

fun securityContext(ctx: Context): SecurityContext {
    val cr = ctx.contentResolver
    return SecurityContext(
        accessibility = componentPackages(Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)),
        listeners = componentPackages(Settings.Secure.getString(cr, "enabled_notification_listeners")),
        admins = ctx.getSystemService(DevicePolicyManager::class.java)?.activeAdmins?.map { it.packageName }?.toSet() ?: emptySet(),
        defaultSms = Telephony.Sms.getDefaultSmsPackage(ctx),
    )
}

enum class Flag(val title: String, val detail: String, val weight: Int) {
    ACCESSIBILITY("Can read and control your screen",
        "Has an Accessibility service switched on: it sees everything on screen and can tap for you. Password managers and screen readers need this; spyware abuses it.", 4),
    ADMIN("Device administrator", "Can lock or wipe the phone and blocks normal uninstalling until removed as admin.", 3),
    HIDDEN("No app icon", "Installed by you but has no icon in the launcher. Keyboards and plugins do this; so does stalkerware.", 3),
    NOTIFICATIONS("Reads your notifications", "Sees every notification, including message previews and login codes.", 2),
    SIDELOADED("Installed outside an app store", "Not from Google Play or your phone maker's store, so nobody reviewed it.", 2),
    SENSITIVE("Many sensitive permissions", "Allowed 3 or more of: camera, microphone, location, SMS, contacts, call log.", 2),
    BG_LOCATION("Location all the time", "Allowed to read your location even while you're not using it.", 2),
    BACKGROUND("Busy in the background", "Ran a background service for over 30 minutes in the last day while you hardly opened it.", 2),
    NEW("Installed in the last 7 days", "Recently added; worth a look if you don't recognise it.", 1),
}

fun flagsFor(a: AppInfo, sec: SecurityContext, usage: UsageInfo?, now: Long = System.currentTimeMillis()): List<Flag> {
    val f = mutableListOf<Flag>()
    if (a.pkg in sec.accessibility) f += Flag.ACCESSIBILITY
    if (a.pkg in sec.admins) f += Flag.ADMIN
    if (a.pkg in sec.listeners) f += Flag.NOTIFICATIONS
    if (!a.system) {
        if (!a.launchable) f += Flag.HIDDEN
        if (a.installer !in StoreInstallers && !preinstalledByOem(a)) f += Flag.SIDELOADED
        if (now - a.installed < 7 * 86_400_000L) f += Flag.NEW
    }
    if (a.groups.count { it.sensitive } >= 3) f += Flag.SENSITIVE
    if ("android.permission.ACCESS_BACKGROUND_LOCATION" in a.granted) f += Flag.BG_LOCATION
    if (usage != null && usage.fgServiceMs > 30 * 60_000L && usage.foregroundMs < 5 * 60_000L) f += Flag.BACKGROUND
    return f
}

// ------------------------------------------------------------------ usage access

fun hasUsageAccess(ctx: Context): Boolean {
    val ops = ctx.getSystemService(AppOpsManager::class.java)
    return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED
}

data class UsageInfo(val lastUsed: Long, val foregroundMs: Long, val fgServiceMs: Long)

/** Per-app activity over the last 24 hours. */
fun usageToday(ctx: Context): Map<String, UsageInfo> {
    val usm = ctx.getSystemService(UsageStatsManager::class.java)
    val now = System.currentTimeMillis()
    return usm.queryAndAggregateUsageStats(now - 86_400_000L, now).mapValues { (_, u) ->
        UsageInfo(u.lastTimeUsed, u.totalTimeInForeground, u.totalTimeForegroundServiceUsed)
    }
}

data class AppStorage(val app: Long, val data: Long, val cache: Long)

fun appStorage(ctx: Context, pkg: String): AppStorage? = runCatching {
    val s = ctx.getSystemService(StorageStatsManager::class.java)
        .queryStatsForPackage(StorageManager.UUID_DEFAULT, pkg, Process.myUserHandle())
    AppStorage(s.appBytes, s.dataBytes - s.cacheBytes, s.cacheBytes)
}.getOrNull()

/** Bytes per uid since `since`, as (wifi, mobile). Needs usage access. */
@Suppress("DEPRECATION")
fun dataUsage(ctx: Context, since: Long): Map<Int, Pair<Long, Long>> {
    val nsm = ctx.getSystemService(NetworkStatsManager::class.java)
    val out = HashMap<Int, LongArray>()
    for ((type, slot) in listOf(ConnectivityManager.TYPE_WIFI to 0, ConnectivityManager.TYPE_MOBILE to 1)) {
        val stats = runCatching { nsm.querySummary(type, null, since, System.currentTimeMillis()) }.getOrNull() ?: continue
        val b = NetworkStats.Bucket()
        while (stats.hasNextBucket()) {
            stats.getNextBucket(b)
            out.getOrPut(b.uid) { LongArray(2) }[slot] += b.rxBytes + b.txBytes
        }
        stats.close()
    }
    return out.mapValues { it.value[0] to it.value[1] }
}

// ------------------------------------------------------------------ live vitals

data class Vitals(
    val at: Long,
    val memTotal: Long,
    val memAvail: Long,
    val lowMemory: Boolean,
    val battery: Int,
    val batteryTempC: Float,
    val charging: Boolean,
    val currentMa: Int,
    val voltageMv: Int,
    val thermal: Int,
    val coreLoad: List<Float>,  // current/max frequency per core; empty if the kernel hides it
    val rx: Long,
    val tx: Long,
)

private fun readLong(path: String): Long? = runCatching { File(path).readText().trim().toLong() }.getOrNull()

fun sampleVitals(ctx: Context): Vitals {
    val am = ctx.getSystemService(ActivityManager::class.java)
    val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
    val bat = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = bat?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = bat?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
    val status = bat?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val bm = ctx.getSystemService(BatteryManager::class.java)
    val cores = (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { i ->
        val cur = readLong("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq") ?: return@mapNotNull null
        val max = readLong("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq") ?: return@mapNotNull null
        (cur.toFloat() / max).coerceIn(0f, 1f)
    }
    val currentUa = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
    return Vitals(
        at = SystemClock.elapsedRealtime(),
        memTotal = mi.totalMem,
        memAvail = mi.availMem,
        lowMemory = mi.lowMemory,
        battery = if (level >= 0) level * 100 / scale else -1,
        batteryTempC = (bat?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f,
        charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
        currentMa = if (currentUa == Int.MIN_VALUE) 0 else currentUa / 1000,
        voltageMv = bat?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0,
        thermal = ctx.getSystemService(PowerManager::class.java).currentThermalStatus,
        coreLoad = cores,
        rx = TrafficStats.getTotalRxBytes(),
        tx = TrafficStats.getTotalTxBytes(),
    )
}

fun thermalLabel(s: Int) = when (s) {
    PowerManager.THERMAL_STATUS_NONE -> "Cool"
    PowerManager.THERMAL_STATUS_LIGHT -> "Warm"
    PowerManager.THERMAL_STATUS_MODERATE -> "Hot, slightly throttled"
    PowerManager.THERMAL_STATUS_SEVERE -> "Very hot, throttled"
    PowerManager.THERMAL_STATUS_CRITICAL, PowerManager.THERMAL_STATUS_EMERGENCY, PowerManager.THERMAL_STATUS_SHUTDOWN -> "Critical"
    else -> "Unknown"
}

/**
 * Asks Android to stop other apps' cached background processes. Android restarts what it
 * needs, so the gain is usually temporary; it never touches the app you're using.
 */
fun closeBackgroundApps(ctx: Context, apps: List<AppInfo>): Int {
    val am = ctx.getSystemService(ActivityManager::class.java)
    var n = 0
    for (a in apps) if (!a.system && a.pkg != ctx.packageName) { am.killBackgroundProcesses(a.pkg); n++ }
    return n
}
