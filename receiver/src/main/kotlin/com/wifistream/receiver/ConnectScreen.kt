package com.wifistream.receiver

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun ConnectScreen(vm: AppViewModel) {
    val found by vm.found.collectAsStateWithLifecycle()
    var address by rememberSaveable { mutableStateOf("") }
    val firstFound = remember { FocusRequester() }

    // With a remote, start on the first phone found so one press of OK connects.
    LaunchedEffect(found.isNotEmpty()) {
        if (found.isNotEmpty()) runCatching { firstFound.requestFocus() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = screenMargin(), vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle("Hassasin's Stream", onBack = if (vm.backStack.size > 1) vm::back else null)
        Text("Choose the device to play from", color = Muted, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(4.dp))
        // TVs show a code for the phone to scan: no typing, and it works without a router too.
        if (LocalIsTv.current) TvPairCard(vm)

        if (found.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(
                    "  Looking for devices on this Wi-Fi. On the device that holds the videos, open Hassasin's Stream and turn on Share this device.",
                    color = Muted,
                )
            }
        }
        found.forEachIndexed { index, sender ->
            FocusRow(
                onClick = { vm.connect(sender.address) },
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .then(if (index == 0) Modifier.focusRequester(firstFound) else Modifier),
            ) {
                RowIcon(R.drawable.ic_phone)
                Column {
                    Text(sender.name, style = MaterialTheme.typography.titleMedium)
                    Text(sender.address.removePrefix("http://"), color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("Or type the address shown on the sharing device", color = Muted)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                singleLine = true,
                placeholder = { Text("192.168.1.5") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { vm.connect(address) }),
                // The field gives way first, so the button always fits beside it.
                modifier = Modifier.weight(1f, fill = false).widthIn(max = 320.dp),
            )
            PillButton("Connect", onClick = { if (address.isNotBlank() && !vm.connecting) vm.connect(address) }, primary = true)
            if (vm.connecting) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        }
        vm.connectError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        if (canShareFromThisDevice()) {
            Spacer(Modifier.height(16.dp))
            Text("Are the videos on this device?", color = Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton("Share this device", onClick = { vm.open(Screen.Share) })
            }
            Text("Watching on a TV? Scan the code it shows to connect it to this phone.", color = Muted)
            ScanTvButton()
        }
    }
}
