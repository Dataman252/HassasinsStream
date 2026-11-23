package com.wifistream.receiver

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.launch

/**
 * The TV's pairing card: a QR code the phone scans to connect. On a shared Wi-Fi it holds the TV's
 * address; "Direct link" makes the TV's own small network for places without a router.
 */
@Composable
fun TvPairCard(vm: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val code = remember { PairCode.newCode() }
    val group = remember { DirectGroup(context.applicationContext) }
    var direct by remember { mutableStateOf<Pair<String, String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    // Listening for the phone the whole time the card is on screen.
    DisposableEffect(code) {
        val listener = PairListener(code) { address -> android.os.Handler(android.os.Looper.getMainLooper()).post { vm.connect(address) } }
        listener.start()
        onDispose { listener.stop() }
    }
    // The direct network stays up once connected over it; it goes when the app closes.
    DisposableEffect(Unit) { onDispose { if (vm.connection == null) group.remove() } }

    fun startDirect() {
        busy = true
        problem = null
        scope.launch {
            group.create()
                .onSuccess { direct = it }
                .onFailure { problem = (it.message ?: "Could not start a direct network.") + " Use the phone's hotspot instead: turn it on, join it from this TV's Wi-Fi settings, then scan." }
            busy = false
        }
    }
    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startDirect() else problem = "A direct link needs the nearby-devices permission."
    }

    val content = direct?.let { (ssid, pass) -> PairCode("${DirectGroup.OWNER_ADDRESS}:${PairCode.PORT}", code, ssid, pass) }
        ?: tvAddress()?.let { PairCode("$it:${PairCode.PORT}", code) }
    val image = remember(content) { content?.let { qrBitmap(it.toUri()) } }

    Row(
        Modifier.widthIn(max = 900.dp).background(Panel, RoundedCornerShape(20.dp)).padding(20.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(200.dp).background(Color.White, RoundedCornerShape(12.dp)).padding(8.dp), contentAlignment = Alignment.Center) {
            if (image != null) Image(image, contentDescription = "Pairing code", modifier = Modifier.size(184.dp))
            else Text("Connect this TV to Wi-Fi first, or make a direct link.", color = Color.Black, modifier = Modifier.padding(8.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Connect with your phone", style = MaterialTheme.typography.titleLarge)
            Text(
                if (direct != null) {
                    "Direct link is on (${direct!!.first}). On the phone: Hassasin's Stream › Share this device › Scan TV code. " +
                        "The phone joins this TV's own network; no router needed."
                } else {
                    "On the phone: Hassasin's Stream › Share this device › Scan TV code. It turns sharing on and this TV connects by itself."
                },
                color = Muted,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text("Code $code", color = Muted, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (direct == null) {
                    PillButton("No router? Direct link", onClick = {
                        if (!group.supported) {
                            problem = "This TV has no Wi-Fi Direct. Use the phone's hotspot: turn it on, join it from this TV's Wi-Fi settings, then scan."
                        } else if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
                            startDirect()
                        } else {
                            ask.launch(permission)
                        }
                    })
                } else {
                    PillButton("Stop direct link", onClick = {
                        group.remove()
                        direct = null
                    })
                }
                PillButton("Wi-Fi settings", onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) } })
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** The phone's "Scan TV code" button and what happens after the scan. */
@Composable
fun ScanTvButton(primary: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PillButton("Scan TV code", primary = primary, onClick = {
            val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
            GmsBarcodeScanning.getClient(context, options).startScan()
                .addOnSuccessListener { barcode ->
                    val code = PairCode.parse(barcode.rawValue.orEmpty())
                    if (code == null) {
                        failed = true
                        message = "That is not a Hassasin's Stream code. Open the app on the TV to show it."
                        return@addOnSuccessListener
                    }
                    failed = false
                    scope.launch {
                        pairWithTv(context, code) { step -> message = step }
                            .onSuccess { message = it }
                            .onFailure {
                                failed = true
                                message = it.message
                            }
                    }
                }
                .addOnFailureListener {
                    failed = true
                    message = "The scanner could not open: ${it.message ?: "Google Play services are needed."}"
                }
        })
        message?.let { Text(it, color = if (failed) MaterialTheme.colorScheme.error else Muted, style = MaterialTheme.typography.bodySmall) }
    }
}
