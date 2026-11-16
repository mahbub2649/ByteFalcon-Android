package app.diskscan

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import app.diskscan.ui.AppState
import app.diskscan.ui.AppsScreen
import app.diskscan.ui.Bg
import app.diskscan.ui.Brand
import app.diskscan.ui.DeviceScreen
import app.diskscan.ui.DeviceState
import app.diskscan.ui.DiskscanTheme
import app.diskscan.ui.Faint
import app.diskscan.ui.GlassBackground
import app.diskscan.ui.HomeScreen
import app.diskscan.ui.MonitorScreen
import app.diskscan.ui.PermissionScreen
import app.diskscan.ui.ResultsScreen
import app.diskscan.ui.ScanningScreen
import app.diskscan.ui.Screen
import app.diskscan.ui.SecurityScreen
import app.diskscan.ui.SystemState
import app.diskscan.ui.TextC
import app.diskscan.ui.glass
import java.io.File

class MainActivity : ComponentActivity() {
    private var hasAccess by mutableStateOf(false)
    private var resumes by mutableIntStateOf(0)
    private var sensorPerms by mutableStateOf(false)

    private val clearCaches = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        Toast.makeText(this, "Rescan to see the space you got back.", Toast.LENGTH_SHORT).show()
    }
    private val askStorage = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasAccess = storageGranted()
    }
    private val askSensorPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        sensorPerms = sensorPermsGranted()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Engine.init(storageVolumes())
        setContent { DiskscanApp(hasAccess, resumes, sensorPerms, ::requestAccess, ::clearAppCaches, ::openFile, ::requestSensorPerms) }
    }

    override fun onResume() {
        super.onResume()
        hasAccess = storageGranted()  // the user may have just granted it in Settings
        sensorPerms = sensorPermsGranted()
        resumes++
    }

    private val legacyStorage = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R
    private val legacyPerms = arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)

    private fun storageGranted() =
        if (legacyStorage) legacyPerms.all { checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
        else Environment.isExternalStorageManager()

    private fun sensorPermsGranted() = listOf(android.Manifest.permission.ACTIVITY_RECOGNITION, android.Manifest.permission.BODY_SENSORS)
        .all { checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }

    private fun requestSensorPerms() =
        askSensorPerms.launch(arrayOf(android.Manifest.permission.ACTIVITY_RECOGNITION, android.Manifest.permission.BODY_SENSORS))

    private fun storageVolumes(): List<Pair<String, String>> {
        val sm = getSystemService(StorageManager::class.java)
        if (!legacyStorage) return sm.storageVolumes.mapNotNull { v ->
            v.directory?.let { dir -> dir.absolutePath to if (v.isPrimary) "Internal storage" else v.getDescription(this) }
        }
        // Android 10: StorageVolume.directory doesn't exist; derive each volume's root from its app folder.
        return getExternalFilesDirs(null).filterNotNull().mapNotNull { f ->
            val root = f.absolutePath.substringBefore("/Android/data/", "").ifEmpty { return@mapNotNull null }
            val v = sm.getStorageVolume(File(root))
            root to if (v == null || v.isPrimary) "Internal storage" else v.getDescription(this)
        }
    }

    private fun requestAccess() {
        if (legacyStorage) { askStorage.launch(legacyPerms); return }
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    /** Android's own "clear all app caches" dialog; available to apps holding All files access. */
    private fun clearAppCaches() {
        try {
            clearCaches.launch(Intent(StorageManager.ACTION_CLEAR_APP_CACHE))
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
        }
    }

    /** Hands a file to the app that handles its type, with read access to that one file only. */
    private fun openFile(path: String) {
        val file = File(path)
        val uri = try {
            FileProvider.getUriForFile(this, "$packageName.files", file)
        } catch (e: IllegalArgumentException) {
            Toast.makeText(this, "Can't share files from this location", Toast.LENGTH_SHORT).show(); return
        }
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(view)
        } catch (e: ActivityNotFoundException) {
            try {  // nothing claims this type: let the user pick any app that opens files
                startActivity(Intent.createChooser(view.setDataAndType(uri, "*/*"), file.name))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, "No app on this phone can open ${file.extension.uppercase()} files", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

private enum class Section(val label: String) { Storage("Storage"), Monitor("Monitor"), Device("Device"), Apps("Apps"), Security("Security") }

@Composable
private fun DiskscanApp(
    hasAccess: Boolean,
    resumes: Int,
    sensorPerms: Boolean,
    onRequestAccess: () -> Unit,
    onClearAppCaches: () -> Unit,
    openFile: (String) -> Unit,
    onRequestSensorPerms: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toast = remember { { msg: String -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() } }
    val state = remember { AppState(scope, toast, openFile) }
    val sys = remember { SystemState(scope, context.applicationContext, toast) }
    val dev = remember { DeviceState(scope, context.applicationContext, toast) }
    dev.hasSensorPerms = sensorPerms
    var section by remember { mutableStateOf(Section.Storage) }
    LaunchedEffect(hasAccess) { if (hasAccess) state.refreshVolumes() }
    LaunchedEffect(resumes) { if (resumes > 1) sys.onResume() }

    DiskscanTheme {
        Box(Modifier.fillMaxSize().background(Bg)) {
            GlassBackground()
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).consumeWindowInsets(WindowInsets.navigationBars)) {
                    Crossfade(section, label = "section") { s ->
                        when (s) {
                            Section.Storage -> Crossfade(if (!hasAccess) null else state.screen, label = "screen") { screen ->
                                when (screen) {
                                    null -> PermissionScreen(onRequestAccess)
                                    Screen.Start -> HomeScreen(state)
                                    Screen.Scanning -> ScanningScreen(state)
                                    Screen.Results -> ResultsScreen(state, onClearAppCaches)
                                }
                            }
                            Section.Monitor -> MonitorScreen(sys)
                            Section.Device -> DeviceScreen(dev, onRequestSensorPerms)
                            Section.Apps -> AppsScreen(sys)
                            Section.Security -> SecurityScreen(sys) { app -> sys.selected = app; section = Section.Apps }
                        }
                    }
                }
                BottomNav(section) { section = it }
            }
        }
    }
}

@Composable
private fun BottomNav(current: Section, onSelect: (Section) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Bg.copy(alpha = .55f)).glass(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .navigationBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        for (s in Section.entries) {
            val on = s == current
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable { onSelect(s) }.padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.width(28.dp).height(4.dp).clip(RoundedCornerShape(99.dp)).then(if (on) Modifier.background(Brand) else Modifier))
                Spacer(Modifier.height(6.dp))
                Text(s.label, fontSize = 13.sp, color = if (on) TextC else Faint, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}
