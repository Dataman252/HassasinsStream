package com.wifistream.receiver

import android.text.format.Formatter
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import com.wifistream.protocol.MediaInfo
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
private fun Heading(text: String) {
    Text(text, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp))
}

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = Muted, modifier = Modifier.width(150.dp))
        Text(value, fontFamily = FontFamily.Monospace)
    }
}

private fun yesNo(value: Boolean) = if (value) "Yes" else "No"

/** Settings → This device: what the screen, the video decoders and the sound output can do. */
@Composable
fun CapabilitiesScreen(vm: AppViewModel) {
    val caps = vm.capabilities
    LazyColumn(
        contentPadding = PaddingValues(start = screenMargin(), end = screenMargin(), top = 20.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item { ScreenTitle("What this device can play", onBack = vm::back) }
        item {
            Text(
                "Read from this device and the screen or TV it is connected to right now. " +
                    "A different TV or HDMI port can change the HDR and sound results.",
                color = Muted,
            )
        }

        item {
            Column(
                modifier = Modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("This setup's limit", color = Muted, style = MaterialTheme.typography.bodySmall)
                Text(caps.headline(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                caps.limits().forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }

        item { Heading("Screen") }
        item { Fact("Resolution", "${caps.screenWidth}×${caps.screenHeight}") }
        item { Fact("Refresh rates", caps.refreshRates.joinToString(", ") { "%.0f".format(it) } + " Hz") }
        item { Fact("HDR10", yesNo(caps.hdr10)) }
        item { Fact("HDR10+", yesNo(caps.hdr10Plus)) }
        item { Fact("HLG", yesNo(caps.hlg)) }
        item { Fact("Dolby Vision", yesNo(caps.dolbyVision)) }

        item { Heading("Video formats") }
        items(caps.video) { decoder ->
            Fact(
                decoder.label,
                listOfNotNull(
                    "up to ${decoder.maxWidth}×${decoder.maxHeight}",
                    if (decoder.hardware) "hardware" else "software",
                    "10-bit".takeIf { decoder.tenBit },
                    decoder.dolbyVisionProfiles.takeIf { it.isNotEmpty() }?.let { "profiles ${it.joinToString(", ")}" },
                ).joinToString("  ·  "),
            )
        }
        if (caps.video.none { it.label == "Dolby Vision" }) item { Fact("Dolby Vision", "no decoder") }
        if (caps.video.none { it.label == "AV1" }) item { Fact("AV1", "no decoder") }

        item { Heading("Sound formats") }
        items(caps.audio) { sound ->
            Fact(
                sound.label,
                listOfNotNull(
                    "passthrough".takeIf { sound.passthrough },
                    "device decoder".takeIf { sound.deviceDecoder },
                    "app software decoder".takeIf { sound.softwareDecoder },
                ).joinToString("  ·  ").ifEmpty { "not supported" },
            )
        }
        item {
            Text(
                "Passthrough sends the untouched stream to a TV or soundbar that decodes it. " +
                    "It is ${if (vm.store.passthrough) "on" else "off"} in this app's audio settings.",
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** The "will it play here" block on a detail page, with the real test and the properties page behind buttons. */
@Composable
fun PlayabilityBlock(vm: AppViewModel, file: VideoFile) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val media: MediaInfo? = file.entry.media
    val verdict = remember(file.key, media, vm.store.version) { media?.let { vm.capabilities.verdictFor(it, vm.store.passthrough) } }
    var testing by remember(file.key) { mutableStateOf(false) }
    var result by remember(file.key) { mutableStateOf<TestResult?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (verdict != null) {
            Text(verdict.headline, fontWeight = FontWeight.SemiBold)
            verdict.notes.forEach { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
        }
        result?.let { test ->
            Text(
                if (test.passed) "Test passed: it started playing." else "Test failed: it did not play.",
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 6.dp),
            )
            test.lines.forEach { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PillButton(
                if (testing) "Testing…" else "Test playback",
                onClick = {
                    val url = vm.urlOf(file) ?: return@PillButton
                    if (testing) return@PillButton
                    testing = true
                    scope.launch {
                        result = runCatching { testPlayback(context, url, vm.store) }
                            .getOrElse { TestResult(false, listOf("The test could not run: ${it.message}")) }
                        testing = false
                    }
                },
            )
            PillButton("Properties", onClick = { vm.open(Screen.Properties(file)) })
        }
    }
}

private fun Format.flags(): String? = listOfNotNull(
    "default".takeIf { selectionFlags and C.SELECTION_FLAG_DEFAULT != 0 },
    "forced".takeIf { selectionFlags and C.SELECTION_FLAG_FORCED != 0 },
).joinToString(", ").ifEmpty { null }

private fun language(code: String?): String? =
    code?.takeIf { it.isNotBlank() && it != "und" }?.let { Locale.forLanguageTag(it).displayLanguage.ifBlank { it } }

/** Every detail of one file: where it is, and each video, sound and subtitle track inside it. */
@OptIn(UnstableApi::class)
@Composable
fun PropertiesScreen(vm: AppViewModel, screen: Screen.Properties) {
    val context = LocalContext.current
    val file = screen.file
    val media = file.entry.media
    var tracks by remember { mutableStateOf<List<Format>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(file.key) {
        val url = vm.urlOf(file)
        val read = url?.let { readTracks(context, it) }
        if (read == null) failed = true else tracks = read
    }

    LazyColumn(
        contentPadding = PaddingValues(start = screenMargin(), end = screenMargin(), top = 20.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item { ScreenTitle("Properties", onBack = vm::back) }

        item { Heading("File") }
        item { Fact("Name", file.entry.name) }
        item { Fact("Folder", file.folder.ifEmpty { "(top of the shared folder)" }) }
        item { Fact("Size", "${Formatter.formatFileSize(context, file.entry.sizeBytes)}  (${file.entry.sizeBytes} bytes)") }
        item { Fact("Container", file.entry.name.substringAfterLast('.', "").uppercase()) }
        media?.durationMs?.takeIf { it > 0 }?.let { duration ->
            item { Fact("Duration", formatClock(duration)) }
            item { Fact("Overall bitrate", "%.1f Mbps".format(file.entry.sizeBytes * 8.0 / (duration / 1000.0) / 1_000_000)) }
        }
        vm.titleOf(file)?.let { info -> item { Fact("Matched as", "${info.title} (${info.year ?: "?"})  ·  ${info.sourceLabel} ${info.type} ${info.id}") } }

        val all = tracks
        when {
            failed -> item { Text("Could not read the tracks from the sharing device.", color = MaterialTheme.colorScheme.error) }
            all == null -> item { Text("Reading the tracks…", color = Muted, modifier = Modifier.padding(top = 14.dp)) }
            else -> {
                val video = all.filter { MimeTypes.isVideo(it.sampleMimeType) }
                val audio = all.filter { MimeTypes.isAudio(it.sampleMimeType) }
                val text = all.filter { MimeTypes.isText(it.sampleMimeType) || it.sampleMimeType?.startsWith("application/") == true }

                item { Heading("Video") }
                items(video) { v ->
                    Fact(
                        codecLabel(v.sampleMimeType),
                        listOfNotNull(
                            "${v.width}×${v.height}",
                            v.frameRate.takeIf { it > 0 }?.let { "%.3f fps".format(it) },
                            v.bitrate.takeIf { it > 0 }?.let { "%.1f Mbps".format(it / 1_000_000.0) },
                            v.codecs,
                            when (v.colorInfo?.colorTransfer) {
                                C.COLOR_TRANSFER_ST2084 -> "HDR10 (PQ)"
                                C.COLOR_TRANSFER_HLG -> "HLG"
                                C.COLOR_TRANSFER_SDR -> "SDR"
                                else -> null
                            },
                            when (v.colorInfo?.colorSpace) {
                                C.COLOR_SPACE_BT2020 -> "BT.2020"
                                C.COLOR_SPACE_BT709 -> "BT.709"
                                else -> null
                            },
                        ).joinToString("  ·  "),
                    )
                }
                if (video.isEmpty()) item { Text("None", color = Muted) }

                item { Heading("Sound (${audio.size})") }
                items(audio) { a ->
                    Fact(
                        language(a.language) ?: "Unknown",
                        listOfNotNull(
                            codecLabel(a.sampleMimeType),
                            a.channelCount.takeIf { it > 0 }?.let { "$it channels" },
                            a.sampleRate.takeIf { it > 0 }?.let { "$it Hz" },
                            a.bitrate.takeIf { it > 0 }?.let { "${it / 1000} kbps" },
                            a.label,
                            a.flags(),
                        ).joinToString("  ·  "),
                    )
                }
                if (audio.isEmpty()) item { Text("None", color = Muted) }

                item { Heading("Subtitles (${text.size})") }
                items(text) { s ->
                    Fact(
                        language(s.language) ?: "Unknown",
                        listOfNotNull(codecLabel(s.sampleMimeType ?: s.codecs), s.label, s.flags()).joinToString("  ·  "),
                    )
                }
                if (text.isEmpty()) item { Text("None inside the file", color = Muted) }
            }
        }
    }
}
