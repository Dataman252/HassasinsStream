package com.wifistream.receiver

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** Where the owner of a sharing phone enters, checks, imports or backs up its TMDB and OpenSubtitles keys. */
@Composable
fun KeysScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val isTv = LocalIsTv.current
    var tmdb by rememberSaveable(vm.keys.tmdb) { mutableStateOf(vm.keys.tmdb) }
    var openSubtitles by rememberSaveable(vm.keys.openSubtitles) { mutableStateOf(vm.keys.openSubtitles) }
    // Show where the keys stand as soon as the screen opens.
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.keys.check() }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
            vm.importKeys(text.orEmpty())
        }
    }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            val written = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(vm.keys.exportJson().toByteArray()) }
            }.isSuccess
            vm.showKeyStatus(if (written) "Key file saved. Keep it somewhere safe to restore the keys later." else "Could not save the file there.")
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(start = screenMargin(), end = screenMargin(), top = 20.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { ScreenTitle("API keys", onBack = vm::back) }
        item {
            Text(
                "Posters, plots and trailers come from TMDB, and subtitles from OpenSubtitles. Both are free. " +
                    "Only this phone needs the keys: TVs and phones watching from it ask this phone, which looks things up for them. " +
                    "They never receive the keys themselves.",
                color = Muted,
            )
        }
        vm.keyStatus?.let { status -> item { Text(status, fontWeight = FontWeight.SemiBold) } }

        item { SectionHeading("TMDB — posters, plots, trailers", vm.keys.status.tmdb) }
        item {
            Steps(
                "On a phone or computer, open themoviedb.org and create a free account.",
                "Open Settings, then API, and request a key. Choose Developer, and Personal as the type of use.",
                "Copy either the short API Key or the long API Read Access Token and enter it below. " +
                    "The short one is easier to type.",
            )
        }
        item {
            OutlinedTextField(
                value = tmdb,
                onValueChange = { tmdb = it },
                singleLine = true,
                label = { Text("TMDB key") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp),
            )
        }

        item { SectionHeading("OpenSubtitles — subtitle downloads", vm.keys.status.openSubtitles) }
        item {
            Steps(
                "Create a free account at opensubtitles.com (the .com site).",
                "Open your profile menu, choose API consumers, then New consumer. Give it any name and tick Under dev.",
                "Copy the API key it shows and enter it below.",
            )
        }
        item {
            OutlinedTextField(
                value = openSubtitles,
                onValueChange = { openSubtitles = it },
                singleLine = true,
                label = { Text("OpenSubtitles API key") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp),
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Save and check", onClick = { vm.saveKeys(tmdb, openSubtitles) }, primary = true)
                PillButton("Check again", onClick = vm::checkOwnKeys)
            }
        }

        item { Text("Import or back up", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp)) }
        item {
            Text(
                "A key file ($KEY_FILE_NAME) holds both keys. Save one to keep the keys safe, and import it after a reset.",
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!isTv) {
                    PillButton("Import from a file", onClick = {
                        try {
                            pickFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                        } catch (e: ActivityNotFoundException) {
                            vm.showKeyStatus("This device has no file picker.")
                        }
                    })
                    PillButton("Save keys to a file", onClick = {
                        try {
                            saveFile.launch(KEY_FILE_NAME)
                        } catch (e: ActivityNotFoundException) {
                            vm.showKeyStatus("This device has no file picker.")
                        }
                    })
                }
            }
        }
    }
}

@Composable
private fun SectionHeading(title: String, state: com.wifistream.protocol.KeyState) {
    Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold)
        Text(state.label(), color = state.colour(), fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Steps(vararg steps: String) {
    Text(
        steps.mapIndexed { index, step -> "${index + 1}. $step" }.joinToString("\n"),
        color = Muted,
        style = MaterialTheme.typography.bodySmall,
    )
}
