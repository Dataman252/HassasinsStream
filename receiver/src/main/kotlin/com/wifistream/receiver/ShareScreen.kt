package com.wifistream.receiver

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wifistream.protocol.EntryKind
import com.wifistream.protocol.kindForFileName
import com.wifistream.sender.ShareService
import com.wifistream.sender.ShareState
import com.wifistream.sender.Storage
import java.io.File

private val StatusOn = Color(0xFF2E9E5B)

private data class Setup(val allFiles: Boolean, val notifications: Boolean, val battery: Boolean)

@RequiresApi(30)
private fun checkSetup(context: Context) = Setup(
    allFiles = Environment.isExternalStorageManager(),
    notifications = Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
    battery = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
)

/** Share this device: on/off, the address, permissions, and which folders other devices may see. */
@RequiresApi(30)
@Composable
fun ShareScreen(onBack: () -> Unit, onKeys: (() -> Unit)? = null, keys: ApiKeys? = null) {
    val context = LocalContext.current
    val status by ShareState.status.collectAsStateWithLifecycle()
    // Show whether the keys work as soon as the screen opens.
    androidx.compose.runtime.LaunchedEffect(keys) { keys?.check() }
    val storage = remember { Storage(context.applicationContext) }

    // Permissions are granted in system screens, so re-read them every time we come back.
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }
    val setup = remember(refresh) { checkSetup(context) }
    val shared = remember(refresh, setup.allFiles) { if (setup.allFiles) storage.roots() else emptyList() }
    // A shared drive that is unplugged or plugged back in shows up here without leaving the screen.
    LaunchedEffect(Unit) {
        var drives = storage.volumes().map { it.dir }
        while (true) {
            kotlinx.coroutines.delay(2_000)
            val now = storage.volumes().map { it.dir }
            if (now != drives) {
                drives = now
                refresh++
            }
        }
    }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val appUri = Uri.parse("package:${context.packageName}")

    // The folder picker is a sub-page of this screen; null means it is closed.
    var picking by remember { mutableStateOf(false) }
    if (picking) {
        FolderPicker(storage, onDone = {
            picking = false
            refresh++
        })
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(start = screenMargin(), end = screenMargin(), top = 20.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { ScreenTitle("Share this device", onBack = onBack) }

        item {
            FocusRow(
                onClick = {
                    when {
                        !setup.allFiles ->
                            context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, appUri))
                        status.running -> ShareService.stop(context)
                        else -> ShareService.start(context)
                    }
                },
            ) {
                Box(Modifier.size(10.dp).background(if (status.running) StatusOn else Muted, CircleShape))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (status.running) "Sharing is on" else "Sharing is off",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        when {
                            status.error != null -> status.error!!
                            !setup.allFiles -> "Tap to allow file access first."
                            !status.running && shared.isEmpty() -> "Choose folders below, then tap to turn on."
                            !status.running -> "Tap to turn on."
                            status.address == null -> "Connect this device to Wi-Fi, or turn on its hotspot."
                            status.discoverable -> "Other devices find this one automatically as \"${status.deviceName}\"."
                            else -> "If the other device does not find this one, type the address below into it."
                        },
                        color = if (status.error != null) MaterialTheme.colorScheme.error else Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(if (status.running) "On" else "Off", fontWeight = FontWeight.SemiBold)
            }
        }
        if (status.running && status.address != null) {
            item {
                SelectionContainer {
                    Text(
                        "${status.address}   ·   ${status.scannedFiles} media files scanned",
                        fontFamily = FontFamily.Monospace,
                        color = Muted,
                    )
                }
            }
        }

        if (!setup.allFiles || !setup.notifications || !setup.battery) {
            item { Text("Setup", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp)) }
            if (!setup.allFiles) {
                item {
                    SetupRow("Allow file access", "Needed to read your videos and music, including USB drives.") {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, appUri))
                    }
                }
            }
            if (!setup.notifications) {
                item {
                    SetupRow("Allow the notification", "Shows that sharing is on, with a Stop button.") {
                        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }
            if (!setup.battery) {
                item {
                    SetupRow("Keep running with the screen off", "Stops the phone from pausing sharing to save battery.") {
                        @Suppress("BatteryLife")
                        context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, appUri))
                    }
                }
            }
        }

        if (status.running) {
            item { HealthCard(status.temperatureC, status.previewsPaused) }
        }
        item { LockChecklist(battery = setup.battery, onBattery = {
            @Suppress("BatteryLife")
            context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, appUri))
        }) }
        item { EventLog() }

        item {
            FocusRow(onClick = {
                // Apps may not switch the hotspot on themselves; this opens the system screen that does.
                val opened = runCatching { context.startActivity(Intent("android.settings.TETHER_SETTINGS")) }.isSuccess
                if (!opened) runCatching { context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
            }) {
                Column(Modifier.weight(1f)) {
                    Text("No Wi-Fi router? Use this phone's hotspot", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Turn the hotspot on, connect the TV to it, and keep sharing on. The TV finds this phone by itself. " +
                            "Playing uses no mobile data; posters and online subtitles do.",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text("Open", fontWeight = FontWeight.SemiBold)
            }
        }
        if (status.driveDrops > 0) {
            item {
                val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(status.lastDropAt))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0x33F59E0B), RoundedCornerShape(16.dp))
                        .border(1.dp, Color(0x99F59E0B), RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        if (status.driveMissing) "The USB drive is disconnected" else
                            "The USB drive dropped out ${status.driveDrops} time${if (status.driveDrops == 1) "" else "s"} (last at $time)",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "It lost its connection without being ejected. This is almost always power or the plug: the phone powers the drive from its " +
                            "battery, and a fast SSD can briefly need more than the phone gives. Use a USB-C hub or OTG adapter with a charger " +
                            "plugged in, and a short cable pushed in firmly. Playback carries on once the drive is back.",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        if (onKeys != null) {
            item {
                FocusRow(onClick = onKeys) {
                    Column(Modifier.weight(1f)) {
                        Text("API keys", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "For posters and subtitles on every device watching from this phone. They never need keys of their own.",
                            color = Muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        // Under the text, so a long status ("Could not check: no internet") never squeezes it.
                        KeyStates(keys?.status, Modifier.padding(top = 6.dp), Alignment.Start)
                    }
                }
            }
        }
        item {
            FocusRow(onClick = {}) {
                Column(Modifier.weight(1f)) {
                    Text("Connection speed", style = MaterialTheme.typography.titleMedium)
                    Text(
                        status.lastSpeedMbps?.let { "Last test from a watching device: ${Link.verdict(it)}" }
                            ?: "Run a test on the TV: Settings, Test connection speed. The result shows here too.",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                status.lastSpeedMbps?.let { Text(mbpsLabel(it), fontWeight = FontWeight.SemiBold) }
            }
        }
        item { Text("Shared folders", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp)) }
        if (!setup.allFiles) {
            item { Text("Folders can be chosen once file access is allowed.", color = Muted) }
        } else {
            if (shared.isEmpty()) {
                item { Text("Nothing is shared yet. Only the folders you choose are visible to other devices.", color = Muted) }
            }
            items(shared, key = { it.dir.absolutePath }) { root ->
                FocusRow(onClick = {
                    storage.setShared(root.dir, false)
                    refresh++
                }) {
                    RowIcon(R.drawable.ic_folder)
                    Column(Modifier.weight(1f)) {
                        Text(root.info.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(root.dir.absolutePath, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text("Remove", color = Muted)
                }
            }
            item { PillButton("Choose folders", onClick = { picking = true }, primary = shared.isEmpty()) }
        }
    }
}

/** The phone's warmth while sharing; seek-bar pictures pause by themselves when it gets warm. */
@Composable
private fun HealthCard(temperatureC: Float, paused: Boolean) {
    val warm = paused || temperatureC >= 40f
    val (dot, text) = when {
        temperatureC <= 0f -> Muted to "Temperature not reported by this phone"
        warm -> Color(0xFFF59E0B) to "Warm · ${"%.0f".format(temperatureC)} °C · seek-bar pictures paused to cool down"
        else -> StatusOn to "Steady · ${"%.0f".format(temperatureC)} °C"
    }
    FocusRow(onClick = {}) {
        Box(Modifier.size(10.dp).background(dot, CircleShape))
        Column(Modifier.weight(1f)) {
            Text("Phone health", style = MaterialTheme.typography.titleMedium)
            Text(text, color = Muted, style = MaterialTheme.typography.bodySmall)
            if (warm) {
                Text(
                    "Playback is not affected. Pictures carry on once the phone is below 38 °C. Charging while sharing and a case make it warmer.",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * Everything that lets sharing survive a locked screen. Phone makers add their own background limits
 * on top of Android's (vivo and Samsung especially), and those can only be switched off by hand.
 */
@Composable
private fun LockChecklist(battery: Boolean, onBattery: () -> Unit) {
    val context = LocalContext.current
    val maker = Build.MANUFACTURER.lowercase()
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Keep sharing when the screen locks", style = MaterialTheme.typography.titleMedium)
        CheckLine(battery, "Battery: unrestricted", if (battery) null else "Fix", onBattery)
        val makerStep = when {
            "vivo" in maker || "iqoo" in maker -> "vivo: Battery › Background power use › allow, and Autostart on"
            "samsung" in maker -> "Samsung: Battery › Never sleeping apps › add Hassasin's Stream"
            "xiaomi" in maker || "redmi" in maker || "poco" in maker -> "Xiaomi: Autostart on, Battery saver › No restrictions"
            "oppo" in maker || "realme" in maker || "oneplus" in maker -> "Battery › Allow background activity and Auto launch"
            else -> "App battery settings: allow background activity"
        }
        CheckLine(null, makerStep, "Open") { openMakerSettings(context) }
        CheckLine(null, "Wi-Fi: keep on during sleep (some phones turn it off when locked)", "Open") {
            runCatching { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        }
        CheckLine(true, "Sharing restarts by itself if the phone stops it", null) {}
    }
}

@Composable
private fun CheckLine(ok: Boolean?, text: String, action: String?, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.size(10.dp).background(
                when (ok) {
                    true -> StatusOn
                    false -> Color(0xFFF59E0B)
                    null -> Muted
                },
                CircleShape,
            ),
        )
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (action != null) PillButton(action, onClick = onClick, primary = ok == false)
    }
}

/** Opens the phone maker's own background or autostart screen, falling back to the app's info page. */
private fun openMakerSettings(context: Context) {
    val candidates = listOf(
        Intent().setClassName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        Intent().setClassName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
        Intent().setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        Intent().setClassName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        Intent().setClassName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
    )
    for (intent in candidates) {
        if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }
    runCatching {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
    }
}

/** What happened to sharing lately, so a drop with the screen locked can be explained. */
@Composable
private fun EventLog() {
    val context = LocalContext.current
    LaunchedEffect(Unit) { ShareState.loadEvents(context.applicationContext) }
    val events by ShareState.events.collectAsStateWithLifecycle()
    if (events.isEmpty()) return
    val format = remember { java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale.getDefault()) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Recent events", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            PillButton("Clear", onClick = { ShareState.clearEvents(context.applicationContext) })
        }
        events.take(12).forEach { e ->
            Text("${format.format(java.util.Date(e.at))}   ${e.text}", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SetupRow(title: String, detail: String, onClick: () -> Unit) {
    FocusRow(onClick = onClick) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        Text("Allow", fontWeight = FontWeight.SemiBold)
    }
}

/** Walk the device's drives and folders; "Share" marks the one being looked at. */
@RequiresApi(30)
@Composable
private fun FolderPicker(storage: Storage, onDone: () -> Unit) {
    // Drives come and go (a USB drive plugged in or knocked loose), so the list is re-read every two seconds.
    var volumes by remember { mutableStateOf(storage.volumes()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(2_000)
            val now = storage.volumes()
            if (now.map { it.dir } != volumes.map { it.dir }) volumes = now
        }
    }
    // null = the list of drives; otherwise the folder being browsed.
    var current by remember { mutableStateOf<File?>(null) }
    var changes by remember { mutableIntStateOf(0) }
    val here = current

    BackHandler {
        val folder = current
        when {
            folder == null -> onDone()
            volumes.any { it.dir == folder } -> current = null
            else -> current = folder.parentFile
        }
    }

    // null means Android refused to list the folder, which is different from the folder being empty.
    val children = remember(here) { here?.listFiles() }
    val folders = remember(children) {
        children.orEmpty().filter { it.isDirectory && !it.name.startsWith(".") }.sortedBy { it.name.lowercase() }
    }
    val mediaHere = remember(children) {
        children.orEmpty().count { it.isFile && kindForFileName(it.name).let { kind -> kind == EntryKind.VIDEO || kind == EntryKind.AUDIO } }
    }
    val sharedHere = remember(here, changes) { here != null && storage.isShared(here) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = screenMargin(), end = screenMargin(), top = 20.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ScreenTitle(
                title = here?.let { folder -> volumes.firstOrNull { it.dir == folder }?.label ?: folder.name } ?: "Choose folders",
                onBack = {
                    val folder = current
                    when {
                        folder == null -> onDone()
                        volumes.any { it.dir == folder } -> current = null
                        else -> current = folder.parentFile
                    }
                },
            )
        }
        if (here == null) {
            item { Text("Open a drive, go to the folder you want, and share it. Share a drive itself to share everything on it.", color = Muted) }
            items(volumes, key = { it.dir.absolutePath }) { volume ->
                FocusRow(onClick = { current = volume.dir }) {
                    RowIcon(R.drawable.ic_storage)
                    Text(volume.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (remember(changes) { storage.isShared(volume.dir) }) Text("Shared", color = Muted)
                }
            }
        } else {
            item {
                Text(here.absolutePath, color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            item {
                PillButton(
                    if (sharedHere) "Stop sharing this folder" else "Share this folder",
                    onClick = {
                        storage.setShared(here, !sharedHere)
                        changes++
                    },
                    primary = !sharedHere,
                )
            }
            item {
                Text(
                    when {
                        children == null ->
                            "Android did not let the app read this location. If this is a USB drive, unplug it, plug it back in, " +
                                "wait for the phone to say it is ready, and try again."
                        mediaHere > 0 && folders.isEmpty() -> "$mediaHere videos or music files are directly in here. Share this folder to include them."
                        mediaHere > 0 -> "$mediaHere videos or music files are directly in here, plus the folders below."
                        folders.isEmpty() -> "Nothing in here: no folders and no videos or music."
                        else -> "Folders inside:"
                    },
                    color = if (children == null) MaterialTheme.colorScheme.error else Muted,
                )
            }
            items(folders, key = { it.absolutePath }) { folder ->
                FocusRow(onClick = { current = folder }) {
                    RowIcon(R.drawable.ic_folder)
                    Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (remember(changes) { storage.isShared(folder) }) Text("Shared", color = Muted)
                }
            }
        }
        item { PillButton("Done", onClick = onDone) }
    }
}
