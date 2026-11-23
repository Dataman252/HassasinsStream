package com.wifistream.receiver

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.drawText
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/** What the sound panels need from the player screen. */
class SoundUi(
    val sound: SoundSettings,
    val set: (SoundSettings) -> Unit,
    val band: Int,
    val selectBand: (Int) -> Unit,
    val userPresets: Map<String, EqSnapshot>,
    val savePreset: () -> Unit,
    val deletePreset: (String) -> Unit,
    /** Null on TVs, which have no file picker. */
    val importFile: (() -> Unit)?,
    val exportFile: (() -> Unit)?,
    val isMusic: Boolean,
    val passedThrough: Boolean,
    /** A remote cannot touch the graph, so TVs pick bands from a row of buttons instead. */
    val isTv: Boolean = false,
    /** Impulse responses kept in the app: file name to a short description. */
    val impulses: List<Pair<String, String>> = emptyList(),
    /** Opens the file picker for a .wav (null on TVs). */
    val importImpulse: (() -> Unit)? = null,
    val deleteImpulse: (String) -> Unit = {},
)

private const val GRAPH_DB = 15f

private fun xOf(hz: Float, w: Float) = (log10(hz / 20f) / 3f) * w
private fun hzOf(x: Float, w: Float) = (20f * 1000f.pow((x / w).coerceIn(0f, 1f))).coerceIn(20f, 20_000f)
private fun yOf(db: Float, h: Float) = h / 2 - (db / GRAPH_DB).coerceIn(-1f, 1f) * (h / 2 - 10f)
private fun dbOf(y: Float, h: Float) = ((h / 2 - y) / (h / 2 - 10f) * GRAPH_DB).coerceIn(-GRAPH_DB, GRAPH_DB)

/** The bands drawn as dots, in the current mode. */
private fun SoundSettings.editableBands(): List<EqBand> =
    if (mode == EqMode.GRAPHIC) graphic.mapIndexed { i, g -> EqBand(GRAPHIC_HZ[i], g, 1.41f) } else bands

/**
 * The equalizer curve, which can be shaped by hand: drag a dot sideways for its frequency (parametric)
 * and up or down for its gain, pinch for its width. Touching it switches the preset to Custom.
 */
@Composable
fun EqGraph(ui: SoundUi) {
    val current by rememberUpdatedState(ui)
    val s = ui.sound
    val dots = s.editableBands()
    val curve = s.allBands()
    val eqOnly = s.eqBands()
    val toneShown = curve.size != eqOnly.size
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val small = androidx.compose.ui.text.TextStyle(color = Color(0xB3FFFFFF), fontSize = androidx.compose.ui.unit.TextUnit(10f, androidx.compose.ui.unit.TextUnitType.Sp))
    val label = small.copy(color = Color.White, fontSize = androidx.compose.ui.unit.TextUnit(12f, androidx.compose.ui.unit.TextUnitType.Sp), fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(200.dp)
            .glass(RoundedCornerShape(16.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val w = size.width.toFloat()
                    val h = size.height.toFloat() - 22f * density
                    val now = current
                    val list = now.sound.editableBands()
                    if (list.isEmpty()) return@awaitEachGesture
                    // The nearest dot to the finger is the one being moved.
                    val index = list.indices.minByOrNull { i ->
                        val dx = xOf(list[i].hz, w) - down.position.x
                        val dy = yOf(list[i].gainDb, h) - down.position.y
                        dx * dx + dy * dy * 0.25f
                    } ?: return@awaitEachGesture
                    now.selectBand(index)
                    down.consume()
                    do {
                        val event = awaitPointerEvent()
                        val live = current
                        val bands = live.sound.editableBands()
                        val band = bands.getOrNull(index) ?: break
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            // Two fingers: pinch to make the band narrower or wider.
                            val zoom = event.calculateZoom()
                            if (live.sound.mode == EqMode.PARAMETRIC && zoom != 1f) {
                                live.set(live.sound.withBand(index, band.copy(q = (band.q * zoom).coerceIn(0.1f, 10f))))
                            }
                        } else pressed.firstOrNull()?.let { p ->
                            val gain = (dbOf(p.position.y, h) * 2).roundToInt() / 2f
                            val moved = if (live.sound.mode == EqMode.PARAMETRIC) {
                                band.copy(hz = hzOf(p.position.x, w).roundToSignificant(), gainDb = gain)
                            } else {
                                band.copy(gainDb = gain)
                            }
                            if (moved != band) live.set(live.sound.withBand(index, moved))
                        }
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        val w = size.width
        // The bottom strip holds the band labels.
        val h = size.height - 22f * density
        drawLine(Color(0x40FFFFFF), Offset(0f, h / 2), Offset(w, h / 2), 1f)
        listOf(-12f, -6f, 6f, 12f).forEach { db -> drawLine(Color(0x14FFFFFF), Offset(0f, yOf(db, h)), Offset(w, yOf(db, h)), 1f) }
        listOf(50f, 100f, 200f, 500f, 1000f, 2000f, 5000f, 10000f).forEach { hz ->
            drawLine(Color(if (hz == 100f || hz == 1000f || hz == 10000f) 0x2EFFFFFF else 0x12FFFFFF), Offset(xOf(hz, w), 0f), Offset(xOf(hz, w), h), 1f)
        }
        val steps = 160
        val line = Path()
        val fill = Path()
        for (i in 0..steps) {
            val hz = 20f * 1000f.pow(i / steps.toFloat())
            val p = Offset(i / steps.toFloat() * w, yOf(SoundProcessor.responseDb(curve, hz), h))
            if (i == 0) {
                line.moveTo(p.x, p.y)
                fill.moveTo(p.x, h / 2)
                fill.lineTo(p.x, p.y)
            } else {
                line.lineTo(p.x, p.y)
                fill.lineTo(p.x, p.y)
            }
        }
        fill.lineTo(w, h / 2)
        fill.close()
        drawPath(fill, Brush.verticalGradient(listOf(Accent.copy(alpha = 0.28f), Violet.copy(alpha = 0.05f))), style = Fill)
        // With tone shaping on, the equalizer alone is a thin line and the bright line is the total.
        if (toneShown && eqOnly.isNotEmpty()) {
            val eqLine = Path()
            for (i in 0..steps) {
                val hz = 20f * 1000f.pow(i / steps.toFloat())
                val p = Offset(i / steps.toFloat() * w, yOf(SoundProcessor.responseDb(eqOnly, hz), h))
                if (i == 0) eqLine.moveTo(p.x, p.y) else eqLine.lineTo(p.x, p.y)
            }
            drawPath(eqLine, Color(0x80FFFFFF), style = Stroke(width = 2f))
            drawText(measurer, "Bright line: all layers together (quick sound + equalizer + tone)", Offset(10f * density, 6f * density), small)
        }
        drawPath(line, Brush.horizontalGradient(listOf(Violet, Accent)), style = Stroke(width = 5f))
        dots.forEachIndexed { i, band ->
            val center = Offset(xOf(band.hz, w), yOf(band.gainDb, h))
            val chosen = i == ui.band
            if (chosen) drawCircle(Accent.copy(alpha = 0.35f), radius = 22f, center = center)
            drawCircle(if (band.on) Color.White else Color(0x66FFFFFF), radius = if (chosen) 11f else 7f, center = center)
            // Each band's frequency under the graph, so the dots need no separate list.
            val name = measurer.measure(hzLabel(band.hz).replace(" ", ""), if (chosen) label else small)
            val nx = (center.x - name.size.width / 2f).coerceIn(0f, w - name.size.width)
            drawText(name, topLeft = Offset(nx, h + 4f * density))
            if (chosen) {
                val value = measurer.measure(
                    "${hzLabel(band.hz)}  ·  ${if (band.gainDb > 0) "+" else ""}${"%.1f".format(band.gainDb)} dB" +
                        if (s.mode == EqMode.PARAMETRIC) "  ·  Q ${"%.2f".format(band.q)}" else "",
                    label,
                )
                val vx = (center.x - value.size.width / 2f).coerceIn(4f, w - value.size.width - 4f)
                val vy = if (center.y - 34f * density > 0) center.y - 30f * density else center.y + 16f * density
                drawRoundRect(
                    Color(0xCC0B0B1A),
                    Offset(vx - 6f * density, vy - 2f * density),
                    androidx.compose.ui.geometry.Size(value.size.width + 12f * density, value.size.height + 4f * density),
                    androidx.compose.ui.geometry.CornerRadius(8f * density),
                )
                drawText(value, topLeft = Offset(vx, vy))
            }
        }
    }
}

private fun Float.roundToSignificant(): Float = when {
    this < 100f -> roundToInt().toFloat()
    this < 1000f -> (this / 5f).roundToInt() * 5f
    else -> (this / 50f).roundToInt() * 50f
}

/** The settings with one band changed, which also makes the preset "Custom". */
fun SoundSettings.withBand(index: Int, band: EqBand): SoundSettings = when (mode) {
    EqMode.GRAPHIC -> copy(eqOn = true, preset = "Custom", graphic = graphic.toMutableList().also { if (index in it.indices) it[index] = band.gainDb })
    EqMode.PARAMETRIC -> copy(eqOn = true, preset = "Custom", bands = bands.toMutableList().also { if (index in it.indices) it[index] = band })
}

private fun signedDb(v: Float) = (if (v > 0) "+" else "") + "%.1f dB".format(v)

/** The Equalizer panel. */
fun equalizerItems(ui: SoundUi): List<PanelItem> = buildList {
    val s = ui.sound
    val bands = s.editableBands()
    val index = ui.band.coerceIn(0, (bands.size - 1).coerceAtLeast(0))
    add(ToggleRow("Equalizer", if (s.eqOn) "Preset: ${s.preset}" else "Off", on = s.eqOn) { ui.set(s.copy(eqOn = it)) })
    add(CustomRow { EqGraph(ui) })
    if (!ui.isTv) add(NoteRow("Drag a dot to shape the sound" + if (s.mode == EqMode.PARAMETRIC) ", pinch to change its width." else "."))
    if (s.allBands().size != s.eqBands().size) {
        add(NoteRow("Quick sound or tone tuning is on too. The layers add together: the bright line is the total, and the automatic pre-amp allows for all of them."))
    }
    add(ChipsRow("Mode", EqMode.entries.map { it.label }, EqMode.entries.indexOf(s.mode)) { ui.selectBand(0); ui.set(s.copy(mode = EqMode.entries[it])) })

    val names = EQ_PRESETS.map { it.first } + ui.userPresets.keys + "Custom"
    add(ChipsRow("Preset", names, names.indexOf(s.preset)) { i ->
        val name = names[i]
        val builtIn = EQ_PRESETS.firstOrNull { it.first == name }
        when {
            builtIn != null -> ui.set(s.withPreset(name, builtIn.second))
            name in ui.userPresets -> ui.set(s.apply(ui.userPresets.getValue(name), name))
            else -> ui.set(s.copy(eqOn = true, preset = "Custom"))
        }
    })

    if (bands.isNotEmpty()) {
        // On touch screens the dots on the graph are the band picker; a remote needs buttons.
        if (ui.isTv) {
            add(ChipsRow(
                "Band",
                bands.mapIndexed { i, b -> if (s.mode == EqMode.GRAPHIC) hzLabel(b.hz) else "${i + 1} · ${hzLabel(b.hz)}" },
                index,
            ) { ui.selectBand(it) })
        } else {
            add(NoteRow("Editing ${hzLabel(bands[index].hz)}. Tap another dot on the graph to edit it."))
        }
        val band = bands[index]
        // What this band starts as, for its sliders' reset buttons.
        val normal = DEFAULT_PARAMETRIC.getOrNull(index)?.takeIf { s.mode == EqMode.PARAMETRIC } ?: band.copy(gainDb = 0f, q = 1f)
        fun change(new: EqBand) = ui.set(s.withBand(index, new))
        if (s.mode == EqMode.PARAMETRIC) {
            add(ChipsRow("Shape", FilterType.entries.map { it.label }, FilterType.entries.indexOf(band.type)) { change(band.copy(type = FilterType.entries[it])) })
            add(StepperRow(
                "Frequency",
                hzLabel(band.hz),
                fraction = log10(band.hz / 20f) / 3f,
                onSet = { f -> change(band.copy(hz = (20f * 10f.pow(f * 3f)).roundToSignificant().coerceIn(20f, 20_000f))) },
                onReset = { change(band.copy(hz = normal.hz)) },
                changed = band.hz != normal.hz,
            ) { change(band.copy(hz = (band.hz * if (it > 0) 1.06f else 1 / 1.06f).roundToSignificant().coerceIn(20f, 20_000f))) })
        }
        if (band.type != FilterType.LOW_PASS && band.type != FilterType.HIGH_PASS && band.type != FilterType.NOTCH) {
            add(StepperRow(
                "Gain",
                signedDb(band.gainDb),
                fraction = (band.gainDb + 12f) / 24f,
                onSet = { f -> change(band.copy(gainDb = ((f * 24f - 12f) * 2).roundToInt() / 2f)) },
                onReset = { change(band.copy(gainDb = 0f)) },
                changed = band.gainDb != 0f,
            ) { change(band.copy(gainDb = (band.gainDb + it * 0.5f).coerceIn(-15f, 15f))) })
        }
        if (s.mode == EqMode.PARAMETRIC) {
            add(StepperRow(
                "Width (Q)",
                "%.2f".format(band.q),
                fraction = (band.q - 0.1f) / 9.9f,
                detail = "Low is a broad change, high is a narrow one.",
                onSet = { f -> change(band.copy(q = ((0.1f + f * 9.9f) * 20).roundToInt() / 20f)) },
                onReset = { change(band.copy(q = normal.q)) },
                changed = band.q != normal.q,
            ) { change(band.copy(q = ((band.q + it * 0.05f).coerceIn(0.1f, 10f) * 20).roundToInt() / 20f)) })
            add(ToggleRow("Band on", on = band.on) { change(band.copy(on = it)) })
            if (bands.size < MAX_EQ_BANDS) {
                add(ChoiceRow("Add a band", "Up to $MAX_EQ_BANDS.") {
                    ui.set(s.copy(eqOn = true, preset = "Custom", bands = s.bands + EqBand(2000f, 0f, 1f)))
                    ui.selectBand(s.bands.size)
                })
            }
            if (bands.size > 1) {
                add(ChoiceRow("Remove band ${index + 1}") {
                    ui.set(s.copy(preset = "Custom", bands = s.bands.filterIndexed { i, _ -> i != index }))
                    ui.selectBand((index - 1).coerceAtLeast(0))
                })
            }
        }
    }

    add(ToggleRow(
        "Automatic pre-amp",
        "Lowers the volume by the curve's highest lift, so boosts never distort." +
            if (s.autoPreamp) " Now ${signedDb(SoundProcessor.autoPreamp(s.allBands()))}." else "",
        on = s.autoPreamp,
    ) { ui.set(s.copy(autoPreamp = it)) })
    if (!s.autoPreamp) {
        add(StepperRow(
            "Pre-amp",
            signedDb(s.preampDb),
            fraction = (s.preampDb + 15f) / 20f,
            onSet = { f -> ui.set(s.copy(preampDb = ((f * 20f - 15f) * 2).roundToInt() / 2f)) },
            onReset = { ui.set(s.copy(preampDb = 0f)) },
            changed = s.preampDb != 0f,
        ) { ui.set(s.copy(preampDb = (s.preampDb + it * 0.5f).coerceIn(-15f, 5f))) })
    }
    add(ChoiceRow("Save as my preset", "Keeps this curve under its own name.") { ui.savePreset() })
    if (s.preset in ui.userPresets) add(ChoiceRow("Delete \"${s.preset}\"") { ui.deletePreset(s.preset) })
    ui.importFile?.let { add(ChoiceRow("Import AutoEQ or preset file", "A ParametricEQ.txt for your headphones, from autoeq.app.") { it() }) }
    ui.exportFile?.let { add(ChoiceRow("Export to a file", "Same format, for other players or to share.") { it() }) }
    if (ui.passedThrough) add(NoteRow("This track is passed through untouched to the TV or soundbar, so effects cannot act on it. Changing a setting here switches passthrough off."))
}

/** Tone tuning, in the style of HiBy's MSEB: each slider describes the sound, not a frequency. */
fun toneItems(ui: SoundUi): List<PanelItem> = buildList {
    val s = ui.sound
    val t = s.tone
    add(ToggleRow("Tone tuning", "Describe the sound you want; each slider runs from −10 to +10. Adds on top of the equalizer.", on = s.toneOn) { ui.set(s.copy(toneOn = it)) })
    fun slider(label: String, value: Float, detail: String, update: (Float) -> Tone) = add(StepperRow(
        label,
        (if (value > 0) "+" else "") + value.roundToInt(),
        fraction = (value + 10f) / 20f,
        detail = detail,
        onSet = { f -> ui.set(s.copy(toneOn = true, tone = update((f * 20f - 10f).roundToInt().toFloat()))) },
        onReset = { ui.set(s.copy(tone = update(0f))) },
        changed = value != 0f,
    ) { ui.set(s.copy(toneOn = true, tone = update((value + it).coerceIn(-10f, 10f)))) })
    slider("Overall temperature", t.warmth, "Minus is cooler and brighter, plus is warmer and richer.") { t.copy(warmth = it) }
    slider("Bass extension", t.bassExtension, "How deep the lowest bass reaches.") { t.copy(bassExtension = it) }
    slider("Bass texture", t.bassPunch, "Minus is soft and smooth, plus is firm and punchy.") { t.copy(bassPunch = it) }
    slider("Note thickness", t.thickness, "Minus is lean and clean, plus is full-bodied.") { t.copy(thickness = it) }
    slider("Vocals", t.vocal, "Minus sets voices back, plus brings them forward.") { t.copy(vocal = it) }
    slider("Female overtones", t.upperVocal, "Sweetness of female voices and violins.") { t.copy(upperVocal = it) }
    slider("Sibilance", t.sibilance, "Minus tames harsh \"s\" sounds.") { t.copy(sibilance = it) }
    slider("Initial transients", t.detail, "How sharp the start of each note is.") { t.copy(detail = it) }
    slider("Air", t.air, "Sparkle and space at the very top.") { t.copy(air = it) }
}

/** Quick sound: the simple controls most people need, at the top of the Sound panel. */
fun quickItems(ui: SoundUi): List<PanelItem> = buildList {
    val s = ui.sound
    add(ChipsRow("Dialogue boost", listOf("Off", "Low", "Medium", "High"), s.dialogue) { ui.set(s.copy(dialogue = it)) })
    add(ToggleRow("Night mode", "Evens out loud explosions and quiet talk.", on = s.night) { ui.set(s.copy(night = it)) })
    add(ChipsRow("Bass", listOf("Less", "Normal", "More", "Lots", "Max"), s.bass + 1) { ui.set(s.copy(bass = it - 1)) })
    add(ChipsRow("Treble", listOf("Less", "Normal", "More", "Lots", "Max"), s.treble + 1) { ui.set(s.copy(treble = it - 1)) })
    add(StepperRow(
        "Volume boost",
        if (s.boostDb == 0) "Off" else "+${s.boostDb} dB",
        fraction = s.boostDb / 15f,
        onSet = { f -> ui.set(s.copy(boostDb = (f * 15).roundToInt())) },
        onReset = { ui.set(s.copy(boostDb = 0)) },
        changed = s.boostDb != 0,
    ) { ui.set(s.copy(boostDb = (s.boostDb + it).coerceIn(0, 15))) })
    add(ChoiceRow("Reset quick sound", "Dialogue boost, night mode, bass, treble and volume boost back to normal.", enabled = s.quickChanged()) {
        ui.set(s.resetQuick())
    })
}

/** Balance, mono, swap, width, crossfeed and dialogue. */
fun channelItems(ui: SoundUi): List<PanelItem> = buildList {
    val s = ui.sound
    val balanceText = when {
        abs(s.balance) < 0.01f -> "Centre"
        s.balance < 0 -> "Left ${(abs(s.balance) * 100).roundToInt()}%"
        else -> "Right ${(s.balance * 100).roundToInt()}%"
    }
    add(StepperRow(
        "Balance",
        balanceText,
        fraction = (s.balance + 1f) / 2f,
        onSet = { f -> ui.set(s.copy(balance = ((f * 2f - 1f) * 20).roundToInt() / 20f)) },
        onReset = { ui.set(s.copy(balance = 0f)) },
        changed = s.balance != 0f,
    ) { ui.set(s.copy(balance = ((s.balance + it * 0.05f).coerceIn(-1f, 1f) * 20).roundToInt() / 20f)) })
    add(StepperRow(
        "Stereo width",
        "${(s.width * 100).roundToInt()}%",
        fraction = s.width / 2f,
        detail = "Below 100% narrows the sound, above widens it.",
        onSet = { f -> ui.set(s.copy(width = ((f * 2f) * 20).roundToInt() / 20f)) },
        onReset = { ui.set(s.copy(width = 1f)) },
        changed = s.width != 1f,
    ) { ui.set(s.copy(width = ((s.width + it * 0.05f).coerceIn(0f, 2f) * 20).roundToInt() / 20f)) })
    add(ChipsRow("Crossfeed (headphones)", listOf("Off", "Light", "Strong"), s.crossfeed) { ui.set(s.copy(crossfeed = it)) })
    add(NoteRow("Crossfeed lets each ear hear a little of the other side, like speakers. Easier on long listening."))
    add(ChipsRow("Dialogue boost", listOf("Off", "Low", "Medium", "High"), s.dialogue) { ui.set(s.copy(dialogue = it)) })
    add(ToggleRow("Mono", "Both ears get the same sound.", on = s.mono) { ui.set(s.copy(mono = it)) })
    add(ToggleRow("Swap left and right", on = s.swap) { ui.set(s.copy(swap = it)) })
}

/** Limiter and ReplayGain (volume boost and night mode are in Quick sound). */
fun dynamicsItems(ui: SoundUi): List<PanelItem> = buildList {
    val s = ui.sound
    add(ToggleRow("Limiter", "Stops sudden peaks from distorting.", on = s.limiter) { ui.set(s.copy(limiter = it)) })
    add(ChipsRow("ReplayGain (music)", listOf("Off", "Track", "Album"), s.replayGain) { ui.set(s.copy(replayGain = it)) })
    add(NoteRow(
        "ReplayGain plays every song at the same loudness, using tags in the music files. " +
            if (ui.isMusic) "" else "It applies to music, not videos.",
    ))
}

/** Convolution with an impulse response, as in HiBy's players: a room, a speaker, or headphone correction. */
fun convolutionItems(ui: SoundUi): List<PanelItem> = buildList {
    val s = ui.sound
    add(ToggleRow(
        "Convolution",
        if (s.convFile.isBlank()) "Load an impulse response (.wav) below, then switch this on."
        else "Using ${s.convFile.substringBeforeLast('.')}. Adds on top of every other layer.",
        on = s.convOn && s.convFile.isNotBlank(),
    ) { on -> if (s.convFile.isNotBlank()) ui.set(s.copy(convOn = on)) })
    if (ui.impulses.isEmpty()) {
        add(NoteRow("No impulse responses yet. Mono, stereo and 4-channel \"true stereo\" WAV files work, any sample rate, up to 1.4 seconds."))
    }
    ui.impulses.forEach { (file, detail) ->
        add(ChoiceRow(file.substringBeforeLast('.'), detail, selected = s.convFile == file) {
            ui.set(s.copy(convFile = file, convOn = true))
        })
    }
    ui.importImpulse?.let { add(ChoiceRow("Load an impulse response (.wav)", "Kept in the app, so it only needs loading once.") { it() }) }
    if (ui.importImpulse == null) add(NoteRow("Impulse responses are loaded on a phone or tablet; a TV can use the ones already loaded on it."))
    add(StepperRow(
        "Mix",
        "${s.convMix}%",
        fraction = s.convMix / 100f,
        detail = "How much of the convolved sound to use; 100% is fully convolved.",
        onSet = { f -> ui.set(s.copy(convMix = (f * 100).roundToInt())) },
        onReset = { ui.set(s.copy(convMix = 100)) },
        changed = s.convMix != 100,
    ) { ui.set(s.copy(convMix = (s.convMix + it * 5).coerceIn(0, 100))) })
    add(StepperRow(
        "Gain",
        (if (s.convGainDb > 0) "+" else "") + "%.1f dB".format(s.convGainDb),
        fraction = (s.convGainDb + 12f) / 24f,
        detail = "Level is matched automatically; adjust to taste.",
        onSet = { f -> ui.set(s.copy(convGainDb = ((f * 24f - 12f) * 2).roundToInt() / 2f)) },
        onReset = { ui.set(s.copy(convGainDb = 0f)) },
        changed = s.convGainDb != 0f,
    ) { ui.set(s.copy(convGainDb = (s.convGainDb + it * 0.5f).coerceIn(-12f, 12f))) })
    if (s.convFile.isNotBlank()) {
        add(ChoiceRow("Delete \"${s.convFile.substringBeforeLast('.')}\"") {
            ui.deleteImpulse(s.convFile)
            ui.set(s.copy(convOn = false, convFile = ""))
        })
    }
    add(NoteRow("Adds about 10 ms of delay; use Sound delay if lips look out of step."))
}

// ---------- resets, one per panel (the "Reset" in each panel's heading) ----------

private val NORMAL = SoundSettings()

fun SoundSettings.resetQuick() = copy(dialogue = NORMAL.dialogue, night = NORMAL.night, bass = NORMAL.bass, treble = NORMAL.treble, boostDb = NORMAL.boostDb)
fun SoundSettings.quickChanged() = this != resetQuick()

fun SoundSettings.resetEq() = copy(preset = "Flat", graphic = List(10) { 0f }, bands = DEFAULT_PARAMETRIC, preampDb = 0f, autoPreamp = true)
fun SoundSettings.eqChanged() = graphic.any { it != 0f } || bands != DEFAULT_PARAMETRIC || preampDb != 0f || !autoPreamp

fun SoundSettings.resetTone() = copy(tone = Tone())
fun SoundSettings.toneChanged() = tone != Tone()

fun SoundSettings.resetChannels() = copy(balance = NORMAL.balance, width = NORMAL.width, crossfeed = NORMAL.crossfeed, dialogue = NORMAL.dialogue, mono = NORMAL.mono, swap = NORMAL.swap)
fun SoundSettings.channelsChanged() = this != resetChannels()

fun SoundSettings.resetDynamics() = copy(boostDb = NORMAL.boostDb, night = NORMAL.night, limiter = NORMAL.limiter, replayGain = NORMAL.replayGain)
fun SoundSettings.dynamicsChanged() = this != resetDynamics()

/** Keeps the chosen impulse response, but switches it off and puts mix and gain back. */
fun SoundSettings.resetConvolution() = copy(convOn = false, convMix = NORMAL.convMix, convGainDb = NORMAL.convGainDb)
fun SoundSettings.convolutionChanged() = this != resetConvolution()
